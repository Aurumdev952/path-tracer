package rw.aurum.pathtracer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The shared ARCore frame loop: camera background, display geometry and an event queue onto the
 * GL thread. Each mode extends it and does its own work in [onFrame].
 */
abstract class ArRenderer : GLSurfaceView.Renderer {

    @Volatile private var session: Session? = null
    private val events = ConcurrentLinkedQueue<() -> Unit>()

    private val background = BackgroundRenderer()
    private var textureBoundTo: Session? = null
    private var viewportChanged = false
    protected var width = 1
        private set
    protected var height = 1
        private set
    var displayRotation: () -> Int = { 0 }

    private val altitudes = ConcurrentLinkedQueue<Pair<Long, Float>>()

    /** Queues a barometer reading (milliseconds since boot, meters) for the GL thread. */
    fun pushAltitude(t: Long, meters: Float) {
        altitudes.add(t to meters)
    }

    /** Hands over the barometer readings that arrived since the last call. */
    protected fun takeAltitudes(each: (Long, Float) -> Unit) {
        while (true) {
            val (t, m) = altitudes.poll() ?: return
            each(t, m)
        }
    }

    /** Set when debug mode is on. */
    @Volatile var debug: DebugLog? = null
    private var fpsFrames = 0
    private var fpsSince = 0L
    private var lastTracking = ""

    fun attach(session: Session) {
        this.session = session
        viewportChanged = true
    }

    /** Runs [event] on the GL thread before the next frame. */
    protected fun post(event: () -> Unit) {
        events.add(event)
    }

    /** Creates this mode's GL resources. Called whenever the GL context is (re)created. */
    protected abstract fun onCreateGl()

    /** Called once per camera frame, after the camera image has been drawn. */
    protected abstract fun onFrame(session: Session, frame: Frame)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        onCreateGl()
        textureBoundTo = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        this.width = width
        this.height = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return
        if (textureBoundTo !== session) {
            session.setCameraTextureName(background.textureId)
            textureBoundTo = session
        }
        if (viewportChanged) {
            session.setDisplayGeometry(displayRotation(), width, height)
            viewportChanged = false
        }
        while (true) (events.poll() ?: break)()

        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            return
        }
        background.draw(frame)
        debug?.let { trace(it, frame.camera) }
        onFrame(session, frame)
    }

    private fun trace(debug: DebugLog, camera: Camera) {
        val now = android.os.SystemClock.elapsedRealtime()
        fpsFrames++
        if (now - fpsSince >= 1000) {
            if (fpsSince > 0) debug.show("fps", fpsFrames * 1000 / (now - fpsSince))
            fpsFrames = 0
            fpsSince = now
        }
        val tracking = if (camera.trackingState == TrackingState.PAUSED) {
            "paused/" + camera.trackingFailureReason.name.lowercase()
        } else {
            camera.trackingState.name.lowercase()
        }
        if (tracking != lastTracking) {
            debug.event("tracking", "state" to tracking)
            lastTracking = tracking
        }
        debug.show("tracking", tracking)
    }
}

/** A short instruction for when ARCore isn't tracking, or null while it is. */
fun trackingHint(camera: Camera): String? {
    if (camera.trackingState == TrackingState.TRACKING) return null
    return when (camera.trackingFailureReason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark. Turn on a light"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast. Slow down"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at something with more detail"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera is in use by another app"
        else -> "Move your phone slowly to start tracking"
    }
}

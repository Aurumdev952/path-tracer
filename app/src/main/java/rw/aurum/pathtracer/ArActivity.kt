package rw.aurum.pathtracer

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException

/**
 * A full-screen camera view with an AR mode drawn over it: handles the camera permission,
 * installing ARCore and the session lifecycle. Each mode supplies a renderer and a Compose overlay.
 */
abstract class ArActivity : ComponentActivity() {

    protected abstract val renderer: ArRenderer
    private lateinit var glView: GLSurfaceView
    private var session: Session? = null
    private var installRequested = false
    protected val blocker = mutableStateOf<String?>(null)
    /** The session log, when debug mode is on. */
    protected var debug: DebugLog? = null
        private set

    /** Feeds the barometer to the renderer, to notice elevator rides. */
    protected open val wantsBarometer = false
    private val barometer by lazy { Barometer(this) }
    /** Whether elevator rides can be noticed on this phone. */
    protected val hasBarometer: Boolean get() = wantsBarometer && barometer.available
    /** Steps tell walking up stairs from riding; without them the barometer still works. */
    private val stepSensor by lazy { StepSensor(this) }
    private val magnetSensor by lazy { MagnetSensor(this) }
    /** Asking again from every onResume would loop after a "no". */
    private var askedForSteps = false

    /** Asks for the sharpest camera image ARCore offers, for taking photos. */
    protected open val wantsSharpImages = false

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.CAMERA] == false) blocker.value = "Camera access is needed to see your way."
        }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun mayCountSteps() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACTIVITY_RECOGNITION)

    /** Sets this mode's ARCore options on top of the shared defaults. */
    protected open fun configure(session: Session, config: Config) {}

    @Composable
    protected abstract fun Overlay()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (DebugLog.isEnabled(this)) {
            debug = DebugLog.open(this, javaClass.simpleName.removeSuffix("Activity").lowercase()).also {
                it.event(
                    "start",
                    "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "android" to Build.VERSION.RELEASE,
                    "app" to runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull(),
                    "arcore" to runCatching { packageManager.getPackageInfo("com.google.ar.core", 0).versionName }.getOrNull(),
                )
                renderer.debug = it
            }
        }
        @Suppress("DEPRECATION")
        renderer.displayRotation = { windowManager.defaultDisplay.rotation }
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContent {
            PathTracerTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
                    val message = blocker.value
                    if (message != null) BlockerScreen(message) else Overlay()
                    debug?.let { DebugPanel(it, Modifier.align(Alignment.CenterStart)) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val missing = buildList {
            if (!granted(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (hasBarometer && stepSensor.available && !askedForSteps && !mayCountSteps()) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
                askedForSteps = true
            }
        }
        if (missing.isNotEmpty()) {
            permissions.launch(missing.toTypedArray())
            return
        }
        val session = session ?: createSession() ?: return
        try {
            session.resume()
        } catch (e: Exception) {
            blocker.value = "Couldn't open the camera: ${e.message}"
            return
        }
        glView.onResume()
        if (debug != null) {
            magnetSensor.onField = renderer::pushField
            magnetSensor.start()
        }
        if (wantsBarometer) {
            barometer.onAltitude = renderer::pushAltitude
            barometer.start()
            renderer.stepsKnown = stepSensor.available && mayCountSteps()
            if (renderer.stepsKnown) {
                stepSensor.onStep = renderer::pushStep
                stepSensor.start()
            }
            debug?.event("steps", "known" to renderer.stepsKnown)
        }
    }

    private fun createSession(): Session? = try {
        when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                installRequested = true
                null
            }
            ArCoreApk.InstallStatus.INSTALLED -> Session(this).also { session ->
                if (wantsSharpImages) {
                    session.getSupportedCameraConfigs(CameraConfigFilter(session))
                        .filter { it.imageSize.width <= MAX_IMAGE_WIDTH }
                        .maxByOrNull { it.imageSize.width }
                        ?.let { session.cameraConfig = it }
                }
                session.configure(
                    Config(session).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        focusMode = Config.FocusMode.AUTO
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        configure(session, this)
                    }
                )
                this.session = session
                renderer.attach(session)
                debug?.event(
                    "session",
                    "depth" to session.config.depthMode.name.lowercase(),
                    "planes" to session.config.planeFindingMode.name.lowercase(),
                    "image" to session.cameraConfig.imageSize.let { "${it.width}x${it.height}" },
                    "texture" to session.cameraConfig.textureSize.let { "${it.width}x${it.height}" },
                )
            }
        }
    } catch (e: UnavailableException) {
        debug?.event("error", "message" to e.javaClass.simpleName)
        blocker.value = "This device can't run ARCore (${e.javaClass.simpleName.removePrefix("Unavailable").removeSuffix("Exception")})."
        null
    } catch (e: Exception) {
        debug?.event("error", "message" to e.message)
        blocker.value = "Couldn't start AR: ${e.message}"
        null
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            glView.onPause()
            session?.pause()
        }
        if (wantsBarometer) {
            barometer.stop()
            stepSensor.stop()
        }
        magnetSensor.stop()
        debug?.event("pause")
        debug?.flush()
    }

    override fun onDestroy() {
        debug?.close()
        session?.close()
        session = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_IMAGE_WIDTH = 1920
    }
}

@Composable
fun PathTracerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.Black), content = content)
}

package rw.aurum.pathtracer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import java.io.File

class MainActivity : ComponentActivity() {

    private val tracer by lazy { PathTracer(TrailStore(File(filesDir, "trail.json"))) }
    private lateinit var glView: GLSurfaceView
    private var session: Session? = null
    private var installRequested = false
    private val blocker = mutableStateOf<String?>(null)

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) blocker.value = "Camera access is needed to trace your path."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        tracer.displayRotation = { windowManager.defaultDisplay.rotation }
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(tracer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
                val ui by tracer.state.collectAsState()
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
                    val message = blocker.value
                    if (message != null) BlockerScreen(message) else Overlay(
                        ui = ui,
                        onToggle = tracer::toggleRecording,
                        onNewTrail = tracer::newTrail,
                        onRetry = tracer::retrySaveRoom,
                        onMemory = tracer::useMemory,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
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
    }

    private fun createSession(): Session? = try {
        when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                installRequested = true
                null
            }
            ArCoreApk.InstallStatus.INSTALLED -> Session(this).also { session ->
                session.configure(
                    Config(session).apply {
                        cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        focusMode = Config.FocusMode.AUTO
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        augmentedImageDatabase = AugmentedImageDatabase(session).apply {
                            assets.open("start_marker.png").use { addImage("start", BitmapFactory.decodeStream(it)) }
                        }
                    }
                )
                this.session = session
                tracer.attach(session)
            }
        }
    } catch (e: UnavailableException) {
        blocker.value = "This device can't run ARCore (${e.javaClass.simpleName.removePrefix("Unavailable").removeSuffix("Exception")})."
        null
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            glView.onPause()
            session?.pause()
        }
        tracer.persist()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }
}

private val Accent = Color(0xFF2EE6C8)
private val Glass = Color(0xB3101418)

@Composable
private fun Overlay(
    ui: UiState,
    onToggle: () -> Unit,
    onNewTrail: () -> Unit,
    onRetry: () -> Unit,
    onMemory: (Memory) -> Unit,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusPill(ui.room, ui.memory)
            if (ui.room == Room.FindingStart) {
                Spacer(Modifier.size(10.dp))
                MemoryPicker(ui.memory, onMemory)
            }
        }

        if (ui.room == Room.FindingStart || ui.room == Room.Relocalizing) {
            Reticle(Modifier.align(Alignment.Center))
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val hint = ui.hint ?: instruction(ui.room, ui.memory)
            AnimatedVisibility(hint != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    hint.orEmpty(),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .background(Glass, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Glass, RoundedCornerShape(24.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("%.1f m".format(ui.meters), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(if (ui.recording) "walked" else "paused", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
                if (ui.room is Room.CloudFailed) {
                    OutlinedButton(onClick = onRetry) { Text("Retry save") }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = onNewTrail) { Text("New") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onToggle,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                ) { Text(if (ui.recording) "Pause" else "Resume") }
            }
        }
    }
}

private fun instruction(room: Room, memory: Memory): String? = when (room) {
    Room.FindingStart -> when (memory) {
        Memory.Marker -> "Put the START HERE marker where you begin, then point your camera at it"
        Memory.Cloud -> "Point your camera at the floor where you're standing"
    }
    Room.Relocalizing -> when (memory) {
        Memory.Marker -> "Welcome back. Point at your START HERE marker to bring your path back"
        Memory.Cloud -> "Welcome back. Look around where you started to bring your path back"
    }
    is Room.NotFound -> "${room.reason}. Tap New to start a fresh path"
    is Room.CloudFailed -> "Room not saved: ${room.reason}"
    else -> null
}

@Composable
private fun StatusPill(room: Room, memory: Memory) {
    val (label, color) = when (room) {
        Room.FindingStart -> (if (memory == Memory.Marker) "Looking for marker" else "Finding floor") to Color(0xFFFFC857)
        Room.Relocalizing -> "Recognising room" to Color(0xFFFFC857)
        Room.Anchoring -> "Tracing · saving room" to Accent
        Room.Remembered -> "Tracing · room remembered" to Accent
        is Room.CloudFailed -> "Tracing · not saved" to Color(0xFFFF6B6B)
        is Room.NotFound -> "Room not found" to Color(0xFFFF6B6B)
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot",
    )
    Row(
        Modifier.background(Glass, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).alpha(pulse).background(color, CircleShape))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MemoryPicker(selected: Memory, onPick: (Memory) -> Unit) {
    Row(Modifier.background(Glass, CircleShape).padding(4.dp)) {
        Memory.entries.forEach { memory ->
            val active = memory == selected
            Text(
                if (memory == Memory.Marker) "Marker · offline" else "Cloud",
                color = if (active) Color.Black else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(if (active) Accent else Color.Transparent, CircleShape)
                    .clickable { onPick(memory) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun Reticle(modifier: Modifier) {
    val scale by rememberInfiniteTransition(label = "reticle").animateFloat(
        initialValue = 0.85f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "scale",
    )
    Box(modifier.size(64.dp).scale(scale).border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).background(Accent, CircleShape))
    }
}

@Composable
private fun BlockerScreen(message: String) {
    Box(Modifier.fillMaxSize().background(Color.Black).padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Color.White, textAlign = TextAlign.Center, fontSize = 18.sp)
    }
}

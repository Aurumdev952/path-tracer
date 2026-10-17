package rw.aurum.pathtracer

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.io.File

/** Free-walk mode: a glowing trail behind you that comes back on your next visit. */
class TrailActivity : ArActivity() {

    private val tracer by lazy { PathTracer(TrailStore(File(filesDir, "trail.json"))) }
    override val renderer: ArRenderer get() = tracer

    override fun configure(session: Session, config: Config) {
        config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
        config.augmentedImageDatabase = AugmentedImageDatabase(session).apply {
            assets.open("start_marker.png").use { addImage("start", BitmapFactory.decodeStream(it)) }
        }
    }

    @Composable
    override fun Overlay() {
        val ui by tracer.state.collectAsState()
        TrailOverlay(
            ui = ui,
            onToggle = tracer::toggleRecording,
            onNewTrail = tracer::newTrail,
            onRetry = tracer::retrySaveRoom,
            onMemory = tracer::useMemory,
        )
    }

    override fun onPause() {
        super.onPause()
        tracer.persist()
    }
}

@Composable
private fun TrailOverlay(
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

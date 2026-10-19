package rw.aurum.pathtracer

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Config
import com.google.ar.core.Session
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Records a route: photo of a sign at the entrance, then walk, with photos after elevators. */
class RecordActivity : ArActivity() {

    private val store by lazy { RouteStore(File(filesDir, "routes")) }
    private val recorder by lazy { RouteRecorder(store) }
    override val renderer: ArRenderer get() = recorder
    override val wantsSharpImages = true
    override val wantsBarometer = true

    override fun configure(session: Session, config: Config) {
        // Walls as well as floors, and depth when the phone has it: both let the recorder measure
        // a sign the moment it is photographed, so ARCore can lock on to it right away.
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) config.depthMode = Config.DepthMode.AUTOMATIC
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        if (savedInstanceState == null) store.clearDraft()
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun Overlay() {
        val ui by recorder.state.collectAsState()
        val saved = ui.stage as? RecordStage.Saved
        LaunchedEffect(saved) {
            if (saved != null) {
                Toast.makeText(this@RecordActivity, "Route saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        RecordOverlay(
            ui = ui,
            corners = recorder.corners,
            onShutter = recorder::takePhoto,
            onRetake = recorder::retake,
            onCancelCheckpoint = recorder::cancelCheckpoint,
            onCheckpoint = recorder::addCheckpoint,
            onFloorChange = { change, floor ->
                recorder.changeFloor(change, floor)
                if (change != FloorChange.Elevator) {
                    Toast.makeText(this, "${change.label.replaceFirstChar { it.uppercase() }} added. Keep walking", Toast.LENGTH_SHORT).show()
                }
            },
            onFinish = recorder::finish,
            autoElevator = hasBarometer,
            onConfirmFloor = recorder::confirmFloor,
            onNotElevator = recorder::notAnElevator,
            onDiscard = ::finish,
        )
    }
}

@Composable
private fun RecordOverlay(
    ui: RecordUi,
    corners: StateFlow<FloatArray>,
    onShutter: () -> Unit,
    onRetake: () -> Unit,
    onCancelCheckpoint: () -> Unit,
    onCheckpoint: () -> Unit,
    onFloorChange: (FloorChange, String) -> Unit,
    onFinish: (String) -> Unit,
    /** Elevator rides are noticed on their own (the phone has a barometer). */
    autoElevator: Boolean,
    onConfirmFloor: (String) -> Unit,
    onNotElevator: () -> Unit,
    onDiscard: () -> Unit,
) {
    var askFloor by remember { mutableStateOf(false) }
    var askName by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    BackHandler { if (ui.photos > 0) askDiscard = true else onDiscard() }

    val stage = ui.stage
    val reason = (stage as? RecordStage.Capture)?.reason ?: (stage as? RecordStage.Locking)?.reason
    Box(Modifier.fillMaxSize()) {
        if (stage is RecordStage.Capture || stage is RecordStage.Locking) {
            FramingBox(locking = stage is RecordStage.Locking, ready = ui.status?.good == true, corners = corners) {
                if (ui.detail != null) DetailMeter(ui.detail)
                ui.status?.let { SignStatusLine(it) }
            }
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                when (stage) {
                    is RecordStage.Capture -> Pill(if (reason == PhotoFor.Start) "Photo of the entrance" else "Take a photo", Warn)
                    is RecordStage.Locking -> Pill("Locking on…", Warn)
                    RecordStage.Walking -> Pill(
                        "Recording" + (if (ui.floor.isNotBlank()) " · floor ${ui.floor}" else "") +
                            (if (ui.photos > 1) " · ${ui.photos} photos" else ""),
                        Accent,
                    )
                    is RecordStage.Riding -> Pill("In the elevator · going ${if (stage.rise < 0) "down" else "up"}", Accent)
                    is RecordStage.Saved -> Pill("Saved", Accent)
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val text = ui.hint ?: when (stage) {
                    is RecordStage.Capture -> when (val r = stage.reason) {
                        PhotoFor.Start -> "Stand at the entrance. Fit a sign, poster or door plate in the box, then tap the button"
                        is PhotoFor.Elevator -> (if (r.floor.isNotBlank()) "Floor ${r.floor}. " else "") +
                            "When you step out of the elevator, photograph a sign near it, like the floor number"
                        PhotoFor.Checkpoint -> "Fit a sign in the box to add a checkpoint. It keeps long routes accurate"
                    }
                    is RecordStage.Locking -> if (stage.slow) {
                        "Still looking. Step a little closer, or retake the photo of a sign with more detail"
                    } else {
                        "Keep the sign in view and move your phone slowly from side to side"
                    }
                    RecordStage.Walking -> when {
                        ui.meters >= 4f -> null
                        autoElevator -> "Walk to your destination. Elevators are noticed on their own; tap Floor change for stairs or escalators"
                        else -> "Walk to your destination. Tap Floor change before an elevator, stairs or an escalator"
                    }
                    is RecordStage.Riding -> "Recording is paused during the ride. You'll photograph a sign when you step out"
                    is RecordStage.Saved -> null
                }
                if (text != null) Hint(text, Modifier.padding(bottom = 12.dp))

                when (stage) {
                    is RecordStage.Capture -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (reason == PhotoFor.Checkpoint) {
                            OutlinedButton(onClick = onCancelCheckpoint) { Text("Cancel") }
                            Spacer(Modifier.size(24.dp))
                        }
                        Shutter(onShutter)
                        if (reason == PhotoFor.Checkpoint) Spacer(Modifier.size(96.dp))
                    }
                    is RecordStage.Locking -> Row(
                        Modifier.fillMaxWidth().background(Glass, RoundedCornerShape(24.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PhotoThumb(ui.photo, 56)
                        Spacer(Modifier.weight(1f))
                        if (reason == PhotoFor.Checkpoint) OutlinedButton(onClick = onCancelCheckpoint) { Text("Cancel") }
                        OutlinedButton(onClick = onRetake) { Text("Retake") }
                    }
                    RecordStage.Walking -> Column(
                        Modifier.fillMaxWidth().background(Glass, RoundedCornerShape(24.dp)).padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("%.1f m".format(ui.meters), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                                Text("recorded", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                            }
                            Button(
                                onClick = { askName = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                            ) { Text("Arrived") }
                        }
                        Spacer(Modifier.size(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { askFloor = true }, modifier = Modifier.weight(1f)) { Text("Floor change") }
                            OutlinedButton(onClick = onCheckpoint, modifier = Modifier.weight(1f)) { Text("Checkpoint") }
                        }
                    }
                    is RecordStage.Riding -> Column(
                        Modifier.fillMaxWidth().background(Glass, RoundedCornerShape(24.dp)).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TurnArrow(if (stage.rise < 0) Math.PI.toFloat() else null, 44.dp, color = Accent, bent = false)
                        Text("%.0f m".format(kotlin.math.abs(stage.rise)), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("about ${floorsIn(stage.rise)} floors", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                        if (stage.auto) TextButton(onClick = onNotElevator) { Text("Not in an elevator") }
                    }
                    is RecordStage.Saved -> Unit
                }
            }
        }
    }

    if (askFloor) FloorChangeDialog(
        onDismiss = { askFloor = false },
        onConfirm = { change, floor ->
            askFloor = false
            onFloorChange(change, floor)
        },
    )
    if (askName) NameDialog(
        title = "Name this route",
        initial = "",
        confirm = "Save",
        onDismiss = { askName = false },
        onConfirm = {
            askName = false
            onFinish(it)
        },
    )
    val question = ui.askFloor
    if (question != null && stage is RecordStage.Capture) {
        NameDialog(
            title = "Which floor are you on?",
            initial = question.guess,
            confirm = "OK",
            label = "You rode ${question.ride}",
            onDismiss = { onConfirmFloor(question.guess) },
            onConfirm = onConfirmFloor,
        )
    }
    if (askDiscard) AlertDialog(
        onDismissRequest = { askDiscard = false },
        title = { Text("Discard this route?") },
        text = { Text("What you've recorded so far will be lost.") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { askDiscard = false }) { Text("Keep recording") } },
    )
}

/**
 * The square the sign must fit in. Matches [SignPhoto.BOX], centered on the screen. While framing,
 * dots mark the [corners] ARCore can grab onto and the border turns green when the sign is [ready];
 * [below] goes under the box.
 */
@Composable
private fun FramingBox(locking: Boolean, ready: Boolean, corners: StateFlow<FloatArray>, below: @Composable () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "lock").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha",
    )
    val dots = corners.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = maxWidth * SignPhoto.BOX
        Box(
            Modifier
                .size(side)
                .alpha(if (locking) pulse else 1f)
                .border(3.dp, if (locking || ready) Accent else Color.White, RoundedCornerShape(18.dp))
        ) {
            // Read the corners only while drawing, so new ones redraw the dots and nothing else.
            if (!locking) Canvas(Modifier.fillMaxSize()) {
                val c = dots.value
                for (i in 0 until c.size / 2) {
                    drawCircle(Accent.copy(alpha = 0.85f), radius = 2.5.dp.toPx(), center = Offset(c[2 * i] * size.width, c[2 * i + 1] * size.height))
                }
            }
        }
        Column(
            Modifier.offset(y = side / 2 + 44.dp).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { below() }
    }
}

/** How much detail is in the box: three bars, like signal strength. */
@Composable
private fun DetailMeter(level: SignDetail.Level) {
    val bars = level.ordinal + 1
    val color = if (level == SignDetail.Level.Weak) Warn else Accent
    Row(
        Modifier.background(Glass, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Detail", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.size(4.dp))
        for (i in 1..3) {
            Box(Modifier.size(width = 14.dp, height = 6.dp).background(if (i <= bars) color else Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.size(4.dp))
        Text(if (level == SignDetail.Level.Ok) "OK" else level.name, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SignStatusLine(status: SignStatus) {
    Text(
        status.text,
        color = if (status.good) Accent else Warn,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.background(Glass, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Shutter(onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp)
            .clip(CircleShape).background(Color.White).clickable(onClick = onClick)
    )
}

@Composable
fun PhotoThumb(file: File?, sizeDp: Int) {
    val bitmap = remember(file) { file?.let { BitmapFactory.decodeFile(it.path) } }
    if (bitmap == null) {
        Box(Modifier.size(sizeDp.dp).background(Color.DarkGray, RoundedCornerShape(10.dp)))
        return
    }
    Image(
        bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun FloorChangeDialog(onDismiss: () -> Unit, onConfirm: (FloorChange, String) -> Unit) {
    var change by remember { mutableStateOf(FloorChange.Elevator) }
    var floor by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Changing floor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.background(Color.White.copy(alpha = 0.08f), CircleShape).padding(4.dp)) {
                    FloorChange.entries.forEach { option ->
                        val active = option == change
                        Text(
                            option.label.replaceFirstChar { it.uppercase() },
                            color = if (active) Color.Black else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .background(if (active) Accent else Color.Transparent, CircleShape)
                                .clickable { change = option }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = floor,
                    onValueChange = { floor = it.take(12) },
                    label = { Text("Going to floor (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                )
                Text(
                    if (change == FloorChange.Elevator) {
                        "After the ride you'll take a photo of a sign near the elevator."
                    } else {
                        "Keep recording while you take the ${change.label}."
                    },
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(change, floor.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    label: String = "Like \"Room 512\" or \"Radiology\"",
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

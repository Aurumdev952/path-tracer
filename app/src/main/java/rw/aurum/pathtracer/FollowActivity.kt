package rw.aurum.pathtracer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** Guides a follower along a saved route, starting from the photo of its entrance. */
class FollowActivity : ArActivity() {

    private val store by lazy { RouteStore(File(filesDir, "routes")) }
    private lateinit var route: Route
    private val stretches by lazy { route.stretches() }
    private val follower by lazy { RouteFollower(route) }
    override val renderer: ArRenderer get() = follower
    override val wantsBarometer = true
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val voice = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        val loaded = intent.getStringExtra(EXTRA_ROUTE)?.let(store::load)
        route = loaded ?: Route("missing", "", 0L, emptyList())
        super.onCreate(savedInstanceState)
        if (loaded == null) {
            finish()
            return
        }
        debug?.event(
            "route", "id" to route.id, "name" to route.name, "legs" to route.legs.size,
            "stretches" to stretches.size, "meters" to route.lengthMeters,
            "photo_widths" to route.legs.joinToString(",") { "%.2f".format(Locale.US, it.photoWidth) },
        )
        voice.value = getPreferences(MODE_PRIVATE).getBoolean(PREF_VOICE, true)
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val engine = tts ?: return@TextToSpeech
            val ok = engine.setLanguage(Locale.getDefault())
            if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED) engine.setLanguage(Locale.US)
            ttsReady = true
            speak(follower.state.value.speech)
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun speak(text: String) {
        if (!voice.value || !ttsReady || text.isEmpty()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guide")
    }

    private fun toggleVoice() {
        voice.value = !voice.value
        debug?.event("voice", "on" to voice.value)
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_VOICE, voice.value).apply()
        if (!voice.value) tts?.stop()
    }

    override fun configure(session: Session, config: Config) {
        debug?.event("depth_supported", "value" to session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
            follower.useDepth = true
        }
        config.augmentedImageDatabase = AugmentedImageDatabase(session).apply {
            route.legs.forEachIndexed { i, leg ->
                val bitmap = store.loadPhoto(route, leg) ?: return@forEachIndexed
                try {
                    if (leg.photoWidth > 0f) addImage("leg$i", bitmap, leg.photoWidth) else addImage("leg$i", bitmap)
                } catch (e: ImageInsufficientQualityException) {
                    // Can't happen for a photo that was locked on while recording; skip it if it does.
                }
            }
        }
    }

    @Composable
    override fun Overlay() {
        val ui by follower.state.collectAsState()
        LaunchedEffect(ui.buzz) { if (ui.buzz > 0) buzz() }
        LaunchedEffect(ui.speechId) { speak(ui.speech) }
        FollowOverlay(
            route = route,
            ui = ui,
            voice = voice.value,
            onVoice = ::toggleVoice,
            stretchFor = { stretches[it] },
            photoFor = { stretch -> store.photo(route, route.legs[stretches[stretch].firstLeg]) },
            onSkip = follower::skip,
            onStop = ::finish,
        )
    }

    private fun buzz() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(70)
        }
    }

    companion object {
        private const val EXTRA_ROUTE = "route"
        private const val PREF_VOICE = "voice"

        fun start(context: Context, route: Route) {
            context.startActivity(Intent(context, FollowActivity::class.java).putExtra(EXTRA_ROUTE, route.id))
        }
    }
}

@Composable
private fun FollowOverlay(
    route: Route,
    ui: FollowUi,
    voice: Boolean,
    onVoice: () -> Unit,
    stretchFor: (Int) -> Stretch,
    photoFor: (Int) -> File,
    onSkip: () -> Unit,
    onStop: () -> Unit,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        val stage = ui.stage
        val ride = ui.ride?.takeIf { stage is FollowStage.Searching && (it.riding || it.check is RideCheck.NotYet || it.check is RideCheck.TooFar) }
        when (stage) {
            is FollowStage.Searching -> if (ride != null) {
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth().background(Glass, RoundedCornerShape(24.dp)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { RideCard(ride) }
            } else {
                Pill(if (stage.stretch == 0) "Finding the start" else "Looking for the next sign", Warn, Modifier.align(Alignment.TopCenter))
            }
            FollowStage.Guiding -> {
                ui.instruction?.let { InstructionCard(it, ui, Modifier.align(Alignment.TopCenter)) }
                AnimatedVisibility(
                    ui.arrow != null,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    LookArrow(ui.arrow ?: 0f)
                }
            }
            FollowStage.Arrived -> Arrived(route, onStop, Modifier.align(Alignment.Center))
        }

        if (ui.stage != FollowStage.Arrived) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (stage is FollowStage.Searching && ride == null) {
                    Searching(route, stretchFor(stage.stretch).entry, photoFor(stage.stretch), ui.status, Modifier.padding(bottom = 12.dp))
                }
                ui.hint?.let { Hint(it, Modifier.padding(bottom = 12.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.canSkip) OutlinedButton(onClick = onSkip) { Text("I'm at the elevator") }
                    OutlinedButton(onClick = onVoice) { Text(if (voice) "Voice on" else "Voice off") }
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                }
            }
        }
    }
}

/** The sign to look for, kept low on the screen so the camera view and the sign stay in sight. */
@Composable
private fun Searching(route: Route, entry: LegEntry, photo: File, status: SignStatus?, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().background(Glass, RoundedCornerShape(24.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PhotoThumb(photo, 96)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (entry) {
                    is LegEntry.Elevator -> "Take the elevator" + (if (entry.floor.isNotBlank()) " to floor ${entry.floor}" else "")
                    else -> "Go to the entrance of ${route.name}"
                },
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                if (entry is LegEntry.Elevator) "When you step out, point your camera at this sign" else "Find this sign and point your camera at it",
                color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
            )
            status?.let { Text(it.text, color = if (it.good) Accent else Warn, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

/** In the elevator: how far along the ride is, or why this isn't the floor yet. */
@Composable
private fun RideCard(ride: RideStatus) {
    val to = if (ride.floor.isNotBlank()) " to floor ${ride.floor}" else ""
    val wrong = !ride.riding && ride.check != null && ride.check != RideCheck.Arrived
    Pill(if (ride.riding) "Riding$to" else "Elevator stopped", if (wrong) Warn else Accent)
    TurnArrow(if (ride.rise < 0) Math.PI.toFloat() else null, 64.dp, color = if (wrong) Warn else Accent, bent = false)
    val expected = ride.expected
    Text(
        if (expected != null) "about ${floorsIn(ride.rise)} of ${floorsIn(expected)} floors" else "about ${floorsIn(ride.rise)} floors",
        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
    )
    if (expected != null) {
        val done = (ride.rise / expected).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(8.dp).background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(done).fillMaxSize().background(Accent, RoundedCornerShape(4.dp)))
        }
    }
    Text(
        ride.check?.let { rideMessage(it, ride.floor) } ?: "Stay in the elevator. I'll tell you when you're there",
        color = if (wrong) Warn else Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center,
    )
}

@Composable
private fun InstructionCard(instruction: Instruction, ui: FollowUi, modifier: Modifier) {
    val color = if (ui.offRoute) Warn else Color.White
    Column(modifier.fillMaxWidth().background(Glass, RoundedCornerShape(24.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InstructionIcon(instruction, 60.dp, color)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(instruction.title, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(instruction.detail, color = color.copy(alpha = 0.8f), fontSize = 18.sp)
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            listOfNotNull("${meters(ui.remaining)} to go", ui.floor.takeIf { it.isNotBlank() }?.let { "Floor $it" }).joinToString(" · "),
            color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
        )
    }
}

@Composable
private fun InstructionIcon(instruction: Instruction, size: Dp, color: Color) {
    when (instruction.kind) {
        Instruction.Kind.Turn, Instruction.Kind.Straight, Instruction.Kind.OffRoute ->
            TurnArrow(instruction.turn, size, color = color)
        Instruction.Kind.Floor -> Canvas(Modifier.size(size)) {
            // Up and down chevrons, like elevator buttons.
            val w = this.size.width
            val stroke = Stroke(width = w * 0.1f)
            fun chevron(y: Float, up: Boolean) {
                val d = if (up) -1f else 1f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.25f, y - d * w * 0.12f)
                    lineTo(w * 0.5f, y + d * w * 0.12f)
                    lineTo(w * 0.75f, y - d * w * 0.12f)
                }
                drawPath(path, color, style = stroke)
            }
            chevron(w * 0.3f, up = true)
            chevron(w * 0.7f, up = false)
        }
        Instruction.Kind.Arrive -> Canvas(Modifier.size(size)) {
            val r = this.size.width / 2
            drawCircle(color, radius = r * 0.85f, style = Stroke(width = r * 0.18f))
            drawCircle(color, radius = r * 0.35f, center = Offset(r, r))
        }
    }
}

/** A big arrow that shows up when the path is off screen. */
@Composable
private fun LookArrow(bearing: Float) {
    val pulse by rememberInfiniteTransition(label = "look").animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "scale",
    )
    val deg = Math.toDegrees(bearing.toDouble())
    val text = when {
        abs(deg) > 140 -> "The path is behind you"
        deg > 0 -> "Turn left to see the path"
        else -> "Turn right to see the path"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TurnArrow(bearing, 150.dp, Modifier.scale(pulse), color = Accent, bent = false)
        Spacer(Modifier.size(8.dp))
        Hint(text)
    }
}

@Composable
private fun Arrived(route: Route, onDone: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().background(Glass, RoundedCornerShape(24.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InstructionIcon(Instruction(Instruction.Kind.Arrive, "", ""), 72.dp, Accent)
        Text("You've arrived", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(route.name, color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, textAlign = TextAlign.Center)
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
        ) { Text("Done") }
    }
}

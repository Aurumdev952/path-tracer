package rw.aurum.pathtracer

import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.hypot

sealed interface FollowStage {
    /** Waiting to see the photo that starts [stretch]: the entrance, or a sign after the elevator. */
    data class Searching(val stretch: Int) : FollowStage
    data object Guiding : FollowStage
    data object Arrived : FollowStage
}

data class FollowUi(
    val stage: FollowStage = FollowStage.Searching(0),
    val hint: String? = null,
    val instruction: Instruction? = null,
    val remaining: Float = 0f,
    val floor: String = "",
    val offRoute: Boolean = false,
    /** Which way to turn to see the path, in radians (positive left), or null when it's in view. */
    val arrow: Float? = null,
    /** Goes up by one each time the phone should buzz (a turn or floor change coming up). */
    val buzz: Int = 0,
    /** Near the end of a stretch that leads to an elevator, offer to move on by hand. */
    val canSkip: Boolean = false,
    /** What to say out loud; [speechId] goes up by one for each new thing to say. */
    val speech: String = "",
    val speechId: Int = 0,
    /** In or just out of an elevator on the way to the next stretch. */
    val ride: RideStatus? = null,
    /** While searching: what ARCore makes of the sign photos. */
    val status: SignStatus? = null,
)

/** [rise] ridden so far against the recorded [expected] rise (null if not measured). */
data class RideStatus(val rise: Float, val expected: Float?, val floor: String, val riding: Boolean, val check: RideCheck?)

/**
 * Guides a follower along a saved route. Photos in the route are ARCore Augmented Images named
 * `leg<index>`; seeing one fixes where the route is in the room.
 */
class RouteFollower(route: Route) : ArRenderer() {

    private val _state = MutableStateFlow(FollowUi())
    val state: StateFlow<FollowUi> = _state

    private val navigator = Navigator(route)
    private val guideRenderer = GuideRenderer()
    private val outlineRenderer = SignOutlineRenderer()
    private val depth = DepthTexture()
    private val floorTracker = FloorTracker()
    private val elevator = ElevatorDetector()
    private val keeper = AnchorKeeper { name, fields -> debug?.event(name, *fields) }
    /** Meters ridden since leaving the last stretch for an elevator. */
    private var riddenRise = 0f
    private var riding = false
    private var rideCheck: RideCheck? = null

    /** Set by the activity when the phone supports ARCore depth and it is switched on. */
    @Volatile var useDepth = false

    private var stage: FollowStage = FollowStage.Searching(0)
    private var anchor: Anchor? = null
    /** Turns the photo's frame into where the stretch really lies, learned from the walk. */
    private var aligner: RouteAligner? = null
    /** While still near the photo just found: what ARCore saw of it, averaged into the anchor. */
    private val settle = ArrayDeque<YawPose>()
    private var settleLeg = -1
    private var foundFrom: Pose? = null
    private var progress = 0f
    private var lastSnapFrame = 0L
    private var frameCount = 0L
    private var buzz = 0
    private var buzzedFor = -1
    private var buzzedStretch = -1
    private var speech = ""
    private var speechId = 0
    /** Stretch, maneuver and kind of the instruction last spoken, so each is said once. */
    private var spokenFor: Triple<Int, Int, Instruction.Kind>? = null
    private var searchStartedAt = SystemClock.elapsedRealtime()
    private var lastSample = 0L
    private var wasOffRoute = false
    private var seen = ImageSeen.None
    /** The photo just found, outlined for a moment so the follower sees which sign it was. */
    private var foundImage: AugmentedImage? = null
    private var foundAt = 0L

    init {
        say("Go to the entrance and point your camera at the sign in the photo")
    }

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val startTime = SystemClock.elapsedRealtime()

    /** The follower says they're out of the elevator already: wait for the next photo. */
    fun skip() = post {
        if (stage == FollowStage.Guiding && !navigator.isLastStretch) {
            debug?.event("skip_pressed", "stretch" to navigator.stretch)
            navigator.skip()
            searchNext()
        }
    }

    override fun onCreateGl() {
        guideRenderer.create()
        outlineRenderer.create()
        depth.create()
    }

    override fun onFrame(session: Session, frame: Frame) {
        frameCount++
        val camera = frame.camera
        var hint = trackingHint(camera)
        var guidance: Guidance? = null
        var arrow: Float? = null
        if (useDepth) depth.update(frame)
        if (camera.trackingState == TrackingState.TRACKING) {
            elevator.position(SystemClock.elapsedRealtime(), camera.pose.tx(), camera.pose.tz())
        }
        elevator.stepsKnown = stepsKnown
        takeSteps(elevator::step)
        takeAltitudes { t, m -> elevator.altitude(t, m)?.let(::onElevator) }
        debug?.show("baro_m", elevator.altitude)

        if (camera.trackingState == TrackingState.TRACKING && stage != FollowStage.Arrived) {
            snapToPhotos(session, frame)
            (stage as? FollowStage.Searching)?.let { watchPhotos(session, it.stretch) }
            this.anchor?.takeIf { stage == FollowStage.Guiding }?.let { held ->
                keeper.check(session, camera, held, SystemClock.elapsedRealtime())?.let { replacement ->
                    held.detach()
                    anchor = replacement
                }
            }
            val anchor = anchor
            if (stage == FollowStage.Guiding && anchor != null) {
                if (anchor.trackingState == TrackingState.TRACKING) {
                    val g = guide(session, camera, anchor)
                    guidance = g
                    arrow = arrowTo(camera, stretchPose(anchor), g)
                } else {
                    hint = hint ?: "Hold on, finding the path again"
                }
            }
        }
        drawFoundOutline(camera)
        publish(hint, guidance, arrow)
    }

    /** While searching: whether ARCore has spotted any of the photos still ahead. */
    private fun watchPhotos(session: Session, stretch: Int) {
        var best = ImageSeen.None
        for (image in session.getAllTrackables(AugmentedImage::class.java)) {
            val leg = image.name.removePrefix("leg").toIntOrNull() ?: continue
            if (navigator.stretchOf(leg) < stretch) continue
            when (image.trackingState) {
                TrackingState.TRACKING -> best = ImageSeen.Tracking
                TrackingState.PAUSED -> if (best == ImageSeen.None) best = ImageSeen.Spotted
                else -> Unit
            }
        }
        if (best != seen) {
            debug?.event("image_state", "seen" to best.name.lowercase())
            seen = best
        }
    }

    private fun drawFoundOutline(camera: Camera) {
        val image = foundImage ?: return
        val age = SystemClock.elapsedRealtime() - foundAt
        if (age > OUTLINE_MS) {
            foundImage = null
            return
        }
        if (image.trackingState != TrackingState.TRACKING) return
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        val fade = 1f - ((age - OUTLINE_MS / 2f) / (OUTLINE_MS / 2f)).coerceIn(0f, 1f)
        outlineRenderer.draw(image, SignOutlineRenderer.Green, fade, view, projection)
    }

    /**
     * Places the route from its photos. A newly found photo places it; while the follower is still
     * near it, more views are averaged in. ARCore knows where a photo is much better than which way
     * it faces, so after that a photo only pins where it is, and the walk sets the direction.
     */
    private fun snapToPhotos(session: Session, frame: Frame) {
        val minStretch = when (val s = stage) {
            is FollowStage.Searching -> s.stretch
            else -> navigator.stretch
        }
        for (image in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
            if (image.trackingState != TrackingState.TRACKING ||
                image.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING
            ) continue
            val leg = image.name.removePrefix("leg").toIntOrNull() ?: continue
            val stretchIndex = navigator.stretchOf(leg)
            if (stretchIndex < minStretch) continue
            val inStretch = navigator.stretches[stretchIndex].images.first { it.leg == leg }.pose
            val world = levelled(image.centerPose).compose(inStretch.inverse().toPose())
            val cam = frame.camera.pose

            if (stretchIndex != navigator.stretch || stage != FollowStage.Guiding) {
                debug?.event("found", "leg" to leg, "stretch" to stretchIndex, "after_ms" to SystemClock.elapsedRealtime() - searchStartedAt)
                navigator.lockOn(leg)
                riddenRise = 0f
                rideCheck = null
                foundImage = image
                foundAt = SystemClock.elapsedRealtime()
                spokenFor = null
                stage = FollowStage.Guiding
                settle.clear()
                settle += world.toYawPose()
                settleLeg = leg
                foundFrom = cam
                place(session, world, stretchIndex, leg)
                return
            }
            if (frameCount - lastSnapFrame < SNAP_EVERY_FRAMES) continue
            val from = foundFrom
            val near = from != null && hypot(cam.tx() - from.tx(), cam.tz() - from.tz()) < SETTLE_METERS
            if (leg == settleLeg && near) {
                settle += world.toYawPose()
                if (settle.size > SETTLE_VIEWS) settle.removeFirst()
                val mean = steadyPose(settle, Float.MAX_VALUE, Float.MAX_VALUE)!!.toPose()
                debug?.let { d ->
                    val was = anchor?.pose
                    val drift = was?.let { hypot(hypot(mean.tx() - it.tx(), mean.ty() - it.ty()), mean.tz() - it.tz()) }
                    val turn = was?.let { Math.toDegrees((mean.toYawPose().yaw - it.toYawPose().yaw).toDouble()) }
                    d.event("snap", "leg" to leg, "views" to settle.size, "drift_m" to drift, "turn_deg" to turn)
                }
                place(session, mean, stretchIndex, leg)
            } else {
                val anchor = anchor ?: continue
                val aligner = aligner ?: continue
                val c = anchor.pose.inverse().transformPoint(image.centerPose.translation)
                val seen = TrailPoint(c[0], c[1], c[2])
                val expected = TrailPoint(inStretch.x, inStretch.y, inStretch.z)
                val before = aligner.fit.apply(seen)
                aligner.landmark(leg, seen, expected)
                debug?.event("landmark", "leg" to leg, "error_m" to hypot(before.x - expected.x, before.z - expected.z))
                lastSnapFrame = frameCount
            }
            return
        }
    }

    /** Puts the stretch at [world], with photo [leg] pinning its own spot in it. */
    private fun place(session: Session, world: Pose, stretchIndex: Int, leg: Int) {
        dropAnchor()
        anchor = session.createAnchor(world)
        val stretch = navigator.stretches[stretchIndex]
        val image = stretch.images.indexOfFirst { it.leg == leg }
        val at = stretch.images[image].pose
        val photo = TrailPoint(at.x, at.y, at.z)
        aligner = RouteAligner(stretch.path).also { it.landmark(leg, photo, photo) }
        progress = stretch.path.distanceAt(stretch.legStarts[image])
        lastSnapFrame = frameCount
    }

    /** Where the stretch's frame is in the world: the photo's, corrected by the walk. */
    private fun stretchPose(anchor: Anchor): Pose =
        anchor.pose.compose((aligner?.fit ?: YawPose.Identity).inverse().toPose())

    private fun dropAnchor() {
        anchor?.detach()
        anchor = null
        aligner = null
        keeper.reset()
    }

    private fun guide(session: Session, camera: Camera, anchor: Anchor): Guidance {
        val cam = camera.pose
        val local = anchor.pose.inverse().transformPoint(floatArrayOf(cam.tx(), floorTracker.floorY(session, camera), cam.tz()))
        val here = TrailPoint(local[0], local[1], local[2])
        val aligner = aligner
        if (aligner?.walk(here, progress) == true) {
            val f = aligner.fit
            debug?.event(
                "align", "walked_m" to aligner.walked, "x" to here.x, "z" to here.z, "s" to progress,
                "turn_deg" to Math.toDegrees(f.yaw.toDouble()), "shift_x" to f.x, "shift_z" to f.z,
            )
        }
        val fit = aligner?.fit ?: YawPose.Identity
        val g = navigator.update(fit.apply(here))
        progress = g.progress

        // Buzz and speak once when each turn, floor change or end comes within reach. Also speak
        // when a new instruction comes up, e.g. "Go straight" after a turn.
        val key = Triple(navigator.stretch, g.maneuverKey, g.instruction.kind)
        if (g.maneuverDistance < BUZZ_DISTANCE && !g.offRoute &&
            (g.maneuverKey != buzzedFor || navigator.stretch != buzzedStretch)
        ) {
            buzz++
            buzzedFor = g.maneuverKey
            buzzedStretch = navigator.stretch
            say(g.instruction.spoken())
            spokenFor = key
        } else if (key != spokenFor) {
            say((if (spokenFor == null) "Route found. " else "") + g.instruction.spoken())
            spokenFor = key
        }

        val stretch = navigator.stretches[navigator.stretch]
        val path = stretch.path
        val from = g.progress - BEHIND
        val to = g.progress + LOOKAHEAD
        val endsHere = path.length <= to + 0.5f
        val turn = navigator.maneuversAhead(from + BEHIND, to).filterIsInstance<Maneuver.Turn>().firstOrNull()
        val marker = if (endsHere) {
            path.pointAt(path.length) to (if (navigator.isLastStretch) GuideRenderer.Marker.Destination else GuideRenderer.Marker.Elevator)
        } else {
            null
        }
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        stretchPose(anchor).toMatrix(model, 0)
        val seconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
        guideRenderer.draw(
            path.slice(from, to), if (endsHere) to + 10f else to, turn, marker,
            model, view, projection, seconds, depth.takeIf { useDepth },
        )

        if (navigator.atStretchEnd(g)) {
            if (navigator.isLastStretch) {
                stage = FollowStage.Arrived
                debug?.event("arrived", "remaining_m" to g.remaining)
                say("You've arrived")
            } else {
                navigator.skip()
                searchNext()
            }
        }
        return g
    }

    /** After reaching an elevator: wait for the photo that starts the next stretch. */
    private fun searchNext(inElevator: Boolean = false) {
        debug?.event("search", "stretch" to navigator.stretch, "in_elevator" to inElevator)
        searchStartedAt = SystemClock.elapsedRealtime()
        seen = ImageSeen.None
        stage = FollowStage.Searching(navigator.stretch)
        dropAnchor()
        riddenRise = 0f
        rideCheck = null
        val floor = elevatorEntry()?.floor.orEmpty()
        val to = if (floor.isNotBlank()) " to floor $floor" else ""
        say(
            if (inElevator) "Riding$to. I'll tell you when you're there"
            else "Take the elevator$to. When you step out, point your camera at the sign in the photo",
        )
    }

    /** The elevator ride that leads to the stretch being searched for, if that's how it starts. */
    private fun elevatorEntry(): LegEntry.Elevator? =
        ((stage as? FollowStage.Searching)?.stretch?.let { navigator.stretches[it].entry }) as? LegEntry.Elevator

    private fun onElevator(e: ElevatorDetector.Event) {
        when (e) {
            is ElevatorDetector.Event.RideStarted -> {
                // Stepping into the elevator before reaching the end of the path still counts.
                val nextIsElevator = navigator.stretches.getOrNull(navigator.stretch + 1)?.entry is LegEntry.Elevator
                if (stage == FollowStage.Guiding && nextIsElevator) {
                    navigator.skip()
                    searchNext(inElevator = true)
                }
                riding = elevatorEntry() != null
                debug?.event("ride_start", "up" to e.up, "counted" to riding)
            }
            is ElevatorDetector.Event.RideEnded -> {
                if (!riding) return
                riding = false
                riddenRise += e.rise
                val entry = elevatorEntry() ?: return
                val check = checkRide(entry.rise, riddenRise)
                rideCheck = check
                debug?.event("ride_end", "rise_m" to e.rise, "total_m" to riddenRise, "expected_m" to entry.rise, "check" to check.toString())
                say(rideMessage(check, entry.floor))
            }
            is ElevatorDetector.Event.RideCancelled -> {
                debug?.event("ride_cancel", "why" to e.why)
                riding = false
            }
        }
    }

    private fun say(text: String) {
        debug?.event("say", "text" to text)
        speech = text
        speechId++
    }

    /** The turn needed to face the path, or null when the path is roughly ahead. */
    private fun arrowTo(camera: Camera, stretch: Pose, g: Guidance): Float? {
        val target = stretch.transformPoint(floatArrayOf(g.target.x, g.target.y, g.target.z))
        val cam = camera.pose
        val z = cam.zAxis
        var fx = -z[0]
        var fz = -z[2]
        if (hypot(fx, fz) < 0.3f) {
            // Phone pointing at the floor: use the top of the screen as "ahead".
            val up = camera.displayOrientedPose.yAxis
            fx = up[0]
            fz = up[2]
        }
        val dx = target[0] - cam.tx()
        val dz = target[2] - cam.tz()
        if (hypot(dx, dz) < 0.5f) return null
        val bearing = signedAngle(fx, fz, dx, dz)
        return bearing.takeIf { abs(it) > ARROW_ANGLE }
    }

    private fun trace(g: Guidance?, arrow: Float?) {
        val d = debug ?: return
        d.show("stage", stage.javaClass.simpleName.lowercase())
        d.show("stretch", "${navigator.stretch + 1}/${navigator.stretches.size}")
        d.show("depth", if (!useDepth) "off" else if (depth.ready) "on" else "waiting")
        d.show("anchor", anchor?.trackingState?.name?.lowercase())
        if (g == null) return
        d.show("progress", g.progress)
        d.show("offset", g.offset)
        d.show("remaining", g.remaining)
        d.show("arrow", arrow?.let { Math.toDegrees(it.toDouble()).toInt() })
        if (g.offRoute != wasOffRoute) {
            d.event(if (g.offRoute) "off_route" else "back_on_route", "offset_m" to g.offset, "progress" to g.progress)
            wasOffRoute = g.offRoute
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSample >= SAMPLE_MS) {
            lastSample = now
            d.event(
                "sample", "stretch" to navigator.stretch, "progress" to g.progress, "offset" to g.offset, "above" to g.above,
                "remaining" to g.remaining, "next" to g.instruction.title, "in_m" to g.maneuverDistance,
            )
        }
    }

    private fun publish(hint: String?, g: Guidance?, arrow: Float?) {
        trace(g, arrow)
        val stage = stage
        val previous = _state.value
        val next = FollowUi(
            stage = stage,
            hint = hint,
            instruction = g?.instruction ?: previous.instruction.takeIf { stage == FollowStage.Guiding },
            remaining = g?.remaining ?: previous.remaining,
            floor = g?.floor ?: previous.floor,
            offRoute = g?.offRoute ?: false,
            arrow = arrow,
            buzz = buzz,
            canSkip = stage == FollowStage.Guiding && !navigator.isLastStretch &&
                g != null && navigator.stretches[navigator.stretch].path.length - g.progress < SKIP_WITHIN,
            speech = speech,
            speechId = speechId,
            ride = elevatorEntry()?.takeIf { riding || rideCheck != null }?.let {
                RideStatus(riddenRise + if (riding) elevator.rise else 0f, it.rise, it.floor, riding, rideCheck)
            },
            status = lockStatus(seen, SystemClock.elapsedRealtime() - searchStartedAt).takeIf { stage is FollowStage.Searching },
        )
        if (next != previous) _state.value = next
    }

    private companion object {
        const val LOOKAHEAD = 7f
        const val BEHIND = 0.6f
        const val BUZZ_DISTANCE = 3f
        const val SKIP_WITHIN = 10f
        const val SAMPLE_MS = 1000L
        const val OUTLINE_MS = 1500L
        const val SNAP_EVERY_FRAMES = 15
        /** Views of the photo are averaged while the follower is this close to where they found it. */
        const val SETTLE_METERS = 1.5f
        const val SETTLE_VIEWS = 30
        val ARROW_ANGLE = Math.toRadians(35.0).toFloat()
    }
}

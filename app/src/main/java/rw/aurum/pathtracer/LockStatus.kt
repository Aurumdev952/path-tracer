package rw.aurum.pathtracer

/** What ARCore makes of a sign photo right now. */
enum class ImageSeen {
    None,
    /** Detected, but ARCore has no pose for it yet. */
    Spotted,
    Tracking,
}

/** One plain-words line about the sign, and whether it's good news. */
data class SignStatus(val text: String, val good: Boolean)

/**
 * Before the photo: is what's in the box good to photograph? Null until the first checks are in.
 * [measurable] is whether the sign's real size can be measured from here, which every photo
 * needs before ARCore can lock on to it; [span] is how much of the wall the box covers, in meters.
 */
fun captureStatus(detail: SignDetail.Level?, dark: Boolean, measurable: Boolean?, span: Float? = null): SignStatus? = when {
    dark -> SignStatus("Too dark. Turn on a light or pick a brighter sign", false)
    measurable == false -> SignStatus("Can't tell how far the sign is. Step closer and move the phone a little", false)
    // ARCore only finds a photo that fills about a quarter of the camera view, so a photo of a
    // few centimeters of wall can only be found from right in front of it.
    span != null && span < MIN_SPAN_M -> SignStatus("Step back a little. A bigger view is easier to find later", false)
    detail == SignDetail.Level.Weak -> SignStatus("Too plain. Pick a sign with more text or pictures", false)
    measurable == true && detail != null -> SignStatus("Ready. Tap the button", true)
    else -> null
}

/** After the photo while recording, or while the follower looks for it, [waitedMs] so far. */
fun lockStatus(seen: ImageSeen, waitedMs: Long): SignStatus = when (seen) {
    ImageSeen.Tracking -> SignStatus("Got it. Hold steady", true)
    ImageSeen.Spotted -> SignStatus("Spotted it. Move slowly from side to side", true)
    ImageSeen.None -> if (waitedMs < NOT_FOUND_MS) {
        SignStatus("Looking for the sign…", false)
    } else {
        SignStatus("Not found yet. Get the whole sign in view, 1 to 2 m away", false)
    }
}

private const val NOT_FOUND_MS = 4000L
private const val MIN_SPAN_M = 0.3f

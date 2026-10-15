# Path Tracer

**See where you've walked.** Path Tracer is a native Android augmented-reality app that draws a glowing trail on the floor behind you as you move through a room. Turn around and your path is there. Leave, come back later, and the same path reappears in the same place.

<p align="center">
  <img src="docs/walking.jpg" width="300" alt="Walking: a magenta trail and a green start beacon drawn on the floor">
  &nbsp;&nbsp;
  <img src="docs/restored.jpg" width="300" alt="After reopening the app: the saved trail reappears on the floor">
</p>
<p align="center"><sub>Left: tracing a path from the start beacon. Right: the same trail restored after the app was closed and reopened.</sub></p>

## Features

- **Live floor trail.** An animated ribbon follows you across the floor. Chevrons flow from the start toward where you are now, and the colour shifts from cyan (oldest) to magenta (newest).
- **Start beacon.** A pulsing marker shows where the walk began.
- **Room memory.** The trail is saved on the phone and anchored to the room, so it comes back on your next visit. You can keep walking to extend it.
- **Two ways to recognise the room.** Use a printed marker (offline, no account) or Google Cloud Anchors (no marker, needs internet).
- **Distance walked**, plus pause, resume and start-over controls.

## How it works

ARCore tracks the phone's position from the camera and motion sensors, and detects the floor. While you walk, the app projects your position onto the floor about every 12 cm and stores each point relative to a **start anchor**. That anchor is what makes the trail persistent. If the app can find the anchor again later, every stored point lands back in the same physical spot.

The app finds the start anchor again in one of two ways:

| Mode | How it recognises the room | Needs |
| --- | --- | --- |
| **Marker · offline** (default) | [ARCore Augmented Images](https://developers.google.com/ar/develop/augmented-images) recognises the `START HERE` picture | A printed or on-screen copy of [`start_marker_print_me.png`](start_marker_print_me.png). No account or internet. |
| **Cloud** | [ARCore Cloud Anchors](https://developers.google.com/ar/develop/cloud-anchors) match what the camera sees against a saved visual map of the start spot (kept for up to 365 days) | Internet and a one-time Google Cloud setup ([below](#cloud-mode-setup)) |

The marker can lie on the floor, hang on a wall, or be shown on a monitor. The app keeps only the marker's heading, so the trail stays level either way.

## Getting started

### Requirements

- An [ARCore-supported Android phone](https://developers.google.com/ar/devices) running Android 7.0 or later. The app prompts you to install Google Play Services for AR if it's missing. Emulators can't run it in any useful way.
- JDK 17 or newer and the Android SDK (API 36), or Android Studio.

### Build and install

```bash
git clone https://github.com/Aurumdev952/path-tracer.git
cd path-tracer
./gradlew installDebug
```

Or open the folder in Android Studio and press **Run**.

### Use it

1. Open **Path Tracer** and allow camera access.
2. Choose a mode at the top of the screen.
   - **Marker:** place the `START HERE` marker where you begin (print it on A4, or show it full-screen on a monitor) and point the camera at it from about 0.5 to 1 m away.
   - **Cloud:** point the camera at the floor where you're standing.
3. When the status reads **Tracing**, walk. Turn around to see your path.
4. Close the app whenever you like. Next time, point at the marker (or look around the start spot in Cloud mode) and the trail comes back.

**Pause** stops recording, and **New** discards the trail and starts over.

## Cloud mode setup

Cloud mode uses ARCore's [keyless authorization](https://developers.google.com/ar/develop/authorization), so no API key is stored in the app.

1. In the [Google Cloud console](https://console.cloud.google.com/), create a project and enable the **ARCore API**.
2. Under **APIs & Services → Credentials**, create an **OAuth client ID** of type **Android**.
   - Package name: `rw.aurum.pathtracer`
   - SHA-1: your signing certificate fingerprint. For debug builds, get it with:

     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
     ```

3. Configure the OAuth consent screen if the console asks.

Until this is set up, Cloud mode still traces live, but the status shows **not saved** and the trail won't come back later.

## Project structure

```
app/src/main/java/rw/aurum/pathtracer/
├── MainActivity.kt        Permissions, ARCore session setup, Compose overlay UI
├── PathTracer.kt          Frame loop: finding the start, recording, cloud host/resolve, UI state
├── Trail.kt               Data model (points in anchor space, memory mode) and JSON storage
├── TrailRenderer.kt       OpenGL ES ribbon and start-beacon shaders
└── BackgroundRenderer.kt  Camera image rendering
app/src/main/assets/start_marker.png   The marker image ARCore looks for
```

All AR state lives on the GL render thread. The UI and ARCore callbacks send events to that thread through a queue, and the thread publishes a single `UiState` back to Compose. The room-recognition lifecycle is one `Room` state machine: finding start → (anchoring →) remembered, or relocalizing → remembered / not found.

## Built with

- [ARCore](https://developers.google.com/ar) 1.56: motion tracking, plane detection, Augmented Images, Cloud Anchors
- Kotlin and [Jetpack Compose](https://developer.android.com/compose) (Material 3)
- OpenGL ES 2.0 with custom shaders

## Limitations

- The trail is drawn on top of everything, so walls and furniture don't hide it yet.
- The phone keeps one trail at a time.
- It assumes one floor level, taken from the lowest detected floor plane.
- Accuracy depends on ARCore tracking. Fast motion, low light and blank walls cause drift.

## Ideas for next steps

- Hide the trail behind real objects with the ARCore Depth API.
- Keep several named trails per room.
- Recognise a room offline with no marker, using an open-source mapping library such as [RTAB-Map](https://introlab.github.io/rtabmap/).

## Troubleshooting

**The Gradle build hangs and never finishes.** On some Linux setups the JVM's process-reaper thread overflows its stack, so Gradle never sees child processes such as `jlink` exit. `gradle.properties` already passes `-Djdk.lang.processReaperUseDefaultStackSize=true` to the Gradle daemon. If the launcher itself still hangs, also run:

```bash
export JAVA_OPTS="-Djdk.lang.processReaperUseDefaultStackSize=true"
```

**The marker isn't recognised.** Make it bigger in the camera view, cut glare and reflections, and keep the camera still for a second. A matte print works best.

## License

[MIT](LICENSE)

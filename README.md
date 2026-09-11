# FoldWall

An Android live wallpaper that reacts to the **hinge angle** of a foldable: as you open
and close the device the wallpaper blurs, bends, darkens and tints in real time, driven
by an AGSL shader.

Inspired by the unfolding animation on Apple's foldable iPhone. This is *not* that
animation — see [What this can and cannot do](#what-this-can-and-cannot-do).

Built for the Galaxy Z Fold 8. Verified so far on the Android Studio
`7.6" Fold-in with outer display` emulator (API 35): all five effects, the live
hinge sensor, the settings-to-wallpaper live update and the diagnostic overlay.
Real-device results will be added here once they exist.

> The app UI is in Italian.

![FoldWall folded, crease effect](docs/screenshot-folded.png)

![Settings screen with live preview](docs/screenshot-settings.png)

---

## What it does

`Sensor.TYPE_HINGE_ANGLE` (public Android API since API 30) drives a `RuntimeShader`
that is applied to the wallpaper through a `RenderEffect` chain. Five distortion styles,
all fully adjustable:

| Effect | What it looks like |
| --- | --- |
| **Piega** (crease) | Shadow valley along the hinge with a lit edge either side. Closest to the original. |
| **Vetro** (glass) | Lens refraction along the crease, like frosted glass bending. |
| **Pagina** (page) | The right half curls onto a cylinder like a turning page, with a spine shadow. |
| **Profondità** (depth) | No warp: zoom, vignette and blur only. The restrained one. |
| **Onda** (ripple) | A wave leaving the crease and damping towards the edges. |

Adjustable per effect: intensity, maximum blur, darkening, desaturation, chromatic
aberration, crease width, response speed, tint colour and amount, crease glow colour and
amount, and an invert switch that moves the effect to the open end of the travel.

Five presets (Originale, Soffice, Cinema, Libro, Neon) set a whole look at once.

Pick any photo as the wallpaper image, or leave it empty and use the built-in gradient
with two configurable colours.

## Why the calibration section exists

The range of hinge angles a **live wallpaper** actually observes is not the full 0–180°.
The engine only receives sensor events while its own panel is lit, and the moment the
firmware hands the panel over is owned by SystemUI, not by the app. So the app does not
guess: turn on **Diagnostica sullo sfondo**, fold the device, read the real degrees off
the on-screen overlay, then set the min/max angles to match.

The overlay reports sensor presence, raw angle, event count, computed openness, the
configured range, the active effect and the surface size.

## What this can and cannot do

**It can**: react to the hinge behind the real launcher, continuously, as a normal
wallpaper — no root, no ADB tricks, no special permissions.

**It cannot**: reproduce the system-level unfold transition. A third-party app cannot
touch SystemUI's display handover, and there is a brief black/snapshot gap during the
physical panel swap that belongs to the system. The effect is therefore two halves
stitched around a gap the app does not control. Only the OEM (or a rooted SystemUI hook)
can do the authentic version.

Cover-screen support is not implemented in v1.

## Build

Requirements: JDK 17, Android SDK with platform 36.

```bash
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. `minSdk` is 33, because
`RuntimeShader` (AGSL) became public API in Android 13.

### Release build

`app/build.gradle.kts` reads signing credentials from a `keystore.properties` file in
the repository root, which is **not** in version control. Without it the release build
still runs and produces an unsigned APK.

```properties
storeFile=../foldwall-release.jks
storePassword=...
keyAlias=foldwall
keyPassword=...
```

Create your own keystore with `keytool -genkeypair -v -keystore foldwall-release.jks
-keyalg RSA -keysize 4096 -validity 10000 -alias foldwall`, then `./gradlew
assembleRelease`.

## Install

A built APK is available at
<https://step.prandi.net/portal/downloads/FoldWall.apk>, or build one yourself:

```bash
adb install -r app-debug.apk
```

Then open the app and press **Imposta come sfondo**, or pick FoldWall from
Settings → Wallpaper → Live wallpapers.

## How it is put together

| File | Role |
| --- | --- |
| `FoldRenderer.kt` | `HardwareRenderer` + two `RenderNode`s. The image is recorded once; only the `RenderEffect` is rebuilt per frame. |
| `FoldShaders.kt` | Shared AGSL prelude/epilogue: chromatic aberration, crease glow, colour grading. |
| `FoldEffect.kt` | One `warp()` / `shade()` pair per effect, spliced into the shared scaffolding. |
| `HingeSource.kt` | `SensorManager` wrapper over `TYPE_HINGE_ANGLE`. |
| `FoldWallpaperService.kt` | The wallpaper engine. Draws only while visible *and* while the angle is still moving. |
| `FoldPreviewView.kt` | The same renderer on a `SurfaceView`, so the settings screen previews the real thing. |

Two details worth knowing if you fork this:

- A `RenderEffect` captures its shader's uniforms at construction, so it must be rebuilt
  every frame. Calling `setFloatUniform` on the shader afterwards changes nothing.
- The blur uses `RenderEffect.createBlurEffect(..., Shader.TileMode.CLAMP)`. Without
  CLAMP the edges of the wallpaper fade to transparent.

## Licence

MIT — see [LICENSE](LICENSE).

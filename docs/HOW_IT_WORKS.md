# How the Atmosphere effect works

Nothing OS ships Atmosphere as a native OpenGL ES 3 engine inside its wallpaper picker. This app
reimplements that engine in Kotlin and GLSL and matches its behaviour constant for constant. This page
explains what happens between your fingerprint unlocking the phone and the home screen settling into
soft colour.

## The short version

1. Six colours are pulled out of your photo.
2. When you unlock, the photo dissolves into the most dominant of them.
3. Five soft shapes, one per remaining colour, grow out of the spots in the photo where those colours
   live, drift to new positions and settle.
4. Everything is blurred together on a tiny canvas and stretched back to full screen, which turns flat
   polygons into smooth gradients. A layer of film grain goes on top.

The morph runs for 187 frames at a fixed 16 ms per frame (about 3 seconds) whatever the refresh rate of
the panel, then holds the final frame without redrawing.

## 1. Colour extraction

When you pick a photo it is centre-cropped to your screen's shape at screen resolution and stored as a
lossless PNG. The colours are then extracted from that stored file:

- the image is decoded at 1/16 scale (`inSampleSize = 16`);
- AndroidX Palette runs with 6 colours, no filters and no target profiles, so dark dominant colours are
  allowed to win;
- swatches are sorted by population;
- for each swatch, the closest real pixel in CIE-LAB space is found. That pixel's colour and position
  become a *seed*.

Seed 0 is the background every part of the photo fades toward. Seeds 1 to 5 become the five shapes, and
each shape starts on its own seed pixel. That is why a sunset's glow blooms out of the sun and a beach's
pale shape rises out of the sand.

If the photo has fewer than six distinct colours, the missing seeds are padded from the dominant swatch
by stepping its saturation, half downward and half upward.

## 2. The frame pipeline

Every frame of the morph is drawn in five passes:

| Pass | What it does |
|---|---|
| Composite | The photo, mixed toward the background colour by `uMixBlend` (0 to 1 over frames 10 to 135, eased) |
| Shapes | Five filled Bezier outlines, alpha 0 to 1 over frames 10 to 65 (eased), blended on top |
| Horizontal blur | Separable Gaussian, sigma = radius / 3, radius up to 30 taps, 14 px per tap at full strength |
| Vertical blur | Same kernel on the other axis |
| Grain | Hash noise mixed in linearly from 0 to 14% over the first 80 frames |

Two details matter more than they look:

- **The canvas is 72 pixels wide.** From frame 10 on, all of this is drawn into a 72 x (72 x H / W)
  texture, then stretched to the full panel with bilinear filtering. At that size a radius of 30 is
  roughly 14% of the screen width, and the magnification itself does a large part of the smoothing.
  Before frame 10 the canvas is half resolution, so the photo first visibly softens before it melts.
- **Blending is never switched off.** Both blur passes and the grain are drawn with
  `SRC_ALPHA, ONE_MINUS_SRC_ALPHA` into textures that are not cleared between frames. While the shapes
  fade in their alpha is below 1, so a little of the previous frame survives each pass. The result is a
  faint trail on the shapes during the first second. It is easy to miss and very visible once removed.

Every eased value goes through the same curve, a cubic Bezier with control points (0.15, 0) and (0, 1):
fast out, long settle. It is evaluated from a 501-point lookup table sampled at t = 0.002 steps, exactly
as the original engine does, rather than through Android's `PathInterpolator`, which approximates the
same curve differently and drifts by up to 0.003.

## 3. The shapes

Each shape starts as the same small, slightly irregular pentagon. Per frame, between frames 10 and 100:

- every vertex moves outward from the centroid by a random step of 2, 4 or 6 px (times 30 on the first
  frame, which is what makes the shapes appear to *bloom*);
- the outline is redrawn as a closed Bezier through the vertices, with handle tension stepping up by
  0.044 per frame to a cap of 0.7, so edges start straight and soften;
- the number of segments per span drops from 16 to 3 as the shape grows.

The shape travels from its seed pixel to a random target. Targets are chosen to be at least 500 px apart
(with up to 100 attempts), and a new layout is rolled on every lock, so no two unlocks look the same.
Travel uses one of three modes, picked per lock: a straight line, or a half-orbit clockwise or
counter-clockwise around the midpoint, rotating the outline as it goes.

## 4. Lock and unlock

On a Nothing phone, SystemUI tells the wallpaper the moment a fingerprint, face or PIN succeeds. The
first frames of the morph play while the lock screen is still fading away, so what you see is the photo
softening and the colour blooming through.

Other phones have no such signal, so this app watches for it:

- while the wallpaper is visible behind the lock screen, it polls `KeyguardManager.isKeyguardLocked`
  every 16 ms and starts the morph on the falling edge. On a Pixel this lands about 40 ms after the
  fingerprint match, roughly three frames later than on a Nothing phone;
- `ACTION_USER_PRESENT` and `COMMAND_KEYGUARD_GOING_AWAY` are used as backups;
- the lock-screen photo returns 500 ms after the screen turns off (scaled by the system animation
  speed), and is redrawn while the screen is still dark so a wake never flashes the previous frame;
- if the screen comes on while the phone is locked, the lock visual is shown immediately.

This is why applying the wallpaper to **both home and lock screen** matters: with the same wallpaper
behind the keyguard, the effect can start before the lock screen has gone.

## 5. Compatibility

- **Android 8.0 (API 26) and newer.** Newer APIs (image decoding, window metrics, thumbnails, wallpaper
  colours, receiver flags, media permissions) all have version-checked fallbacks. Lint runs in CI to keep
  it that way.
- **OpenGL ES 3.0** is required, which every phone that runs Android 8 in practice has.
- **Any resolution or aspect ratio.** All distances are defined on a 1080 px reference width and scaled;
  photos are cropped to the actual panel.
- No native code, so one APK runs on every CPU architecture.

## Source map

| File | Responsibility |
|---|---|
| `engine/AtmosphereRenderer.kt` | Frame clock, pipeline, lock state |
| `engine/ShapeCreator.kt`, `BlobOutline.kt`, `BlobPlacement.kt` | Shape growth, outlines, travel |
| `engine/SeedExtractor.kt` | Palette and seed pixels |
| `engine/NothingEase.kt` | The 501-point easing table |
| `engine/LockPresence.kt` | Lock and unlock decisions, as a pure state machine |
| `AtmosphereWallpaperService.kt` | Wallpaper service, unlock detection, system colours |
| `assets/shaders/atmosphere/` | GLSL for composite, shapes, blur and grain |

### Rendering frames off-screen

The app includes a small developer hook that renders the morph off-screen at panel resolution and saves
the frames as PNG. It is guarded by the `DUMP` permission, so only `adb` can trigger it:

```sh
adb shell am broadcast -n io.github.shashtwat.atmosphere/.engine.MorphDump \
    --es frames 0,10,20,40,80,186 --ei width 540
adb pull /sdcard/Android/data/io.github.shashtwat.atmosphere/files/morph
```

The demo GIF in the README was made this way.

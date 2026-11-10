<div align="center">

<img src="docs/media/icon.png" width="96" alt="Nothing Atmosphere icon" />

# Nothing Atmosphere

The Nothing OS Atmosphere live wallpaper for Android phones.

[Download APK](https://github.com/Shashtwat/nothing-atmosphere-wallpaper/releases/latest/download/NothingAtmosphere.apk) · Android 8.0+ · 1 MB

<img src="docs/media/unlock.gif" width="280" alt="A sunset photo blurring into soft colour as the phone unlocks" />

</div>

## What it does

Your photo is shown on the lock screen. When you unlock, its colours spread out of the image and blur
into a soft, grainy gradient on the home screen. This is the Atmosphere effect from Nothing OS (Nothing
Phone (3)), made to run on Pixel, Samsung, OnePlus and other Android phones.

The rendering follows Nothing's own wallpaper engine: the same colour extraction, blur, grain, timing
and shape movement. See [How it works](docs/HOW_IT_WORKS.md) for details.

<img src="docs/media/screenshots.png" alt="Lock screen, home screen and the app" />

## Install

1. Download [NothingAtmosphere.apk](https://github.com/Shashtwat/nothing-atmosphere-wallpaper/releases/latest/download/NothingAtmosphere.apk) and open it.
   Allow installing unknown apps if Android asks.
2. Open **Nothing Atmosphere**, choose a photo and tap **Apply**.
3. Select **Home screen and lock screen**.

Applying it to both screens is what lets the effect start as soon as you unlock.

## Features

- Pick a single image, or browse a folder of wallpapers (default: `Pictures/Wallpapers`).
- Preview the unlock before applying.
- A new shape layout on every unlock.
- Wallpaper colours are shared with Android for Material You theming.
- Draws only during the 3-second animation, then stays still.
- No internet access, ads or tracking.

## Compatibility

| | |
|---|---|
| Android | 8.0 and newer |
| Tested | Android 8.0 and 11 (emulator), Android 17 (Pixel 10) |
| Graphics | OpenGL ES 3.0 |
| Root | Not required |

Nothing phones tell the wallpaper the moment the phone unlocks. Other phones don't, so the app watches
the lock state instead. On a Pixel the effect starts about 40 ms after unlock.

## FAQ

**Is this an official Nothing app?**
No. It is an independent open-source project.

**Why does a system "Set wallpaper" screen appear?**
Android requires it for every live wallpaper.

**Does it affect battery life?**
Only marginally. It renders for about three seconds after each unlock.

## Build

Requires JDK 17 and the Android SDK.

```sh
git clone https://github.com/Shashtwat/nothing-atmosphere-wallpaper.git
cd nothing-atmosphere-wallpaper
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. Without a signing configuration it is signed
with your debug key; set `ATMOSPHERE_SIGNING` to a properties file (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`) to use your own.

## Credits

Based on [Nothing Wallpaper Changer](https://github.com/NineCSdev/nothing-wallpaper-changer) by NineCSdev
(MIT), reworked to match Nothing OS. NDot and NType typefaces © Nothing Technology Limited. Full notices
in [licenses/](licenses/).

Not affiliated with Nothing Technology Limited. Demo wallpapers are original artwork.

## License

[MIT](LICENSE)

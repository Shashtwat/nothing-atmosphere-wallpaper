# Changelog

All notable changes to this project are listed here. Versions follow [Semantic Versioning](https://semver.org).

## [1.0.0] - 2026-11-10

First public release.

### Added
- Atmosphere live wallpaper: sharp photo on the lock screen, unlock morph into blurred colour on the home
  screen, about 3 seconds at a fixed 16 ms per frame.
- Engine matched to Nothing OS 5 behaviour: six-seed palette with LAB nearest-pixel seeds, five blooming
  Bezier shapes that start on their seed pixels, separable Gaussian blur on a 72 px canvas, persistent
  blending between frames, linear film grain, and a 501-point easing table.
- Unlock detection for phones without Nothing's signal (16 ms keyguard poll, with
  `ACTION_USER_PRESENT` and `COMMAND_KEYGUARD_GOING_AWAY` as fallbacks).
- Wallpaper picker: grid of any folder (defaults to `Pictures/Wallpapers`), single image from storage,
  looping preview, apply.
- System wallpaper colours for Material You.
- Android 8.0+ support with version-checked fallbacks for newer APIs.

[1.0.0]: https://github.com/Shashtwat/nothing-atmosphere-wallpaper/releases/tag/v1.0.0

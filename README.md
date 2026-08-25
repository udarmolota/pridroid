# PriDroid

**PriDroid** runs the native Linux build of
[Prison Architect](https://www.gog.com/game/prison_architect) on Android phones, through
[box64](https://github.com/ptitSeb/box64), with GPU rendering and touch controls.

> [!NOTE]
> Not developed by or affiliated with Paradox Interactive or Introversion Software.
> You must own the game — PriDroid contains no game files and distributes none.
> The **GOG (DRM-free) Linux build** is the supported source; the Steam build refuses to start
> without a running Steam client.

> [!WARNING]
> **Very early.** The game boots and loads on the development device; this is not a product yet.

## How it works

The game is a 17 MB x86_64 ELF, run under a box64 fork
([pridroid-box64](https://github.com/udarmolota/pridroid-box64)) against an in-app X server and a
Mesa/Zink → Vulkan renderer, with audio bridged from ALSA to AAudio.

Forked from [RimDroid](https://github.com/udarmolota/rimdroid), which does the same for RimWorld.
Most of the runtime is shared; RimWorld-specific parts are still being removed.

## Building

Android Studio with JDK 21, NDK 27.0.12077973, CMake 3.22.1. Clone with submodules:

```
git clone --recurse-submodules https://github.com/udarmolota/pridroid.git
```

## License

GPL-3.0 — see [LICENSE](LICENSE), and [NOTICE](NOTICE) for the bundled third-party components.

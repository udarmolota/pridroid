# PriDroid

**PriDroid** is an unofficial, community-developed compatibility launcher that runs the native
Linux x86_64 build of [Prison Architect](https://www.gog.com/game/prison_architect) on ARM64
Android devices, with GPU rendering, touch controls, DLC and Workshop mods.

> [!NOTE]
> PriDroid ships no game files and distributes none — you bring your own copy: the
> **GOG (DRM-free) Linux build**. You can sign in to GOG from inside the app and download the
> installer straight to your phone, or copy one over yourself.
>
> The **Steam build is not supported**: it needs a Steam ownership-verification layer we have not
> built yet. It is on the roadmap.

> [!WARNING]
> **This is a pre-release — earlier than beta.** It runs well on the devices we have; yours may
> surprise us. Bug reports from the in-app menu are genuinely useful and genuinely read.

Not developed by or affiliated with Paradox Interactive or Introversion Software.

## Features

- ✔️ Runs the **native Linux x86_64 build** of Prison Architect on ARM64 phones and tablets
- ✔️ **Boots to the main menu in seconds** — the game's data archives are converted once at install,
  instead of being unpacked again on every launch
- ✔️ **Interface scale** — Prison Architect has a UI-size setting the game itself never exposes;
  PriDroid does. Pair it with the render resolution for text that is both bigger *and* sharper
- ✔️ **Two renderers**, switchable per instance — **Zink** (OpenGL → Vulkan) and
  [**NG-GL4ES**](https://github.com/udarmolota/NG-GL4ES/tree/zomdroid-base-june16)
  (OpenGL → OpenGL ES, no Vulkan at all)
- ✔️ **GOG DLC** — install your own alongside the game; their modes appear in the new-prison settings
- ✔️ **Free Steam Workshop mods**, downloaded by the launcher itself
- ✔️ **Editable on-screen touch controls** — move / resize / opacity, buttons bound to any key or
  mouse action, plus a gamepad button-mapping screen
- ✔️ **Multiple instances** — each install is a card with its own settings
- ✔️ **Render resolution, FPS counter and frame cap**
- ✔️ **Save / settings / control-layout import & export**, and one-tap bug reports
- ✔️ Haptics, night mode, and Russian, Spanish and Portuguese translations

## Project status & what to expect

PriDroid is very young, built by one person, and forked from
[RimDroid](https://github.com/udarmolota/rimdroid) — which does the same thing for RimWorld. Much of
the runtime is shared; the Prison Architect-specific parts are what this project is.

A few honest notes so expectations land right:

- **Emulation is CPU-bound.** A small young prison runs on far more modest hardware than a large busy
  one. The bigger the prison grows, the more even a strong phone will feel it.
- **Renderer support is uneven, and we know it.** Zink is proven on Adreno GPUs. NG-GL4ES exists
  precisely because Zink needs a healthy Vulkan driver and many phones do not have one — but it has
  had far less field testing. If one renderer fails on your device, try the other.
- **The device list is short.** Development happens on two Snapdragon phones. Everything we know
  about other hardware comes from bug reports, which is exactly why they matter so much.

We would rather set expectations honestly than overpromise.

## System requirements

- **Android 11 or newer**, ARM64
- **6 GB RAM or more** (a guideline, not a measured floor)
- Room for the game plus its converted archives — budget roughly **twice the installer size**
- **Your own copy of the game**: the GOG DRM-free Linux build

## If it does not start

Most first-run problems fall into two buckets, and both have a switch:

1. **Black screen, or a crash before you ever see a frame** — try the other renderer
   (Settings → Video → Renderer). Zink needs a working Vulkan driver; NG-GL4ES does not use Vulkan.
2. **Still nothing** — turn on **Compatibility mode** (Settings → Advanced). It changes how the
   emulator translates the game's code. A little slower, so leave it off once things work.

If neither helps, send a report from the in-app menu. It carries the launcher and emulator logs, the
game's own log and the system's process-exit history — enough to find most causes without owning the
device.

## How it works

Prison Architect ships only for x86_64. PriDroid runs the **native Linux build** directly on ARM64:
a [box64](https://github.com/ptitSeb/box64) fork
([pridroid-box64](https://github.com/udarmolota/pridroid-box64)) emulates the x86_64 engine
in-process, the game talks to an in-app X server, graphics go through your phone's real GPU, and
Android touch input is injected straight into the game. Audio is bridged from ALSA to AAudio.

The game is old-school OpenGL — fixed-function calls, display lists, compatibility-profile shaders —
which is why the renderer choice matters more here than it would for a modern engine.

## Supporting development

This is an independent project. To help keep it going, contributions are welcome via
[Ko-Fi](https://ko-fi.com/udarmolota).

## Feedback

Please report issues or request features via
[GitHub Issues](https://github.com/udarmolota/pridroid/issues). There is a one-tap **Report a Bug**
in the in-app menu — attach the zip so we can see what your device is doing.

Running a large prison? **Tell us how it holds up.** Long-session reports are exactly the data we are
missing, and they are how the device notes here will get more precise.

## License

GPL-3.0 — see [LICENSE](LICENSE), and [NOTICE](NOTICE) for the bundled third-party components.

## Credits & Third-Party Sources

- [box64](https://github.com/ptitSeb/box64) — x86_64→ARM64 emulation backend
- [Mesa / Zink](https://gitlab.freedesktop.org/mesa/mesa) — GPU rendering (OpenGL→Vulkan)
- [Turnip / libvulkan_freedreno](https://gitlab.freedesktop.org/mesa/mesa) — Adreno Vulkan driver
- [NG-GL4ES](https://github.com/udarmolota/NG-GL4ES/tree/zomdroid-base-june16) — the second renderer
  (OpenGL→OpenGL ES), descended from [gl4es](https://github.com/ptitSeb/gl4es)
- [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass) — Android linker namespace access
- [Winlator](https://github.com/brunodev85/winlator) — the in-app X server this one grew from
- [RimDroid](https://github.com/udarmolota/rimdroid) — the launcher this one is forked from
- [Zomdroid](https://github.com/udarmolota/zomdroid) — architecture reference and inspiration

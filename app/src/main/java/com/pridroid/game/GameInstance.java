package com.pridroid.game;

import com.pridroid.AppStorage;
import com.pridroid.C;

import java.io.File;
import java.util.ArrayList;

public class GameInstance {

    private final String name;

    public GameInstance(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    /** Per-instance launch settings (renderer, Vulkan driver, debug, interpreter). */
    public com.pridroid.InstanceSettings settings() {
        return new com.pridroid.InstanceSettings(name);
    }

    public String getGamePath() {
        return AppStorage.requireSingleton().getInstanceDir(name).getAbsolutePath();
    }

    /** Prison Architect's per-instance user folder ($HOME/.Prison Architect). */
    public File getUserDataDir() {
        return new File(getGamePath(), ".Prison Architect");
    }

    /**
     * x86_64 library search path for box64 (BOX64_LD_LIBRARY_PATH).
     * Contains ONLY x86_64 libraries — game libs and Linux system libs.
     * ARM64 renderer libs do NOT belong here.
     */
    public String getLdLibraryPathForEmulation() {
        AppStorage storage = AppStorage.requireSingleton();

        ArrayList<String> paths = new ArrayList<>();

        String gameDir = getGamePath();

        // Game root dir (top-level .so files, if any)
        paths.add(gameDir);

        // The game's own libs (libSDL2, libpops_api, libsndio). Its RPATH is $ORIGIN/lib64,
        // which box64 resolves relative to the binary, but listing the dir explicitly keeps
        // the search path independent of how box64 handles RPATH.
        paths.add(gameDir + "/" + C.files.GAME_LIB_DIR);

        // x86_64 system libs — libgcc_s.so.1, libGLU.so.1 etc.
        paths.add(storage.getLibsLinuxX86Path());

        return join(paths, ":");
    }

    /**
     * ARM64 native library path — passed to Android linker for loading
     * our ARM64 .so files (renderer, APK native libs).
     */
    public String getNativeLibraryPath() {
        AppStorage storage = AppStorage.requireSingleton();

        ArrayList<String> paths = new ArrayList<>();

        // APK native libs (libpridroid.so, libpridroidlinker.so etc.)
        paths.add(storage.getLibraryPath());
        paths.add("/system/lib64");

        // ARM64 renderer libs — per this instance's renderer choice
        switch (settings().getRenderer()) {
            case GL4ES:
            case MOBILEGLUES:   // same deps dir; the launcher maps it onto the GL4ES plumbing
                paths.add(storage.getGl4esLibsPath());
                break;
            case ZINK_ZFA:
            case ZINK_OSMESA:
                paths.add(storage.getZinkLibsPath());
                break;
            case SOFTPIPE:
                // libOSMesa.so (softpipe CPU renderer) lives in the deps dir alongside libzfa.so.
                // This dir MUST be in the search path or pridroid_ns can't resolve "libOSMesa.so"
                // by soname → pridroid_init_osmesa()'s namespace dlopen returns NULL.
                paths.add(storage.getGl4esLibsPath());
                paths.add(storage.getZinkLibsPath());   // same deps dir; harmless if duplicate
                break;
        }

        return join(paths, ":");
    }

    /** Args passed to the game binary. */
    public String[] getArgs() {
        // Prison Architect handles -safemode itself: oversized images are scaled before their GL
        // upload. Unlike the inherited RimWorld mip-drop setting, this affects PA's legacy
        // glTexImage2D path and adds no runtime ETC2 encoding cost.
        return settings().getTexTier() == com.pridroid.InstanceSettings.TEX_NONE
                ? new String[]{}
                : new String[]{"-safemode"};
    }

    public boolean isInstalled() {
        return new File(getGamePath(), C.files.GAME_BIN).exists();
    }

    /**
     * Completeness check, separate from {@link #isInstalled()}. isInstalled() stays lenient (just the
     * RimWorldLinux binary) so a working instance NEVER vanishes from the launcher over a layout quirk —
     * but a repack/tarball can ship without the base game content, and RimWorld then dies at startup in
     * ModLister ("Sequence contains no matching element" — no Core) long after we've said "Installed".
     * This returns the list of REQUIRED files that are missing (empty = complete). Data/Core/About/
     * About.xml is the real tell (the base "Core" module); DLC (Data/Biotech, Data/Odyssey…) are
     * optional and intentionally NOT checked. Used to warn at install and block launch, not to hide.
     */
    public java.util.List<String> missingCoreFiles() {
        File root = new File(getGamePath());
        String[] required = {
            C.files.GAME_BIN,
            "main.dat",                       // 164 MB — the base content pack
            "lib64/libSDL2-2.0.so.0",
        };
        java.util.List<String> missing = new ArrayList<>();
        for (String rel : required)
            if (!new File(root, rel).isFile()) missing.add(rel);
        // Existence alone is not enough: an interrupted download (the Steam downloader can die
        // mid-way on low-memory phones) leaves a truncated or empty RimWorldLinux that passes
        // isFile(). box64 then reports only "is not an executable file", which reads like an app
        // bug. Verify it really is an x86-64 ELF of plausible size, so the launcher can say
        // "incomplete, download again" instead.
        if (missing.isEmpty() && !isX86_64Elf(new File(root, C.files.GAME_BIN)))
            missing.add(C.files.GAME_BIN + " (incomplete or corrupted)");
        return missing;
    }

    /**
     * True if {@code f} is a complete x86-64 ELF executable: right magic/class/machine, and the
     * header and section tables it declares actually fit inside the file. Do NOT gate this on a
     * minimum size — RimWorldLinux is a ~14 KB launcher stub (the engine lives in UnityPlayer.so),
     * and an earlier size floor flagged healthy instances as incomplete.
     */
    private static boolean isX86_64Elf(File f) {
        final long len = f.length();
        if (!f.isFile() || len < 64) return false;             // smaller than an ELF64 header
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[64];
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) return false;
                n += r;
            }
            if (!(h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F')) return false;
            if (h[4] != 2 || h[5] != 1) return false;          // ELFCLASS64, little-endian
            if (le16(h, 18) != 0x3e) return false;             // e_machine = EM_X86_64
            // Truncation check: a half-downloaded file keeps a valid header but loses the tail the
            // header points at, which is exactly what box64 reports as "not an executable file".
            long phEnd = le64(h, 32) + (long) le16(h, 54) * le16(h, 56);   // e_phoff + e_phentsize*e_phnum
            long shEnd = le64(h, 40) + (long) le16(h, 58) * le16(h, 60);   // e_shoff + e_shentsize*e_shnum
            return phEnd <= len && shEnd <= len;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xffL);
        return v;
    }

    /** True if the base game content is present enough for RimWorld to load (see {@link #missingCoreFiles}). */
    public boolean isComplete() { return missingCoreFiles().isEmpty(); }

    private static String join(ArrayList<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}

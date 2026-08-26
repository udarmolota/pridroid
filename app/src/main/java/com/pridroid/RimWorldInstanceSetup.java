package com.pridroid;

import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Shared post-install setup for Prison Architect instances, regardless of installation source.
 *
 * <p>This replaces PriDroid's RimWorld setup wholesale. Everything that lived here before was
 * Unity/RimWorld surgery with no counterpart in a native C++ game — the UnityPlayer display-count
 * patch, libsteam_api normalization (the GOG build has no Steam layer at all), the
 * m_PreloadAudioData flip over ~2400 Unity clips, and the Version.txt 1.5/1.6 gate. All of it is
 * preserved in PriDroid, none of it must run here.
 *
 * <p>What remains: the two launch-path markers.
 * <ul>
 *   <li>{@code rd_x11} — the game's bundled SDL2 is X11-only for video, so the in-process X server
 *       path is not an option here, it is the only path.</li>
 *   <li>{@code rd_force_gles} — routes GLX onto the renderer bridge (ZFA pivot, or the EGL
 *       translator when MobileGlues is selected); GameLauncher branches on the renderer itself.</li>
 * </ul>
 * The class name is kept so callers and git history stay readable; the game it sets up is named by
 * {@link C.files#GAME_BIN}.
 */
public final class RimWorldInstanceSetup {
    private static final String TAG = "PriDroid/InstanceSetup";
    private static final String GAMEFIX_DIR = "gamefix";

    /** Post-install configuration; also safe to re-run on existing instances. */
    public static boolean configureDetected(File instanceDir) throws IOException {
        return configure(instanceDir, true);
    }

    public static boolean configure(File instanceDir, boolean ignoredVersionFlag) throws IOException {
        createMarker(instanceDir, "rd_x11");
        createMarker(instanceDir, "rd_force_gles");
        return true;
    }

    /** Best-effort pass over already-installed instances (markers are idempotent). */
    public static void reconcileExistingInstances(File instancesDir) {
        File[] dirs = instancesDir.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File dir : dirs) {
            try {
                configure(dir, true);
            } catch (Throwable t) {
                Log.w(TAG, "reconcileExistingInstances: skipped " + dir.getName() + ": " + t);
            }
        }
    }

    /** Copy bundled game-fix reference files (assets/gamefix/*) into files/gamefix/. The mechanism
     *  is kept from PriDroid (payload currently empty for Prison Architect); ".pack" entries are
     *  gzip-wrapped guest ELFs, stored compressed so AGP's 16 KB-alignment check cannot see them. */
    public static void ensureGameFixAssets(android.content.Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), GAMEFIX_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            android.content.res.AssetManager am = ctx.getAssets();
            String[] names = am.list(GAMEFIX_DIR);
            if (names == null) return;
            for (String name : names) {
                boolean gz = name.endsWith(".pack");
                String outName = gz ? name.substring(0, name.length() - 5) : name;
                File out = new File(dir, outName);
                try (java.io.InputStream raw = am.open(GAMEFIX_DIR + "/" + name)) {
                    if (!gz && out.isFile() && out.length() == raw.available()) continue;
                    java.io.InputStream in = gz ? new java.util.zip.GZIPInputStream(raw) : raw;
                    try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                    }
                    Log.i(TAG, "gamefix asset extracted: " + outName + " (" + out.length() + " bytes)");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureGameFixAssets failed (non-fatal): " + t);
        }
    }

    private static void createMarker(File instanceDir, String name) throws IOException {
        File marker = new File(instanceDir, name);
        if (!marker.exists() && !marker.createNewFile())
            throw new IOException("Cannot create " + marker);
    }

    private RimWorldInstanceSetup() {}
}

package com.rimdroid;

import android.app.Activity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.rimdroid.game.GameInstance;
import com.rimdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

/**
 * Installs a picked file (mod or DLC) into a chosen instance. One unified flow: the user picks the
 * file from anywhere on the phone (SAF — no storage permission needed, works for the /Download/PriDroid
 * zips too), then picks the target instance and Mod/DLC.
 *   • Mod zip → instance/.Prison Architect/mods/&lt;folder&gt;
 *   • GOG DLC installer → instance root (its product marker unlocks content already in the archives)
 * Normal mod zips reuse {@link ModImporter}, which finds the manifest.txt root and strips wrappers.
 *   • zipped GOG DLC installer (a {@code .sh} inside the zip) → extracted by
 *     {@link GogInstallerExtractor} into the instance root, since its payload is already
 *     game-relative (carries a ready-made {@code Data/<Expansion>}). It has no About/About.xml, so
 *     the ModImporter path can't handle it. (We only accept zips — see {@link C.mime#GAME_ARCHIVE}.)
 */
public final class ContentInstaller {
    private static final String TAG = "RimDroid/Content";

    private ContentInstaller() {}

    /** After the user has picked a zip (SAF), choose target instance + Mod/DLC, then install. */
    public static void showTargetDialog(Activity act, Uri zipUri) {
        final List<GameInstance> instances = GameInstanceManager.requireSingleton().getInstances();
        if (instances.isEmpty()) {
            Toast.makeText(act, "No instances yet — download or install the game first.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        float dp = act.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        box.addView(label(act, "File"));
        TextView fileTv = new TextView(act);
        fileTv.setText(displayName(act, zipUri));
        box.addView(fileTv);

        box.addView(label(act, "Install into instance"));
        final Spinner spInst = new Spinner(act);
        List<String> names = new ArrayList<>();
        for (GameInstance gi : instances) names.add(gi.getName());
        spInst.setAdapter(adapter(act, names));
        box.addView(spInst);

        box.addView(label(act, "Type"));
        final RadioGroup rg = new RadioGroup(act);
        final RadioButton rbMod = new RadioButton(act); rbMod.setText("Mod → .Prison Architect/mods/"); rbMod.setId(1);
        final RadioButton rbDlc = new RadioButton(act); rbDlc.setText("DLC → Data/"); rbDlc.setId(2);
        rg.addView(rbMod); rg.addView(rbDlc); rg.check(1);
        box.addView(rg);

        // A zipped GOG installer places its own content (Data/<Expansion>), so the choice above is
        // ignored for it — say so rather than let the picked radio look meaningful.
        TextView hint = new TextView(act);
        hint.setText("A zipped GOG DLC installer (.sh inside a .zip) is detected automatically "
                + "and installs itself into Data/.");
        hint.setPadding(0, (int) (8 * dp), 0, 0);
        box.addView(hint);

        new MaterialAlertDialogBuilder(act)
                .setTitle("Install mod / DLC")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install", (d, w) -> {
                    GameInstance inst = instances.get(spInst.getSelectedItemPosition());
                    boolean intoData = rg.getCheckedRadioButtonId() == 2;
                    install(act, zipUri, inst, intoData);
                })
                .show();
    }

    /** Copy the picked zip to cache, then import it into the instance's Mods or Data folder. */
    public static void install(Activity act, Uri zipUri, GameInstance instance, boolean intoData) {
        final Handler main = new Handler(Looper.getMainLooper());
        final File destDir = intoData
                ? new File(instance.getGamePath(), "Data")
                : new File(instance.getGamePath(), ".Prison Architect/mods");
        final String fallback = stripExt(displayName(act, zipUri));
        Toast.makeText(act, "Installing…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            File cache = new File(act.getCacheDir(), "import_content.zip");
            try (InputStream in = act.getContentResolver().openInputStream(zipUri);
                 FileOutputStream out = new FileOutputStream(cache)) {
                byte[] b = new byte[65536];
                int n;
                // Stop only on EOF (-1) — read() may return 0 without EOF, and ">0" would truncate
                // the cached copy of a large pack (caused a patch file to land as 0 bytes).
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            } catch (Exception e) {
                main.post(() -> Toast.makeText(act, "Read failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            // A GOG .sh installer is NOT a mod zip: it has no manifest.txt, and its payload is
            // already game-root-relative — a DLC
            // installer carries a ready-made Data/<Expansion>. So extract it into the instance ROOT
            // and let its own layout place the content; the Mod/DLC choice doesn't apply.
            if (GogInstallerExtractor.looksLikeGogBundle(cache)) {
                installGogInstaller(act, cache, instance, main);
                return;
            }

            ModImporter.Result r = ModImporter.importZip(cache, destDir, fallback);
            //noinspection ResultOfMethodCallIgnored
            cache.delete();
            main.post(() -> {
                String msg;
                if (r.ok()) {
                    msg = "Installed: " + String.join(", ", r.imported)
                            + " → " + (intoData ? "Data" : ".Prison Architect/mods")
                            + " of " + instance.getName();
                } else if (!r.imported.isEmpty()) {
                    msg = "Installed " + r.imported + ", with errors: " + String.join("; ", r.errors);
                } else {
                    msg = "Install failed: " + String.join("; ", r.errors);
                }
                new MaterialAlertDialogBuilder(act).setMessage(msg).setPositiveButton("OK", null).show();
            });
        }, "rd-content-install").start();
    }

    /** Install a GOG installer (a single {@code .sh}, or a zip bundling installers) into the game
     *  root. Prison Architect gameplay DLCs are entitlement-only: their complete payload is a
     *  {@code goggame-*.info} marker (plus GOG's hashdb), while the assets already live in the base
     *  game's .dat files. Runs on the caller's worker thread. */
    private static void installGogInstaller(Activity act, File src, GameInstance instance, Handler main) {
        File gameRoot = new File(instance.getGamePath());
        try {
            java.util.Set<String> before = listGogProducts(gameRoot);
            GogInstallerExtractor.extract(src, gameRoot, act.getCacheDir(), m -> Log.i(TAG, m));
            java.util.Set<String> added = listGogProducts(gameRoot);
            added.removeAll(before);
            String what = added.isEmpty()
                    ? "GOG content updated (product marker was already present)"
                    : String.join(", ", added);
            alert(act, main, "Installed: " + what + " → " + instance.getName());
        } catch (Exception e) {
            Log.e(TAG, "GOG install failed", e);
            alert(act, main, "Install failed: " + e.getMessage());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
        }
    }

    /** GOG product names represented by goggame-*.info markers in the instance root. */
    private static java.util.Set<String> listGogProducts(File gameRoot) {
        java.util.Set<String> products = new java.util.TreeSet<>();
        File[] markers = gameRoot.listFiles((dir, name) ->
                name.startsWith("goggame-") && name.endsWith(".info"));
        if (markers == null) return products;
        for (File marker : markers) {
            try {
                String json = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8);
                String name = new JSONObject(json).optString("name", "").trim();
                products.add(name.isEmpty() ? marker.getName() : name);
            } catch (Exception e) {
                products.add(marker.getName());
            }
        }
        return products;
    }

    private static void alert(Activity act, Handler main, String msg) {
        main.post(() -> new MaterialAlertDialogBuilder(act)
                .setMessage(msg).setPositiveButton("OK", null).show());
    }

    private static String displayName(Activity act, Uri uri) {
        try (Cursor c = act.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {}
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : "mod.zip";
    }

    private static TextView label(Activity act, String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setPadding(0, (int) (12 * act.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static ArrayAdapter<String> adapter(Activity act, List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(act, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}

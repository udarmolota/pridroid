package com.pridroid;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Backup / restore of Prison Architect saves and in-game settings. */
public final class GameDataTransfer {

    /** Stable top-level names inside PriDroid backup ZIPs. */
    public static final String SAVES  = "saves";
    public static final String CONFIG = "settings";
    private static final String[] ALL_PARTS = { SAVES, CONFIG };
    private static final String[] SETTINGS_FILES = { "preferences.txt", "settings.txt" };

    public static final class Result {
        public final List<String> items = new ArrayList<>(); // which parts were handled
        public String error;
        public long bytes;
        public boolean ok() { return error == null && !items.isEmpty(); }
    }

    private GameDataTransfer() {}

    /**
     * @param userDir {@code <instance>/.Prison Architect}
     * @param parts {@link #SAVES} and/or {@link #CONFIG}; empty means both
     */
    public static Result export(File userDir, OutputStream rawOut, String... parts) {
        String[] use = (parts == null || parts.length == 0) ? ALL_PARTS : parts;
        Result r = new Result();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut))) {
            byte[] buf = new byte[1 << 16];
            for (String part : use) {
                if (SAVES.equals(part)) {
                    File dir = new File(userDir, SAVES);
                    if (!dir.isDirectory()) continue;
                    zipDir(dir, SAVES, zos, buf, r);
                    r.items.add(SAVES);
                } else if (CONFIG.equals(part)) {
                    boolean any = false;
                    for (String name : SETTINGS_FILES) {
                        File f = new File(userDir, name);
                        if (!f.isFile()) continue;
                        zipFile(f, CONFIG + "/" + name, zos, buf, r);
                        any = true;
                    }
                    if (any) r.items.add(CONFIG);
                }
            }
            if (r.items.isEmpty())
                r.error = "Nothing to export (no Prison Architect " + String.join("/", use) + " yet).";
        } catch (IOException e) {
            r.error = msg(e);
        }
        return r;
    }

    private static void zipDir(File dir, String prefix, ZipOutputStream zos, byte[] buf, Result r)
            throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        if (kids.length == 0) {                       // preserve empty dirs
            zos.putNextEntry(new ZipEntry(prefix + "/"));
            zos.closeEntry();
            return;
        }
        for (File f : kids) {
            String name = prefix + "/" + f.getName();
            if (f.isDirectory()) {
                zipDir(f, name, zos, buf, r);
            } else {
                zipFile(f, name, zos, buf, r);
            }
        }
    }

    private static void zipFile(File file, String name, ZipOutputStream zos, byte[] buf, Result r)
            throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int n;
            while ((n = in.read(buf)) != -1) {
                zos.write(buf, 0, n);
                r.bytes += n;
            }
        }
        zos.closeEntry();
    }

    /** Restore a PriDroid backup or a sensibly-zipped PC Prison Architect folder. */
    public static Result importZip(File zipFile, File userDir, String... allowedParts) {
        Set<String> allow = new LinkedHashSet<>(java.util.Arrays.asList(
                (allowedParts == null || allowedParts.length == 0) ? ALL_PARTS : allowedParts));
        Result r = new Result();
        if (!userDir.exists() && !userDir.mkdirs()) { r.error = "Cannot create user data dir"; return r; }
        final String userCanon;
        try { userCanon = userDir.getCanonicalPath(); }
        catch (IOException e) { r.error = msg(e); return r; }

        Set<String> parts = new LinkedHashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            byte[] buf = new byte[1 << 16];
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty() || name.contains("../")) { zis.closeEntry(); continue; }
                String lower = name.toLowerCase(java.util.Locale.US);

                File out = null;
                String handledPart = null;

                if (allow.contains(SAVES)) {
                    String rel = null;
                    if (lower.startsWith(SAVES + "/")) {
                        rel = name.substring(SAVES.length() + 1);
                    } else {
                        // Accept a PC backup that contains .Prison Architect/saves under a wrapper.
                        String marker = ".prison architect/saves/";
                        int at = lower.indexOf(marker);
                        if (at >= 0) rel = name.substring(at + marker.length());
                        // Also accept a simple ZIP made from selected .prison + preview .png files.
                        else if (!name.contains("/")
                                && (lower.endsWith(".prison") || lower.endsWith(".png"))) rel = name;
                    }
                    if (rel != null && !rel.isEmpty()) {
                        out = new File(new File(userDir, SAVES), rel);
                        handledPart = SAVES;
                    }
                }

                if (out == null && allow.contains(CONFIG)) {
                    String base = null;
                    if (lower.startsWith(CONFIG + "/")) {
                        base = name.substring(CONFIG.length() + 1);
                    } else if (!name.contains("/")) {
                        base = name;
                    } else if (lower.endsWith("/.prison architect/preferences.txt")) {
                        base = "preferences.txt";
                    } else if (lower.endsWith("/.prison architect/settings.txt")) {
                        base = "settings.txt";
                    }
                    String canonical = canonicalSettingsName(base);
                    if (canonical != null) {
                        out = new File(userDir, canonical);
                        handledPart = CONFIG;
                    }
                }

                if (out == null) { zis.closeEntry(); continue; }
                String oc = out.getCanonicalPath();
                if (!oc.equals(userCanon) && !oc.startsWith(userCanon + File.separator)) {
                    zis.closeEntry();
                    continue;
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = zis.read(buf)) != -1) { os.write(buf, 0, n); r.bytes += n; }
                    }
                }
                parts.add(handledPart);
                zis.closeEntry();
            }
            r.items.addAll(parts);
            if (parts.isEmpty())
                r.error = "No Prison Architect saves/settings found in this zip.";
        } catch (IOException ex) {
            r.error = msg(ex);
        }
        return r;
    }

    private static String canonicalSettingsName(String name) {
        if (name == null || name.contains("/")) return null;
        for (String allowed : SETTINGS_FILES)
            if (allowed.equalsIgnoreCase(name)) return allowed;
        return null;
    }

    private static String msg(Exception e) { return e.getMessage() != null ? e.getMessage() : "I/O error"; }
}

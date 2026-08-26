package com.pridroid;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports a user-supplied Vulkan driver (AdrenoTools-style) and stores it as the single
 * {@code custom_driver.so} in the deps dir, where the renderer can dlopen it by name
 * (selected per-instance via the "Custom driver (imported)" picker entry).
 *
 * <p>Accepts either:
 * <ul>
 *   <li>a raw <b>.so</b> file — copied verbatim, or</li>
 *   <li>an AdrenoTools driver <b>.zip</b> — we read {@code meta.json}'s {@code libraryName}
 *       (falling back to the first {@code .so}) and extract that entry.</li>
 * </ul>
 * The driver is device-global (one file), so it lives outside any instance and survives
 * {@code libs.tar.xz} re-extraction (that overwrites only its own bundled members by name).
 */
public final class CustomDriverInstaller {

    private CustomDriverInstaller() {}

    /** The on-disk path of the imported driver (may not exist yet). */
    public static File driverFile() {
        return new File(AppStorage.requireSingleton().getHomePath(), C.deps.CUSTOM_DRIVER);
    }

    public static boolean isInstalled() {
        File f = driverFile();
        return f.isFile() && f.length() > 0;
    }

    public static boolean remove() {
        File f = driverFile();
        return !f.exists() || f.delete();
    }

    /**
     * Import the driver pointed to by {@code uri}. {@code displayName} is the picked file name
     * (used only to decide .so vs .zip). Runs on the caller's thread (do it off the UI thread).
     *
     * @return the number of bytes written to custom_driver.so
     * @throws IOException on read/extract failure or if no .so could be found in a zip
     */
    public static long importFrom(@NonNull Context ctx, @NonNull Uri uri,
                                  @Nullable String displayName) throws IOException {
        File dest = driverFile();
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create deps dir: " + parent);

        File source = new File(ctx.getCacheDir(), "pridroid_driver_source_" + System.nanoTime());
        File candidate = new File(parent, C.deps.CUSTOM_DRIVER_FILENAME
                + ".importing." + System.nanoTime());
        try {
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Cannot open the selected file");
                copyTo(in, source);
            }

            // Providers sometimes return no/incorrect extension. Trust the file signature first.
            boolean isZip = hasMagic(source, new byte[]{ 'P', 'K', 3, 4 })
                    || (displayName != null && displayName.toLowerCase().endsWith(".zip"));
            if (isZip) extractSoFromZip(source, candidate);
            else try (InputStream in = new BufferedInputStream(new FileInputStream(source))) {
                copyTo(in, candidate);
            }

            validateArm64SharedObject(candidate);
            try {
                Files.move(candidate.toPath(), dest.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(candidate.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return dest.length();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            //noinspection ResultOfMethodCallIgnored
            candidate.delete();
        }
    }

    private static long copyTo(InputStream in, File dest) throws IOException {
        long total = 0;
        try (OutputStream os = new FileOutputStream(dest, false)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) { os.write(buf, 0, r); total += r; }
        }
        return total;
    }

    /** Two-pass AdrenoTools ZIP selection: read meta.json first, then extract its libraryName. */
    private static long extractSoFromZip(File archive, File dest) throws IOException {
        String libraryName = null;
        ZipEntry firstSo = null;
        ZipEntry namedSo = null;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buf = new byte[64 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String base = baseName(e.getName());
                if (base.equalsIgnoreCase("meta.json")) {
                    try (InputStream in = zip.getInputStream(e)) {
                        libraryName = parseLibraryName(readAll(in, buf));
                    }
                    break;
                }
            }

            entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String base = baseName(e.getName());
                if (!base.toLowerCase().endsWith(".so")) continue;
                if (firstSo == null) firstSo = e;
                if (libraryName != null && (base.equalsIgnoreCase(libraryName)
                        || e.getName().equalsIgnoreCase(libraryName))) {
                    namedSo = e;
                    break;
                }
            }

            ZipEntry chosen = namedSo != null ? namedSo : firstSo;
            if (chosen == null) throw new IOException("No .so found inside the driver zip");
            try (InputStream in = zip.getInputStream(chosen)) {
                return copyTo(in, dest);
            }
        }
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static boolean hasMagic(File file, byte[] magic) {
        try (InputStream in = new FileInputStream(file)) {
            for (byte expected : magic) if (in.read() != (expected & 0xff)) return false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Reject ZIPs, x86 libraries and truncated junk before replacing a known-good custom driver. */
    private static void validateArm64SharedObject(File file) throws IOException {
        if (!file.isFile() || file.length() < 64)
            throw new IOException("Driver is empty or truncated");
        byte[] h = new byte[64];
        try (InputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < h.length) {
                int n = in.read(h, off, h.length - off);
                if (n < 0) throw new IOException("Driver has a truncated ELF header");
                off += n;
            }
        }
        if (h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F'
                || h[4] != 2 || h[5] != 1)
            throw new IOException("Selected file is not a 64-bit ELF library");
        int type = (h[16] & 0xff) | ((h[17] & 0xff) << 8);
        int machine = (h[18] & 0xff) | ((h[19] & 0xff) << 8);
        if (type != 3) throw new IOException("Selected ELF is not a shared library");
        if (machine != 183) throw new IOException("Driver is not ARM64 (AArch64)");
    }

    private static byte[] readAll(InputStream in, byte[] buf) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    @Nullable
    private static String parseLibraryName(byte[] json) {
        try {
            JSONObject o = new JSONObject(new String(json, "UTF-8"));
            String lib = o.optString("libraryName", null);
            return (lib != null && !lib.isEmpty()) ? lib : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}

package com.pridroid;

import android.util.Log;

import net.sf.sevenzipjbinding.ArchiveFormat;
import net.sf.sevenzipjbinding.ExtractAskMode;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IArchiveExtractCallback;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One-time conversion of the GOG build's RAR .dat data archives into stored (uncompressed) zips.
 *
 * Prison Architect's main.dat / sounds.dat / collectables.dat are solid RAR4 archives. The game
 * bundles unrar and fully unpacks them into RAM on EVERY launch; under box64 emulation that
 * measured ~27 s + 18 s + 3 s per start (Snapdragon 8 Elite, 2026-08-27 logs). The engine picks
 * its archive backend by magic bytes ("Rar!" vs "PK") and its zip backend (minizip) reads entries
 * on demand with no upfront decompression — prisons.dat always shipped as a zip. Converting once
 * at install drops the archive phase of every launch from ~48 s to about 1 s. Entries are STORED,
 * so in-game reads skip inflate too; the three files together grow only ~70 MB.
 *
 * RAR decoding uses 7-Zip-JBinding (p7zip's native codec). A pure-Java attempt with junrar 7.5.5
 * was rejected first: it silently mis-decodes these solid RAR4 archives (verified 2026-08-27,
 * 1138/1150 files wrong), which would have corrupted game data.
 *
 * A failed conversion is non-fatal: the original RAR stays in place and the game keeps its
 * slow-but-working unrar path for that archive.
 */
public final class DatRepacker {
    private static final String TAG = "DatRepacker";

    public interface Progress { void report(String message); }

    private DatRepacker() {}

    private static boolean sevenZipReady;

    private static synchronized boolean ensureSevenZip() {
        if (sevenZipReady) return true;
        try {
            System.loadLibrary("7-Zip-JBinding");
            SevenZip.initLoadedLibraries();
            sevenZipReady = true;
        } catch (Throwable t) {
            Log.w(TAG, "7-Zip-JBinding native init failed; RAR .dat files stay unconverted", t);
        }
        return sevenZipReady;
    }

    /** Convert every RAR-format *.dat at the instance root; zips and other files are untouched. */
    public static void repackAll(File instanceDir, Progress progress) {
        File[] dats = instanceDir.listFiles((d, n) -> n.toLowerCase().endsWith(".dat"));
        if (dats == null) return;
        boolean anyRar = false;
        for (File dat : dats) if (isRar(dat)) { anyRar = true; break; }
        if (!anyRar) return;
        if (!ensureSevenZip()) {
            progress.report("Fast-loading conversion unavailable — the game will read its "
                    + "archives directly (slower start, but everything works).");
            return;
        }
        java.util.Arrays.sort(dats);
        for (File dat : dats) {
            if (!isRar(dat)) continue;
            try {
                repackOne(dat, progress);
            } catch (Throwable t) {
                Log.w(TAG, "Keeping " + dat.getName() + " as RAR (conversion failed)", t);
                progress.report("Could not convert " + dat.getName()
                        + " — keeping the original (loads slower but works).");
            }
        }
    }

    private static boolean isRar(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] m = new byte[4];
            r.readFully(m);
            return m[0] == 'R' && m[1] == 'a' && m[2] == 'r' && m[3] == '!';
        } catch (IOException e) {
            return false;
        }
    }

    private static void repackOne(File dat, Progress progress) throws Exception {
        File tmp = new File(dat.getParentFile(), dat.getName() + ".zip.tmp");
        RandomAccessFile raf = null;
        IInArchive archive = null;
        ZipOutputStream zip = null;
        boolean ok = false;
        try {
            raf = new RandomAccessFile(dat, "r");
            archive = SevenZip.openInArchive(ArchiveFormat.RAR, new RandomAccessFileInStream(raf));
            int count = archive.getNumberOfItems();

            long total = 0;
            int files = 0;
            int[] indices = new int[count];
            for (int i = 0; i < count; i++) {
                boolean folder = Boolean.TRUE.equals(archive.getProperty(i,
                        net.sf.sevenzipjbinding.PropID.IS_FOLDER));
                if (folder) continue;
                Object sz = archive.getProperty(i, net.sf.sevenzipjbinding.PropID.SIZE);
                if (sz instanceof Long) total += (Long) sz;
                indices[files++] = i;
            }
            indices = java.util.Arrays.copyOf(indices, files);

            zip = new ZipOutputStream(new FileOutputStream(tmp));
            // Solid RAR: a single batch extract walks the stream once, in archive order. The
            // callback opens one STORED zip entry per file and streams the decoded bytes straight
            // into it. 7-Zip's own per-item CRC check (setOperationResult) is our integrity gate.
            RepackCallback cb = new RepackCallback(archive, zip, dat.getName(), total, progress);
            archive.extract(indices, false, cb);
            if (cb.failures > 0)
                throw new IOException(cb.failures + " item(s) failed extraction");

            zip.finish();
            zip.close(); zip = null;
            archive.close(); archive = null;
            raf.close(); raf = null;

            // rename(2) over the original atomically replaces it (same directory).
            if (!tmp.renameTo(dat) && (!dat.delete() || !tmp.renameTo(dat)))
                throw new IOException("could not replace " + dat.getName());
            ok = true;
        } finally {
            closeQuietly(zip);
            if (archive != null) try { archive.close(); } catch (Throwable ignored) {}
            if (raf != null) try { raf.close(); } catch (Throwable ignored) {}
            if (!ok && tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
        }
    }

    private static void closeQuietly(ZipOutputStream z) {
        if (z != null) try { z.close(); } catch (Throwable ignored) {}
    }

    /** Streams each archive item into a STORED zip entry as 7-Zip decodes the solid stream. */
    private static final class RepackCallback implements IArchiveExtractCallback {
        private final IInArchive archive;
        private final ZipOutputStream zip;
        private final String datName;
        private final long total;
        private final Progress progress;
        int failures;

        private final java.util.HashSet<String> seenPaths = new java.util.HashSet<>();
        private long done;
        private int lastPct = -10;
        private boolean entryOpen;
        private long entryExpected;
        private long entryWritten;

        RepackCallback(IInArchive archive, ZipOutputStream zip, String datName,
                       long total, Progress progress) {
            this.archive = archive;
            this.zip = zip;
            this.datName = datName;
            this.total = total;
            this.progress = progress;
        }

        @Override
        public ISequentialOutStream getStream(int index, ExtractAskMode mode) throws SevenZipException {
            if (mode != ExtractAskMode.EXTRACT) return null;
            boolean folder = Boolean.TRUE.equals(archive.getProperty(index,
                    net.sf.sevenzipjbinding.PropID.IS_FOLDER));
            if (folder) return null;

            String path = String.valueOf(archive.getProperty(index,
                    net.sf.sevenzipjbinding.PropID.PATH)).replace('\\', '/');
            entryExpected = 0;
            // A solid RAR may legitimately hold two files with the same name (seen in sounds.dat:
            // Inmate_Fem_Heckle_1h.ogg twice). A zip cannot, and the game is fine with one copy —
            // the known-good PC-built zip collapsed these on extraction too. Keep the first, skip
            // the rest (returning null tells 7-Zip to skip decoding this item).
            if (!seenPaths.add(path)) return null;

            long size = toLong(archive.getProperty(index, net.sf.sevenzipjbinding.PropID.SIZE));
            long crc  = toLong(archive.getProperty(index, net.sf.sevenzipjbinding.PropID.CRC)) & 0xFFFFFFFFL;

            try {
                ZipEntry e = new ZipEntry(path);
                e.setMethod(ZipEntry.STORED);
                e.setSize(size);
                e.setCompressedSize(size);
                e.setCrc(crc);
                zip.putNextEntry(e);
            } catch (IOException io) {
                throw new SevenZipException("zip putNextEntry failed for " + path, io);
            }
            entryOpen = true;
            entryExpected = size;
            entryWritten = 0;

            return data -> {
                try {
                    zip.write(data);
                } catch (IOException io) {
                    throw new SevenZipException("zip write failed", io);
                }
                entryWritten += data.length;
                return data.length;
            };
        }

        @Override
        public void prepareOperation(ExtractAskMode mode) { }

        @Override
        public void setOperationResult(ExtractOperationResult result) throws SevenZipException {
            if (entryOpen) {
                try {
                    zip.closeEntry();   // throws if written size disagrees with the declared size
                } catch (IOException io) {
                    throw new SevenZipException("zip closeEntry failed", io);
                }
                entryOpen = false;
            }
            if (result != ExtractOperationResult.OK) {
                failures++;
                Log.w(TAG, datName + ": item extraction result " + result);
                return;
            }
            done += entryExpected;
            if (total > 0) {
                int pct = (int) (done * 100 / total);
                if (pct >= lastPct + 10) {
                    lastPct = pct;
                    progress.report("Converting " + datName + " for fast loading... " + pct + "%");
                }
            }
        }

        @Override public void setTotal(long t) { }
        @Override public void setCompleted(long c) { }

        private static long toLong(Object o) {
            return (o instanceof Number) ? ((Number) o).longValue() : 0L;
        }
    }
}

package io.github.richeyworks.jerky;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Jerky — engine eleven of the ecosystem: cold storage, dried for the road. Packs a
 * SmokeHouse backup directory (segments + manifest, the shape {@code backup()} and DryAge's
 * vault produce) into one DEFLATE-compressed, CRC-verified {@code .jerky} archive, and
 * unpacks it back into a directory that {@code SmokeHouse.restore} opens — the log is the
 * only truth, so a verified archive of the log IS a verified archive of everything.
 *
 * <p>The seventh-engine ADR deferred this behind a measurement; the deferral was overridden
 * by decree (2026-07-18) with the scope honestly narrowed to what needs no measurement:
 * <b>archival compression</b> (backups spend most of their life cold on disk; smaller is
 * strictly better). The deferred half — a scan-friendly <em>columnar</em> cold format —
 * keeps its trigger: a benchmark showing cold-segment scans as a real cost.</p>
 *
 * <p>Format: {@code [magic][fileCount] then per file [name][origSize][deflated bytes...]}
 * with a trailing CRC32 over everything before it. {@link #verify} walks an archive without
 * extracting; {@link #restore} refuses a bad CRC rather than unpacking garbage.</p>
 */
public final class Jerky {

    private static final int MAGIC = 0x4A_45_52_4B;            // "JERK"
    private static final String SUFFIX = ".jerky";

    /** One archive's summary: what went in, what it weighs now. */
    public record Cured(Path archive, int files, long rawBytes, long curedBytes) {
        public double ratio() {
            return rawBytes == 0 ? 1.0 : (double) curedBytes / rawBytes;
        }
    }

    private Jerky() {
    }

    /**
     * Pack {@code backupDir}'s regular files into {@code archive} (a {@code .jerky} path).
     * The source directory is read-only to this operation.
     */
    public static Cured cure(Path backupDir, Path archive) throws IOException {
        if (!Files.isDirectory(backupDir)) {
            throw new IllegalArgumentException("not a directory: " + backupDir);
        }
        if (!archive.getFileName().toString().endsWith(SUFFIX)) {
            throw new IllegalArgumentException("archives end in " + SUFFIX + ": " + archive);
        }
        List<Path> files;
        try (var listing = Files.list(backupDir)) {
            files = listing.filter(Files::isRegularFile).sorted().toList();
        }
        long raw = 0;
        CRC32 crc = new CRC32();
        try (OutputStream fileOut = Files.newOutputStream(archive)) {
            CheckedOut checked = new CheckedOut(fileOut, crc);
            DataOutputStream out = new DataOutputStream(checked);
            out.writeInt(MAGIC);
            out.writeInt(files.size());
            for (Path f : files) {
                out.writeUTF(f.getFileName().toString());
                long size = Files.size(f);
                out.writeLong(size);
                raw += size;
                // Deflate to a buffer first: the compressed length goes in the header, so
                // restore can slice each file's region EXACTLY (inflaters read ahead — an
                // unframed boundary would corrupt the next entry).
                java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
                DeflaterOutputStream deflate = new DeflaterOutputStream(compressed,
                        new Deflater(Deflater.BEST_COMPRESSION, true), 1 << 16);
                Files.copy(f, deflate);
                deflate.finish();
                out.writeInt(compressed.size());
                compressed.writeTo(out);
            }
            out.flush();
            new DataOutputStream(fileOut).writeLong(crc.getValue());   // trailer, unsummed
        }
        return new Cured(archive, files.size(), raw, Files.size(archive));
    }

    /** Walk the archive end to end and check its CRC — true means restorable. */
    public static boolean verify(Path archive) {
        try {
            readInto(archive, null);
            return true;
        } catch (IOException | RuntimeException bad) {
            return false;
        }
    }

    /**
     * Unpack {@code archive} into {@code targetDir} (created; must be empty). Refuses a bad
     * CRC before leaving any file behind — verification is not optional here.
     */
    public static void restore(Path archive, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (var listing = Files.list(targetDir)) {
            if (listing.findAny().isPresent()) {
                throw new IllegalArgumentException("target not empty: " + targetDir);
            }
        }
        if (!verify(archive)) {                                // full pass BEFORE extraction
            throw new IOException("archive fails verification: " + archive);
        }
        readInto(archive, targetDir);
    }

    /**
     * The archived file names, in archive order — the table of contents, without extracting
     * anything (ADR scan-sidecar, 2026-08-20). CRC-verified like every read.
     */
    public static List<String> names(Path archive) throws IOException {
        List<String> out = new ArrayList<>();
        walk(archive, (name, inflate) -> out.add(name));
        return out;
    }

    /**
     * Extract ONE archived file's bytes (ADR scan-sidecar, 2026-08-20): the whole body is
     * CRC-verified (it is already in memory — v1's whole-body checksum makes that free), but
     * only the requested entry is inflated; the others are skipped by their framed compressed
     * length. This is the cold-scan door: {@code extract(archive, "scan.run")} feeds
     * {@code SmokeHouse.scanSorted} and history is scanned without resurrecting a store.
     * The v1 framing supported this from birth; it just had no caller until the 2026-08-20
     * benchmark (524× the raw-read floor) gave it one.
     *
     * @throws IOException if the archive is corrupt or holds no file named {@code name}
     */
    public static byte[] extract(Path archive, String name) throws IOException {
        Objects.requireNonNull(name, "name");
        byte[][] found = new byte[1][];
        List<String> seen = new ArrayList<>();                 // ninth-pass finding 3: one walk,
        try {                                                  // not a second full read just to
            walk(archive, (entryName, inflate) -> {            // name what IS archived
                seen.add(entryName);
                if (entryName.equals(name)) {
                    found[0] = inflate.get();
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        if (found[0] == null) {
            throw new IOException("no '" + name + "' in " + archive + "; archived: " + seen);
        }
        return found[0];
    }

    /** An entry visitor: inflate lazily via the supplier, or don't and the entry is skipped. */
    private interface EntryVisitor {
        void entry(String name, java.util.function.Supplier<byte[]> inflate) throws IOException;
    }

    /** CRC-verify the whole archive, then walk entries, inflating only where the visitor asks. */
    private static void walk(Path archive, EntryVisitor visitor) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        if (bytes.length < Long.BYTES + Integer.BYTES * 2) {
            throw new IOException("archive truncated: " + bytes.length + " bytes");
        }
        int body = bytes.length - Long.BYTES;
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, body);
        DataInputStream trailer = new DataInputStream(
                new java.io.ByteArrayInputStream(bytes, body, Long.BYTES));
        if (crc.getValue() != trailer.readLong()) {
            throw new IOException("CRC mismatch: " + archive);
        }
        DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(bytes, 0, body));
        if (in.readInt() != MAGIC) {
            throw new IOException("not a .jerky archive: " + archive);
        }
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            long size = in.readLong();
            int compressedSize = in.readInt();
            byte[] region = new byte[compressedSize];
            in.readFully(region);                              // the exact compressed slice
            visitor.entry(name, () -> {
                try {
                    InputStream inflate = new InflaterInputStream(
                            new java.io.ByteArrayInputStream(region),
                            new java.util.zip.Inflater(true));
                    return readExactly(inflate, size, name);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    /** Shared walk: with a target, extract; with null, just parse + sum. */
    private static void readInto(Path archive, Path targetDir) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        if (bytes.length < Long.BYTES + Integer.BYTES * 2) {
            throw new IOException("archive truncated: " + bytes.length + " bytes");
        }
        int body = bytes.length - Long.BYTES;
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, body);
        DataInputStream trailer = new DataInputStream(
                new java.io.ByteArrayInputStream(bytes, body, Long.BYTES));
        if (crc.getValue() != trailer.readLong()) {
            throw new IOException("CRC mismatch: " + archive);
        }
        DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(bytes, 0, body));
        if (in.readInt() != MAGIC) {
            throw new IOException("not a .jerky archive: " + archive);
        }
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                throw new IOException("unsafe archived name: " + name);
            }
            long size = in.readLong();
            int compressedSize = in.readInt();
            byte[] region = new byte[compressedSize];
            in.readFully(region);                              // the exact compressed slice
            InputStream inflate = new InflaterInputStream(
                    new java.io.ByteArrayInputStream(region),
                    new java.util.zip.Inflater(true));
            byte[] content = readExactly(inflate, size, name);
            if (targetDir != null) {
                Files.write(targetDir.resolve(name), content);
            }
        }
    }

    private static byte[] readExactly(InputStream in, long size, String name)
            throws IOException {
        if (size > Integer.MAX_VALUE) {
            throw new IOException("file too large for v1: " + name);
        }
        byte[] content = new byte[(int) size];
        int off = 0;
        while (off < content.length) {
            int n = in.read(content, off, content.length - off);
            if (n < 0) {
                throw new IOException("archive ended mid-file: " + name);
            }
            off += n;
        }
        return content;
    }

    /** A pass-through that feeds a CRC32 — the archive's whole-body checksum. */
    private static final class CheckedOut extends OutputStream {
        private final OutputStream delegate;
        private final CRC32 crc;

        CheckedOut(OutputStream delegate, CRC32 crc) {
            this.delegate = delegate;
            this.crc = crc;
        }

        @Override
        public void write(int b) throws IOException {
            crc.update(b);
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            crc.update(b, off, len);
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }
}

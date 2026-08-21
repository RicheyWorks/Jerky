package io.github.richeyworks.jerky;

import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The round trip is the oracle: store → backup → cure → restore → open must equal the
 * original, byte-for-byte in effect. Corruption anywhere in the archive must fail
 * verification and refuse restoration — never unpack garbage. Seeded and deterministic.
 */
class JerkyTest {

    private static SmokeHouseOptions<Long, String> opts() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .segmentBytes(2048)                            // several segments in the pack
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);
    }

    private static TreeMap<Long, String> scan(SmokeHouse<Long, String> store) throws IOException {
        TreeMap<Long, String> out = new TreeMap<>();
        if (store.size() > 0) {
            store.range(store.firstKey(), store.lastKey(), out::put);
        }
        return out;
    }

    @Test
    void theRoundTripPreservesTheTruthAndShrinksIt(@TempDir Path storeDir,
                                                   @TempDir Path backupDir,
                                                   @TempDir Path archiveDir,
                                                   @TempDir Path restoreDir)
            throws IOException {
        Random rnd = new Random(42);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            for (int i = 0; i < 800; i++) {
                long key = rnd.nextInt(200);
                String v = "value-" + key + "-" + i + "-padding-padding-padding";
                store.put(key, v);
                oracle.put(key, v);
            }
            store.backup(backupDir);
        }

        Path archive = archiveDir.resolve("cold.jerky");
        Jerky.Cured cured = Jerky.cure(backupDir, archive);
        assertTrue(cured.files() > 1, "several segments + manifest in the pack");
        assertTrue(cured.curedBytes() < cured.rawBytes(),
                "repetitive log records must compress: " + cured);
        assertTrue(Jerky.verify(archive));

        Jerky.restore(archive, restoreDir.resolve("unpacked"));
        try (SmokeHouse<Long, String> revived =
                     SmokeHouse.restore(restoreDir.resolve("unpacked"), opts())) {
            assertEquals(oracle, scan(revived), "dried and revived, identical");
            assertEquals(oracle.size(), revived.size());
        }
    }

    @Test
    void corruptionAnywhereRefusesLoudly(@TempDir Path storeDir, @TempDir Path backupDir,
                                         @TempDir Path archiveDir) throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            for (long k = 0; k < 50; k++) {
                store.put(k, "v" + k);
            }
            store.backup(backupDir);
        }
        Path archive = archiveDir.resolve("cold.jerky");
        Jerky.cure(backupDir, archive);

        byte[] bytes = Files.readAllBytes(archive);
        bytes[bytes.length / 2] ^= 0x40;                       // one bit, mid-archive
        Path tampered = archiveDir.resolve("tampered.jerky");
        Files.write(tampered, bytes);

        assertFalse(Jerky.verify(tampered), "verification must catch a single flipped bit");
        assertThrows(IOException.class,
                () -> Jerky.restore(tampered, archiveDir.resolve("never")),
                "restoration refuses rather than unpacking garbage");
        assertFalse(Files.exists(archiveDir.resolve("never").resolve("manifest")),
                "nothing extracted from a bad archive");
    }

    @Test
    void unsafeInputsFailLoudly(@TempDir Path dir) throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> Jerky.cure(dir.resolve("nope"), dir.resolve("x.jerky")));
        assertThrows(IllegalArgumentException.class,
                () -> Jerky.cure(dir, dir.resolve("wrong-suffix.zip")));
        Path notAnArchive = dir.resolve("junk.jerky");
        Files.write(notAnArchive, new byte[]{1, 2, 3});
        assertFalse(Jerky.verify(notAnArchive));
    }

    @Test
    void targetedExtractionInflatesOnlyWhatWasAsked(@TempDir Path srcDir, @TempDir Path archiveDir)
            throws IOException {
        // Three files; extract must return the middle one byte-true without touching the rest.
        Files.writeString(srcDir.resolve("aaa.txt"), "first file, some bytes");
        byte[] wanted = new byte[4096];
        new Random(7).nextBytes(wanted);
        Files.write(srcDir.resolve("scan.run"), wanted);
        Files.writeString(srcDir.resolve("zzz.txt"), "last file");
        Path archive = archiveDir.resolve("toc.jerky");
        Jerky.cure(srcDir, archive);

        assertEquals(java.util.List.of("aaa.txt", "scan.run", "zzz.txt"), Jerky.names(archive),
                "the table of contents, in archive order");
        assertTrue(java.util.Arrays.equals(wanted, Jerky.extract(archive, "scan.run")),
                "the extracted entry is byte-true");
        assertThrows(IOException.class, () -> Jerky.extract(archive, "missing.bin"),
                "an absent name fails loudly, naming what IS archived");

        // Corruption anywhere refuses extraction of anything — whole-body CRC, whole-body trust.
        byte[] bytes = Files.readAllBytes(archive);
        bytes[10] ^= 0x01;
        Path tampered = archiveDir.resolve("tampered.jerky");
        Files.write(tampered, bytes);
        assertThrows(IOException.class, () -> Jerky.extract(tampered, "scan.run"));
    }

    @Test
    void archivesAcrossTheDeflaterBufferBoundaryRoundTripByteExact(@TempDir Path srcDir,
                                                                   @TempDir Path archiveDir,
                                                                   @TempDir Path restoreDir)
            throws IOException {
        // Tenth-pass J1: the flush/close-ordering lead. cure() writes the body through an
        // UNBUFFERED DataOutputStream over a pass-through CheckedOut and flushes before appending
        // the trailer, so there is no buffered layer that could leave body bytes behind the
        // trailer — the hypothesized bug does not exist. But the hunt flagged "archives near a
        // buffer boundary" as the untested corner; this closes it. Payloads straddle the
        // deflater's 1<<16 buffer with incompressible data (deflated ≈ raw, crossing the boundary
        // repeatedly) plus a highly-compressible one; every file must round-trip byte-exact.
        int buf = 1 << 16;
        java.util.Map<String, byte[]> expected = new TreeMap<>();
        Random rnd = new Random(20260821L);
        int[] sizes = {0, 1, buf - 1, buf, buf + 1, 2 * buf, 3 * buf + 7};
        for (int i = 0; i < sizes.length; i++) {
            byte[] payload = new byte[sizes[i]];
            rnd.nextBytes(payload);                             // incompressible: crosses the buffer
            String name = String.format("rand-%02d-%d.bin", i, sizes[i]);
            expected.put(name, payload);
            Files.write(srcDir.resolve(name), payload);
        }
        byte[] runs = new byte[5 * buf];
        java.util.Arrays.fill(runs, (byte) 'Q');               // highly compressible: deflated ≪ raw
        expected.put("runs.bin", runs);
        Files.write(srcDir.resolve("runs.bin"), runs);

        Path archive = archiveDir.resolve("boundary.jerky");
        Jerky.cure(srcDir, archive);
        assertTrue(Jerky.verify(archive), "the whole-body CRC must verify across the boundary");

        Path unpacked = restoreDir.resolve("unpacked");
        Jerky.restore(archive, unpacked);
        for (var e : expected.entrySet()) {
            byte[] restored = Files.readAllBytes(unpacked.resolve(e.getKey()));
            assertTrue(java.util.Arrays.equals(e.getValue(), restored),
                    "restored " + e.getKey() + " must be byte-exact across the buffer boundary");
            assertTrue(java.util.Arrays.equals(e.getValue(), Jerky.extract(archive, e.getKey())),
                    "targeted extract of " + e.getKey() + " must be byte-exact too");
        }
    }
}

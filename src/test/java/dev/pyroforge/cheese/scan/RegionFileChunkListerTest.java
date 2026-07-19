package dev.pyroforge.cheese.scan;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionFileChunkListerTest {

    @TempDir
    File tempDir;

    @Test
    void findsOnlyChunksWithNonZeroLocationEntries() throws Exception {
        writeFakeRegionFile(new File(tempDir, "r.0.0.mca"), List.of(index(5, 0), index(2, 1)));

        List<ChunkCoord> found = RegionFileChunkLister.list(tempDir);

        assertEquals(2, found.size());
        assertTrue(found.contains(new ChunkCoord(5, 0)));
        assertTrue(found.contains(new ChunkCoord(2, 1)));
    }

    @Test
    void appliesRegionOffsetForNegativeRegionCoords() throws Exception {
        writeFakeRegionFile(new File(tempDir, "r.-1.-1.mca"), List.of(index(31, 31)));

        List<ChunkCoord> found = RegionFileChunkLister.list(tempDir);

        assertEquals(1, found.size());
        // region (-1,-1) covers chunks -32..-1; local (31,31) is the last chunk in that region.
        assertTrue(found.contains(new ChunkCoord(-1, -1)));
    }

    @Test
    void ignoresNonRegionFiles() throws Exception {
        File notRegion = new File(tempDir, "session.lock");
        notRegion.createNewFile();

        List<ChunkCoord> found = RegionFileChunkLister.list(tempDir);

        assertEquals(0, found.size());
    }

    private static int index(int localX, int localZ) {
        return localX + localZ * 32;
    }

    private static void writeFakeRegionFile(File file, List<Integer> presentIndexes) throws Exception {
        byte[] header = new byte[4096];
        for (int index : presentIndexes) {
            int offset = index * 4;
            // Any non-zero 4-byte entry marks the chunk as present; sector offset/count values
            // themselves don't matter since RegionFileChunkLister only checks existence.
            header[offset + 3] = 1;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.write(header);
        }
    }
}

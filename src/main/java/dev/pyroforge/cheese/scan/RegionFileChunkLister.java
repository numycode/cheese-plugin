package dev.pyroforge.cheese.scan;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists every chunk that actually exists on disk for a world, by reading the location table
 * in the header of each Anvil region file (.mca) — no NBT parsing needed, just existence.
 * This is what makes a "--full" scan possible without loading every chunk in an infinite
 * world: only chunks that were ever generated get touched. Pure file I/O, safe to call off
 * the main thread.
 */
public final class RegionFileChunkLister {

    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int HEADER_BYTES = 4096;
    private static final int CHUNKS_PER_REGION_SIDE = 32;

    private RegionFileChunkLister() {
    }

    public static List<ChunkCoord> list(File regionFolder) throws IOException {
        List<ChunkCoord> coords = new ArrayList<>();
        File[] files = regionFolder.listFiles((dir, name) -> REGION_FILE_PATTERN.matcher(name).matches());
        if (files == null) {
            return coords;
        }
        for (File file : files) {
            Matcher matcher = REGION_FILE_PATTERN.matcher(file.getName());
            if (!matcher.matches()) {
                continue;
            }
            int regionX = Integer.parseInt(matcher.group(1));
            int regionZ = Integer.parseInt(matcher.group(2));
            coords.addAll(readExistingChunks(file, regionX, regionZ));
        }
        return coords;
    }

    private static List<ChunkCoord> readExistingChunks(File regionFile, int regionX, int regionZ) throws IOException {
        List<ChunkCoord> coords = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(regionFile, "r")) {
            if (raf.length() < HEADER_BYTES) {
                return coords;
            }
            byte[] header = new byte[HEADER_BYTES];
            raf.readFully(header);
            for (int i = 0; i < CHUNKS_PER_REGION_SIDE * CHUNKS_PER_REGION_SIDE; i++) {
                int offset = i * 4;
                // 4-byte big-endian entry: 3-byte sector offset + 1-byte sector count.
                // Both zero means the chunk was never generated.
                int entry = ((header[offset] & 0xFF) << 24)
                        | ((header[offset + 1] & 0xFF) << 16)
                        | ((header[offset + 2] & 0xFF) << 8)
                        | (header[offset + 3] & 0xFF);
                if (entry == 0) {
                    continue;
                }
                int localX = i % CHUNKS_PER_REGION_SIDE;
                int localZ = i / CHUNKS_PER_REGION_SIDE;
                coords.add(new ChunkCoord(
                        regionX * CHUNKS_PER_REGION_SIDE + localX,
                        regionZ * CHUNKS_PER_REGION_SIDE + localZ));
            }
        }
        return coords;
    }
}

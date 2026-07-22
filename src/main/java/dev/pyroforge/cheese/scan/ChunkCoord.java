package dev.pyroforge.cheese.scan;

/** Chunk coordinates, in chunk units (i.e. block coordinates >> 4), not region-file units. */
public record ChunkCoord(int x, int z) {
}

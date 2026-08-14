package io.github.thebusybiscuit.slimefun4.utils;

/**
 * Packs Minecraft block coordinates into a single primitive {@code long} for use as a
 * zero-allocation, zero-boxing hash key. Uses the same bit layout as vanilla NMS
 * {@code BlockPos#asLong()}: 26 bits for X, 12 bits for Y (offset by 2048), 26 bits for Z.
 * This comfortably covers the full vanilla world border on X/Z and a Y range of
 * -2048..2047, well beyond the current -64..320 build height limit.
 *
 * This class intentionally does not encode a World - callers that need to disambiguate
 * between worlds must scope their own storage by world (e.g. one map per world, or a
 * world-aware outer key), the same way {@link me.mrCookieSlime.Slimefun.api.BlockStorage}
 * already has one instance per {@link org.bukkit.World}.
 */
public final class FastBlockPos {

    private static final int BITS_X = 26;
    private static final int BITS_Z = 26;
    private static final int BITS_Y = 12;

    private static final long MASK_X = (1L << BITS_X) - 1L;
    private static final long MASK_Y = (1L << BITS_Y) - 1L;
    private static final long MASK_Z = (1L << BITS_Z) - 1L;

    private static final int Y_OFFSET = 1 << (BITS_Y - 1);

    private FastBlockPos() {}

    /**
     * Packs the given block coordinates into a single {@code long}.
     *
     * @param x the block X coordinate
     * @param y the block Y coordinate
     * @param z the block Z coordinate
     * @return the packed representation
     */
    public static long pack(int x, int y, int z) {
        long packedX = (x & MASK_X) << (BITS_Y + BITS_Z);
        long packedY = ((long) (y + Y_OFFSET) & MASK_Y) << BITS_Z;
        long packedZ = z & MASK_Z;

        return packedX | packedY | packedZ;
    }

    /**
     * Extracts the X coordinate from a packed {@code long}.
     *
     * @param packed the packed representation, as produced by {@link #pack(int, int, int)}
     * @return the block X coordinate
     */
    public static int unpackX(long packed) {
        return (int) (packed << (64 - BITS_X - BITS_Y - BITS_Z) >> (64 - BITS_X));
    }

    /**
     * Extracts the Y coordinate from a packed {@code long}.
     *
     * @param packed the packed representation, as produced by {@link #pack(int, int, int)}
     * @return the block Y coordinate
     */
    public static int unpackY(long packed) {
        return (int) ((packed >> BITS_Z) & MASK_Y) - Y_OFFSET;
    }

    /**
     * Extracts the Z coordinate from a packed {@code long}.
     *
     * @param packed the packed representation, as produced by {@link #pack(int, int, int)}
     * @return the block Z coordinate
     */
    public static int unpackZ(long packed) {
        return (int) (packed << (64 - BITS_Z) >> (64 - BITS_Z));
    }
}

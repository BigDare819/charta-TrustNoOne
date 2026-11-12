package dev.trustnoone.util;

import org.jetbrains.annotations.Nullable;

/**
 * Works out how large a Charta card/suit blob actually is.
 *
 * <p>Charta stores card art as one byte per pixel and hard-codes the reading size to {@code CardImage}'s
 * 25x35 ({@code SuitImage}'s 13x13), so a pack that ships 325x455 art gets box-filtered down to 25x35 and
 * loses 13x of detail for no good reason. Two ways out are handled here:
 *
 * <ul>
 *   <li><b>A header</b> — the format this pack actually ships. {@code MYAV} + big-endian width + height is
 *       prepended to the gzip stream, so the real size is exact for <em>any</em> aspect ratio. That matters
 *       for the hand-drawn art that is not a multiple of 5:7 (the 320x320 stamps, the 1920x1080 quest card):
 *       without it they would have to be resampled to a legal size, and the resampled blob would then
 *       disagree with the full-colour PNG bound over it — the glow layer, which is derived from the blob,
 *       would sit visibly off the art.</li>
 *   <li><b>A guess</b> — the fallback for a stock Charta blob (no header), where the pixel <em>count</em>
 *       still fits a blob of any integer scale because the byte layout is size-agnostic. The real size is
 *       recovered from the length, which only works while the aspect ratio stays the base one.</li>
 * </ul>
 */
public final class ScaledImages {

    /** {@code "MYAV"}, the marker that says a blob carries a size header. */
    public static final int MAGIC = 0x4D594156;
    /** Four bytes of marker plus two big-endian ints. */
    public static final int HEADER_SIZE = 12;

    /** Refuse absurd sizes; 64x of a 25x35 card is 1600x2240, well past anything real. */
    private static final int MAX_SCALE = 64;
    private static final int MAX_DIMENSION = 1 << 14;

    private ScaledImages() {
    }

    /**
     * @return {@code {width, height}} when {@code data} starts with a {@link #MAGIC} header, else {@code null}.
     */
    @Nullable
    public static int[] readHeader(byte[] data) {
        if (data.length < HEADER_SIZE || readInt(data, 0) != MAGIC) {
            return null;
        }
        int width = readInt(data, 4);
        int height = readInt(data, 8);
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            return null;
        }
        return new int[]{width, height};
    }

    /**
     * Fills {@code out} with a {@link #MAGIC} header for the given size, so it can be prepended to a blob.
     */
    public static byte[] writeHeader(int width, int height) {
        byte[] out = new byte[HEADER_SIZE];
        writeInt(out, 0, MAGIC);
        writeInt(out, 4, width);
        writeInt(out, 8, height);
        return out;
    }

    /**
     * @return {@code {width, height}} for a blob of {@code length} bytes, or the base size as a fallback.
     */
    public static int[] guess(int length, int baseWidth, int baseHeight) {
        long area = (long) baseWidth * baseHeight;
        if (length > 0 && length % area == 0) {
            long factor = length / area;
            long scale = Math.round(Math.sqrt(factor));
            if (scale > 0 && scale <= MAX_SCALE && scale * scale == factor) {
                return new int[]{(int) (baseWidth * scale), (int) (baseHeight * scale)};
            }
        }
        return new int[]{baseWidth, baseHeight};
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }
}

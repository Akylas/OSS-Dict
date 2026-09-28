package itkach.aard2.dictionary.dsl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Random-access reader for dictzip files ({@code .dz}).
 *
 * <p>A dictzip file is a normal gzip file whose deflate stream is flushed
 * every {@code chunkLength} uncompressed bytes. The gzip header's extra field
 * holds an {@code RA} subfield listing each chunk's compressed size, so any
 * byte range can be read by inflating only the chunks that cover it.</p>
 *
 * <p>Format reference: the {@code dictzip(1)} man page (dictd project).</p>
 *
 * <p>Pure Java (no Android) so it can be unit tested on the JVM.</p>
 */
public final class DictZipReader {

    private static final int FLAG_HEADER_CRC = 0x02;
    private static final int FLAG_EXTRA = 0x04;
    private static final int FLAG_NAME = 0x08;
    private static final int FLAG_COMMENT = 0x10;

    @NonNull
    private final FileChannel channel;
    private final int chunkLength;
    /** File offset of each chunk, plus one final entry for the end of the last chunk. */
    @NonNull
    private final long[] chunkOffsets;

    // Last inflated chunk, since consecutive reads usually hit the same one.
    private int cachedChunk = -1;
    @Nullable
    private byte[] cachedData;
    private int cachedLength;

    private DictZipReader(@NonNull FileChannel channel, int chunkLength, @NonNull long[] chunkOffsets) {
        this.channel = channel;
        this.chunkLength = chunkLength;
        this.chunkOffsets = chunkOffsets;
    }

    /**
     * Returns a reader if the file is a dictzip file, or {@code null} if it is
     * a plain gzip file (no {@code RA} chunk table) or not gzip at all.
     */
    @Nullable
    public static DictZipReader open(@NonNull FileChannel channel) throws IOException {
        ByteBuffer fixed = readAt(channel, 0, 10);
        if (fixed.limit() < 10 || (fixed.get(0) & 0xFF) != 0x1F || (fixed.get(1) & 0xFF) != 0x8B
                || fixed.get(2) != 8) {
            return null;
        }
        int flags = fixed.get(3) & 0xFF;
        if ((flags & FLAG_EXTRA) == 0) return null;

        long position = 10;
        int extraLength = readAt(channel, position, 2).getShort(0) & 0xFFFF;
        position += 2;
        ByteBuffer extra = readAt(channel, position, extraLength);
        position += extraLength;

        int chunkLength = -1;
        int[] chunkSizes = null;
        int index = 0;
        while (index + 4 <= extra.limit()) {
            int id1 = extra.get(index) & 0xFF;
            int id2 = extra.get(index + 1) & 0xFF;
            int length = extra.getShort(index + 2) & 0xFFFF;
            int data = index + 4;
            if (id1 == 'R' && id2 == 'A' && length >= 6 && data + length <= extra.limit()) {
                int version = extra.getShort(data) & 0xFFFF;
                if (version != 1) throw new IOException("Unsupported dictzip version " + version);
                chunkLength = extra.getShort(data + 2) & 0xFFFF;
                int chunkCount = extra.getShort(data + 4) & 0xFFFF;
                if (6 + chunkCount * 2 > length) throw new IOException("Truncated dictzip chunk table");
                chunkSizes = new int[chunkCount];
                for (int chunk = 0; chunk < chunkCount; chunk++) {
                    chunkSizes[chunk] = extra.getShort(data + 6 + chunk * 2) & 0xFFFF;
                }
            }
            index = data + length;
        }
        if (chunkSizes == null || chunkLength <= 0) return null;

        if ((flags & FLAG_NAME) != 0) position = skipZeroTerminated(channel, position);
        if ((flags & FLAG_COMMENT) != 0) position = skipZeroTerminated(channel, position);
        if ((flags & FLAG_HEADER_CRC) != 0) position += 2;

        long[] offsets = new long[chunkSizes.length + 1];
        offsets[0] = position;
        for (int chunk = 0; chunk < chunkSizes.length; chunk++) {
            offsets[chunk + 1] = offsets[chunk] + chunkSizes[chunk];
        }
        return new DictZipReader(channel, chunkLength, offsets);
    }

    /** Reads {@code length} uncompressed bytes starting at uncompressed {@code offset}. */
    @NonNull
    public synchronized byte[] read(long offset, int length) throws IOException {
        byte[] result = new byte[length];
        int copied = 0;
        while (copied < length) {
            long position = offset + copied;
            int chunk = (int) (position / chunkLength);
            if (chunk >= chunkOffsets.length - 1) break;
            inflateChunk(chunk);
            int start = (int) (position - (long) chunk * chunkLength);
            if (start >= cachedLength) break;
            int count = Math.min(length - copied, cachedLength - start);
            System.arraycopy(cachedData, start, result, copied, count);
            copied += count;
        }
        if (copied < length) throw new IOException("Read past end of dictzip data");
        return result;
    }

    private void inflateChunk(int chunk) throws IOException {
        if (chunk == cachedChunk) return;
        long start = chunkOffsets[chunk];
        byte[] compressed = readAt(channel, start, (int) (chunkOffsets[chunk + 1] - start)).array();
        if (cachedData == null) cachedData = new byte[chunkLength];
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            int total = 0;
            while (total < chunkLength && !inflater.finished()) {
                int count = inflater.inflate(cachedData, total, chunkLength - total);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                total += count;
            }
            cachedLength = total;
            cachedChunk = chunk;
        } catch (DataFormatException e) {
            cachedChunk = -1;
            throw new IOException("Corrupt dictzip chunk " + chunk, e);
        } finally {
            inflater.end();
        }
    }

    private static long skipZeroTerminated(@NonNull FileChannel channel, long position) throws IOException {
        ByteBuffer one = ByteBuffer.allocate(1);
        while (true) {
            one.clear();
            if (channel.read(one, position++) < 0) throw new IOException("Truncated gzip header");
            if (one.get(0) == 0) return position;
        }
    }

    @NonNull
    private static ByteBuffer readAt(@NonNull FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) break;
        }
        buffer.flip();
        return buffer;
    }
}

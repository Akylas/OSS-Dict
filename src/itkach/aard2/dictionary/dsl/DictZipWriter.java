package itkach.aard2.dictionary.dsl;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes dictzip files readable by {@link DictZipReader} and by any gzip
 * reader.
 *
 * <p>Pure Java (no Android) so it can be unit tested on the JVM.</p>
 */
public final class DictZipWriter {

    /** Chunk length used by the {@code dictzip} tool. */
    public static final int DEFAULT_CHUNK_LENGTH = 58315;
    /** The gzip extra field is at most 65535 bytes: 10 bytes of headers, 2 per chunk. */
    private static final int MAX_CHUNKS = (0xFFFF - 10) / 2;
    private static final int BUFFER_SIZE = 65536;

    private DictZipWriter() {
    }

    /**
     * Compresses {@code input} into the dictzip file {@code output}, using
     * {@code workFile} for the compressed chunks while their sizes are not yet
     * known. {@code workFile} is deleted afterwards.
     *
     * @throws IOException if the input is too large for the chunk table
     *                     (about 1.9 GB with the default chunk length)
     */
    public static void write(@NonNull InputStream input, @NonNull File output, @NonNull File workFile,
                             int chunkLength) throws IOException {
        int[] chunkSizes = new int[64];
        int chunkCount = 0;
        CRC32 crc = new CRC32();
        long totalLength = 0;

        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (OutputStream chunks = new BufferedOutputStream(new FileOutputStream(workFile), BUFFER_SIZE)) {
            byte[] current = new byte[chunkLength];
            byte[] following = new byte[chunkLength];
            byte[] compressed = new byte[chunkLength + chunkLength / 10 + 64];
            int currentFilled = readChunk(input, current);
            while (true) {
                // Read ahead so the last chunk can be finished instead of flushed.
                int followingFilled = currentFilled == chunkLength ? readChunk(input, following) : 0;
                boolean last = followingFilled == 0;
                crc.update(current, 0, currentFilled);
                totalLength += currentFilled;
                deflater.setInput(current, 0, currentFilled);
                if (last) deflater.finish();
                int size = 0;
                while (true) {
                    int space = compressed.length - size;
                    if (space == 0) throw new IOException("dictzip chunk overflow");
                    int count = last
                            ? deflater.deflate(compressed, size, space)
                            : deflater.deflate(compressed, size, space, Deflater.FULL_FLUSH);
                    size += count;
                    if (last ? deflater.finished() : count < space) break;
                }
                if (size > 0xFFFF) throw new IOException("dictzip chunk too large: " + size);
                if (chunkCount == MAX_CHUNKS) throw new IOException("Input too large for dictzip");
                if (chunkCount == chunkSizes.length) {
                    int[] grown = new int[chunkSizes.length * 2];
                    System.arraycopy(chunkSizes, 0, grown, 0, chunkCount);
                    chunkSizes = grown;
                }
                chunkSizes[chunkCount++] = size;
                chunks.write(compressed, 0, size);
                if (last) break;
                byte[] swap = current;
                current = following;
                following = swap;
                currentFilled = followingFilled;
            }
        } finally {
            deflater.end();
        }

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(output), BUFFER_SIZE));
             InputStream chunks = new BufferedInputStream(new FileInputStream(workFile), BUFFER_SIZE)) {
            int subfieldLength = 6 + chunkCount * 2;
            out.write(new byte[]{0x1F, (byte) 0x8B, 8, 0x04, 0, 0, 0, 0, 0, (byte) 0xFF});
            writeShort(out, subfieldLength + 4);
            out.write('R');
            out.write('A');
            writeShort(out, subfieldLength);
            writeShort(out, 1);
            writeShort(out, chunkLength);
            writeShort(out, chunkCount);
            for (int chunk = 0; chunk < chunkCount; chunk++) writeShort(out, chunkSizes[chunk]);
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = chunks.read(buffer)) > 0) out.write(buffer, 0, count);
            writeInt(out, (int) crc.getValue());
            writeInt(out, (int) totalLength);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            workFile.delete();
        }
    }

    /** Fills {@code buffer} from {@code input}; returns bytes read, 0 at end of input. */
    private static int readChunk(@NonNull InputStream input, @NonNull byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int count = input.read(buffer, total, buffer.length - total);
            if (count < 0) break;
            total += count;
        }
        return total;
    }

    private static void writeShort(@NonNull DataOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(@NonNull DataOutputStream out, int value) throws IOException {
        writeShort(out, value & 0xFFFF);
        writeShort(out, (value >>> 16) & 0xFFFF);
    }
}

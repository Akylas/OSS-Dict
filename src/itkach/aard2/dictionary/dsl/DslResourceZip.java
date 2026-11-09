package itkach.aard2.dictionary.dsl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Random-access reader for a DSL media archive ({@code <name>.dsl.files.zip}).
 *
 * <p>Reads the zip central directory once, then reads single entries on
 * demand, so large media archives are never copied or extracted. Supports
 * stored and deflated entries, and ZIP64 archives (more than 65535 entries).</p>
 *
 * <p>Pure Java (no Android) so it can be unit tested on the JVM.</p>
 */
public final class DslResourceZip {

    private static final int END_OF_CENTRAL_DIRECTORY = 0x06054b50;
    private static final int CENTRAL_DIRECTORY_ENTRY = 0x02014b50;
    private static final int LOCAL_FILE_HEADER = 0x04034b50;
    private static final int ZIP64_END_RECORD = 0x06064b50;
    private static final int ZIP64_LOCATOR = 0x07064b50;
    private static final int ZIP64_LOCATOR_SIZE = 20;
    private static final int ZIP64_END_RECORD_SIZE = 56;
    private static final int ZIP64_EXTRA_FIELD = 0x0001;
    private static final int END_RECORD_SIZE = 22;
    private static final int MAX_COMMENT_SIZE = 0xFFFF;
    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;
    private static final int UTF8_NAME_FLAG = 1 << 11;

    private static final class ZipEntry {
        final long localHeaderOffset;
        final long compressedSize;
        final long size;
        final int method;

        ZipEntry(long localHeaderOffset, long compressedSize, long size, int method) {
            this.localHeaderOffset = localHeaderOffset;
            this.compressedSize = compressedSize;
            this.size = size;
            this.method = method;
        }
    }

    @NonNull
    private final FileChannel channel;
    @Nullable
    private Map<String, ZipEntry> entries;

    public DslResourceZip(@NonNull FileChannel channel) {
        this.channel = channel;
    }

    /**
     * Returns the bytes of the entry whose file name (ignoring directories and
     * case) matches {@code name}, or {@code null} if there is none.
     */
    @Nullable
    public synchronized byte[] read(@NonNull String name) throws IOException {
        if (entries == null) entries = readCentralDirectory();
        ZipEntry entry = entries.get(normalize(name));
        if (entry == null) return null;
        if (entry.compressedSize < 0 || entry.size < 0 || entry.localHeaderOffset < 0
                || entry.compressedSize > Integer.MAX_VALUE || entry.size > Integer.MAX_VALUE) {
            throw new IOException("Invalid entry size or offset: " + name);
        }

        ByteBuffer header = readAt(entry.localHeaderOffset, 30);
        if (header.getInt(0) != LOCAL_FILE_HEADER) throw new IOException("Bad local header for " + name);
        int nameLength = header.getShort(26) & 0xFFFF;
        int extraLength = header.getShort(28) & 0xFFFF;
        long dataOffset = entry.localHeaderOffset + 30 + nameLength + extraLength;
        if (dataOffset + entry.compressedSize > channel.size()) throw new IOException("Truncated entry " + name);
        byte[] compressed = readAt(dataOffset, (int) entry.compressedSize).array();
        if (entry.method == METHOD_STORED) return compressed;

        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            byte[] output = new byte[(int) entry.size];
            int total = 0;
            while (total < output.length && !inflater.finished()) {
                int count = inflater.inflate(output, total, output.length - total);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                total += count;
            }
            if (total != output.length) throw new IOException("Truncated entry " + name);
            return output;
        } catch (DataFormatException e) {
            throw new IOException("Corrupt entry " + name, e);
        } finally {
            inflater.end();
        }
    }

    @NonNull
    private Map<String, ZipEntry> readCentralDirectory() throws IOException {
        long fileSize = channel.size();
        int tailSize = (int) Math.min(fileSize, END_RECORD_SIZE + MAX_COMMENT_SIZE);
        ByteBuffer tail = readAt(fileSize - tailSize, tailSize);
        int endRecord = -1;
        for (int index = tailSize - END_RECORD_SIZE; index >= 0; index--) {
            if (tail.getInt(index) == END_OF_CENTRAL_DIRECTORY) {
                endRecord = index;
                break;
            }
        }
        if (endRecord < 0) throw new IOException("Not a zip archive");
        long entryCount = tail.getShort(endRecord + 10) & 0xFFFF;
        long directorySize = tail.getInt(endRecord + 12) & 0xFFFFFFFFL;
        long directoryOffset = tail.getInt(endRecord + 16) & 0xFFFFFFFFL;
        if (entryCount == 0xFFFF || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL) {
            long locatorOffset = fileSize - tailSize + endRecord - ZIP64_LOCATOR_SIZE;
            if (locatorOffset >= 0) {
                ByteBuffer locator = readAt(locatorOffset, ZIP64_LOCATOR_SIZE);
                if (locator.getInt(0) == ZIP64_LOCATOR) {
                    ByteBuffer record = readAt(locator.getLong(8), ZIP64_END_RECORD_SIZE);
                    if (record.getInt(0) != ZIP64_END_RECORD) throw new IOException("Bad ZIP64 end record");
                    entryCount = record.getLong(32);
                    directorySize = record.getLong(40);
                    directoryOffset = record.getLong(48);
                }
            }
        }
        if (entryCount < 0 || directoryOffset < 0 || directorySize < 0 || directorySize > Integer.MAX_VALUE
                || directoryOffset + directorySize > fileSize) {
            throw new IOException("Invalid central directory");
        }

        ByteBuffer directory = readAt(directoryOffset, (int) directorySize);
        try {
            return parseCentralDirectory(directory, entryCount);
        } catch (IndexOutOfBoundsException | BufferUnderflowException e) {
            throw new IOException("Corrupt central directory", e);
        }
    }

    /**
     * The directory is walked to its end rather than counted: some writers
     * store only the low 16 bits of the entry count of a large archive.
     */
    @NonNull
    private static Map<String, ZipEntry> parseCentralDirectory(@NonNull ByteBuffer directory, long entryCount)
            throws IOException {
        Map<String, ZipEntry> result = new HashMap<>((int) Math.min(entryCount, 1_000_000L) * 2);
        int position = 0;
        for (int count = 0; position + 46 <= directory.limit(); count++) {
            if (directory.getInt(position) != CENTRAL_DIRECTORY_ENTRY) {
                // Past the declared entries this is trailing data (e.g. a digital signature).
                if (count >= entryCount) break;
                throw new IOException("Bad central directory entry " + count);
            }
            int flags = directory.getShort(position + 8) & 0xFFFF;
            int method = directory.getShort(position + 10) & 0xFFFF;
            long compressedSize = directory.getInt(position + 20) & 0xFFFFFFFFL;
            long size = directory.getInt(position + 24) & 0xFFFFFFFFL;
            int nameLength = directory.getShort(position + 28) & 0xFFFF;
            int extraLength = directory.getShort(position + 30) & 0xFFFF;
            int commentLength = directory.getShort(position + 32) & 0xFFFF;
            long localHeaderOffset = directory.getInt(position + 42) & 0xFFFFFFFFL;
            if (size == 0xFFFFFFFFL || compressedSize == 0xFFFFFFFFL || localHeaderOffset == 0xFFFFFFFFL) {
                // Real values moved to the ZIP64 extra field, in this order, only for the
                // 32-bit fields that overflowed.
                int extra = position + 46 + nameLength;
                int extraEnd = extra + extraLength;
                while (extra + 4 <= extraEnd) {
                    int fieldId = directory.getShort(extra) & 0xFFFF;
                    int fieldLength = directory.getShort(extra + 2) & 0xFFFF;
                    if (fieldId == ZIP64_EXTRA_FIELD) {
                        int cursor = extra + 4;
                        if (size == 0xFFFFFFFFL) {
                            size = directory.getLong(cursor);
                            cursor += 8;
                        }
                        if (compressedSize == 0xFFFFFFFFL) {
                            compressedSize = directory.getLong(cursor);
                            cursor += 8;
                        }
                        if (localHeaderOffset == 0xFFFFFFFFL) {
                            localHeaderOffset = directory.getLong(cursor);
                        }
                        break;
                    }
                    extra += 4 + fieldLength;
                }
            }
            byte[] nameBytes = new byte[nameLength];
            directory.position(position + 46);
            directory.get(nameBytes);
            Charset nameCharset = (flags & UTF8_NAME_FLAG) != 0 ? StandardCharsets.UTF_8 : legacyNameCharset();
            String name = new String(nameBytes, nameCharset);
            if (!name.endsWith("/") && (method == METHOD_STORED || method == METHOD_DEFLATED)) {
                result.put(normalize(name), new ZipEntry(localHeaderOffset, compressedSize, size, method));
            }
            position += 46 + nameLength + extraLength + commentLength;
        }
        return result;
    }

    @NonNull
    private ByteBuffer readAt(long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, offset + buffer.position());
            if (count < 0) throw new IOException("Unexpected end of zip archive");
        }
        buffer.flip();
        return buffer;
    }

    /** Archive entries are matched by file name only, case-insensitively. */
    @NonNull
    static String normalize(@NonNull String name) {
        String path = name.replace('\\', '/');
        return path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

    /** Zip names without the UTF-8 flag are nominally CP437; plain ASCII in practice. */
    @NonNull
    private static Charset legacyNameCharset() {
        try {
            return Charset.forName("IBM437");
        } catch (IllegalArgumentException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}

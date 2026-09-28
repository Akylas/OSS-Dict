package itkach.aard2.dictionary.dsl;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import itkach.aard2.dictionary.Dictionary;
import itkach.aard2.dictionary.DictionaryContent;
import itkach.aard2.dictionary.DictionaryEntry;
import itkach.slob.Slob;

/**
 * Reads ABBYY Lingvo DSL dictionaries ({@code .dsl}, {@code .dsl.gz}, {@code .dsl.dz}).
 *
 * <p>A plain {@code .dsl} and a dictzip {@code .dsl.dz} are read in place
 * with random access. A plain gzip file (no dictzip chunk table) is converted
 * once to dictzip in the app's files directory. The headword index is built
 * on first load and cached, so later loads skip the full-file scan.</p>
 *
 * <p>Optional companions: a media archive ({@code .dsl.files.zip}), an
 * annotation ({@code .ann}) exposed as the {@code description} tag, and an
 * abbreviation dictionary ({@code _abrv.dsl}) used for {@code [p]} tooltips.</p>
 */
public final class DslDictionary implements Dictionary {
    private static final String TAG = DslDictionary.class.getSimpleName();

    private static final byte[] CACHE_MAGIC = new byte[]{'D', 'S', 'L', 'I', 'D', 'X', '\n', '\0'};
    /** Increment when the cache format or the parser output changes. */
    private static final int CACHE_VERSION = 1;
    private static final int BUFFER_SIZE = 65536;
    private static final int MAX_DESCRIPTION_LENGTH = 4000;
    private static final String CONTENT_TYPE_HTML = "text/html; charset=utf-8";
    private static final String TAG_DESCRIPTION = "description";

    @NonNull private final String id;
    @NonNull private final String filePath;
    @NonNull private final Map<String, String> tags;
    @NonNull private final Charset charset;
    private final int cardCount;

    /** Sorted headwords; the index into this list is the entry's blob id. */
    @NonNull private final List<String> keys;
    @NonNull private final long[] offsets;
    @NonNull private final int[] lengths;

    @NonNull private final CardSource source;
    @NonNull private Map<String, String> abbreviations = Collections.emptyMap();
    @Nullable private DslResourceZip resources;

    // Keep descriptors alive so GC finalizers don't close the channels in use.
    @NonNull private final List<Object> openHandles = new ArrayList<>();

    private DslDictionary(@NonNull String id, @NonNull String filePath, @NonNull Map<String, String> tags,
                          @NonNull Charset charset, int cardCount, @NonNull List<String> keys,
                          @NonNull long[] offsets, @NonNull int[] lengths, @NonNull CardSource source) {
        this.id = id;
        this.filePath = filePath;
        this.tags = tags;
        this.charset = charset;
        this.cardCount = cardCount;
        this.keys = keys;
        this.offsets = offsets;
        this.lengths = lengths;
        this.source = source;
    }

    /** Uncompressed DSL text, stored plain or as dictzip. */
    private interface CardSource {
        @NonNull
        byte[] read(long offset, int length) throws IOException;

        /** Streams the whole uncompressed text from the start. */
        @NonNull
        InputStream openStream() throws IOException;
    }

    @NonNull
    private static CardSource plainSource(@NonNull FileChannel channel) {
        return new CardSource() {
            @Override
            @NonNull
            public byte[] read(long offset, int length) throws IOException {
                ByteBuffer buffer = ByteBuffer.allocate(length);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer, offset + buffer.position()) < 0) break;
                }
                return buffer.position() == length ? buffer.array() : Arrays.copyOf(buffer.array(), buffer.position());
            }

            @Override
            @NonNull
            public InputStream openStream() throws IOException {
                channel.position(0);
                return new BufferedInputStream(Channels.newInputStream(channel), BUFFER_SIZE);
            }
        };
    }

    @NonNull
    private static CardSource dictZipSource(@NonNull FileChannel channel, @NonNull DictZipReader reader) {
        return new CardSource() {
            @Override
            @NonNull
            public byte[] read(long offset, int length) throws IOException {
                return reader.read(offset, length);
            }

            @Override
            @NonNull
            public InputStream openStream() throws IOException {
                channel.position(0);
                return new BufferedInputStream(
                        new GZIPInputStream(Channels.newInputStream(channel), BUFFER_SIZE), BUFFER_SIZE);
            }
        };
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    /**
     * Opens a DSL dictionary and its optional companion files.
     *
     * @param resourcesPath URI of the {@code .files.zip} media archive, or {@code null}
     * @param annPath       URI of the {@code .ann} annotation, or {@code null}
     * @param abbrevPath    URI of the {@code _abrv.dsl} abbreviations, or {@code null}
     */
    @NonNull
    public static DslDictionary fromUri(@NonNull Context context, @NonNull Uri uri, @NonNull String filePath,
                                        @Nullable String resourcesPath, @Nullable String annPath,
                                        @Nullable String abbrevPath) throws IOException {
        ParcelFileDescriptor sourceDescriptor = context.getContentResolver().openFileDescriptor(uri, "r");
        if (sourceDescriptor == null) throw new IOException("Cannot open: " + filePath);
        FileInputStream sourceStream = new FileInputStream(sourceDescriptor.getFileDescriptor());
        FileChannel sourceChannel = sourceStream.getChannel();
        long sourceSize = sourceChannel.size();

        List<Object> handles = new ArrayList<>();
        CardSource source;
        if (!isGzip(sourceChannel)) {
            source = plainSource(sourceChannel);
            handles.add(sourceDescriptor);
            handles.add(sourceStream);
        } else {
            DictZipReader reader = DictZipReader.open(sourceChannel);
            if (reader != null) {
                source = dictZipSource(sourceChannel, reader);
                handles.add(sourceDescriptor);
                handles.add(sourceStream);
            } else {
                // Plain gzip has no random access: convert it to dictzip once.
                File converted = convertOnce(context, filePath, sourceChannel, sourceSize);
                sourceDescriptor.close();
                FileInputStream convertedStream = new FileInputStream(converted);
                FileChannel convertedChannel = convertedStream.getChannel();
                reader = DictZipReader.open(convertedChannel);
                if (reader == null) throw new IOException("Invalid dictzip copy " + converted);
                source = dictZipSource(convertedChannel, reader);
                handles.add(convertedStream);
            }
        }

        File cache = cacheFile(context, filePath);
        DslDictionary dict = tryLoadFromCache(cache, source, filePath, sourceSize);
        if (dict == null) {
            dict = parse(source, filePath);
            saveToCache(cache, dict, sourceSize);
        }
        dict.openHandles.addAll(handles);

        if (annPath != null && !annPath.isEmpty()) {
            try {
                String annotation = DslParser.decode(readAll(context, Uri.parse(annPath))).trim();
                if (annotation.length() > MAX_DESCRIPTION_LENGTH) {
                    annotation = annotation.substring(0, MAX_DESCRIPTION_LENGTH);
                }
                if (!annotation.isEmpty()) dict.tags.put(TAG_DESCRIPTION, annotation);
            } catch (IOException e) {
                Log.w(TAG, "Failed to read annotation " + annPath, e);
            }
        }
        if (abbrevPath != null && !abbrevPath.isEmpty()) {
            try {
                dict.abbreviations = DslParser.parseAbbreviations(
                        DslParser.decode(readAll(context, Uri.parse(abbrevPath))));
            } catch (IOException e) {
                Log.w(TAG, "Failed to read abbreviations " + abbrevPath, e);
            }
        }
        if (resourcesPath != null && !resourcesPath.isEmpty()) {
            try {
                ParcelFileDescriptor zipDescriptor = context.getContentResolver()
                        .openFileDescriptor(Uri.parse(resourcesPath), "r");
                if (zipDescriptor != null) {
                    FileInputStream zipStream = new FileInputStream(zipDescriptor.getFileDescriptor());
                    dict.resources = new DslResourceZip(zipStream.getChannel());
                    dict.openHandles.add(zipDescriptor);
                    dict.openHandles.add(zipStream);
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to open media archive " + resourcesPath, e);
            }
        }
        return dict;
    }

    /** Scans the DSL file, then sorts the index with the lookup collation. */
    @NonNull
    private static DslDictionary parse(@NonNull CardSource source, @NonNull String filePath) throws IOException {
        DslParser.Result result;
        try (InputStream input = source.openStream()) {
            result = DslParser.parse(input);
        }

        List<DslParser.IndexEntry> entries = result.entries;
        Integer[] order = new Integer[entries.size()];
        for (int index = 0; index < order.length; index++) order[index] = index;
        final Slob.KeyComparator comparator = Slob.Strength.QUATERNARY.comparator;
        Arrays.sort(order, (first, second) -> comparator.compare(
                new Slob.Keyed(entries.get(first).key), new Slob.Keyed(entries.get(second).key)));

        List<String> keys = new ArrayList<>(order.length);
        long[] offsets = new long[order.length];
        int[] lengths = new int[order.length];
        for (int index = 0; index < order.length; index++) {
            DslParser.IndexEntry entry = entries.get(order[index]);
            keys.add(entry.key);
            offsets[index] = entry.offset;
            lengths[index] = entry.length;
        }
        Map<String, String> tags = new HashMap<>(result.tags);
        String id = deterministicUuid(filePath).toString();
        Log.d(TAG, "Parsed " + filePath + ": " + result.cardCount + " cards, " + keys.size() + " keys");
        return new DslDictionary(id, filePath, tags, result.charset, result.cardCount,
                keys, offsets, lengths, source);
    }

    // -----------------------------------------------------------------------
    // Dictionary interface
    // -----------------------------------------------------------------------

    @Override @NonNull public String getId() { return id; }

    @Override
    @NonNull
    public String getLabel() {
        String label = tags.get("label");
        return (label != null && !label.isEmpty()) ? label : shortName(filePath);
    }

    @Override
    @NonNull
    public String getUri() {
        String uri = tags.get("uri");
        return (uri != null && !uri.isEmpty()) ? uri : ("dsl:" + id);
    }

    @Override @NonNull public Map<String, String> getTags() { return Collections.unmodifiableMap(tags); }
    @Override public long getBlobCount() { return cardCount; }
    @Override public int size() { return keys.size(); }

    @Override
    @Nullable
    public DictionaryEntry get(int index) {
        if (index < 0 || index >= keys.size()) return null;
        return new DictionaryEntry(this, String.valueOf(index), keys.get(index), null);
    }

    @Override
    @NonNull
    public Iterator<DictionaryEntry> find(@NonNull String key, @NonNull Slob.Strength strength) {
        final int startIndex = findStartIndex(key, strength);
        final Slob.KeyComparator stopComparator = strength.stopComparator;
        final Slob.Keyed lookupKeyed = new Slob.Keyed(key);

        return new Iterator<DictionaryEntry>() {
            int index = startIndex;
            DictionaryEntry next = advance();

            private DictionaryEntry advance() {
                if (index >= keys.size()) return null;
                String candidate = keys.get(index);
                if (stopComparator.compare(new Slob.Keyed(candidate), lookupKeyed) != 0) return null;
                int currentIndex = index++;
                return new DictionaryEntry(DslDictionary.this, String.valueOf(currentIndex), candidate, null);
            }

            @Override public boolean hasNext() { return next != null; }

            @Override
            public DictionaryEntry next() {
                if (next == null) throw new NoSuchElementException();
                DictionaryEntry result = next;
                next = advance();
                return result;
            }
        };
    }

    @Override
    @Nullable
    public DictionaryContent getContent(@NonNull String blobId) {
        int index;
        try {
            index = Integer.parseInt(blobId);
        } catch (NumberFormatException e) {
            return getResourceContent(blobId);
        }
        if (index < 0 || index >= keys.size()) return null;
        try {
            String cardText = new String(source.read(offsets[index], lengths[index]), charset);
            String html = DslHtmlRenderer.render(DslParser.parseCard(cardText), abbreviations);
            return new DictionaryContent(CONTENT_TYPE_HTML, ByteBuffer.wrap(html.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            Log.e(TAG, "Failed to read card " + index + " of " + filePath, e);
            return null;
        }
    }

    @Override
    @NonNull
    public String getContentType(@NonNull String blobId) {
        try {
            Integer.parseInt(blobId);
            return CONTENT_TYPE_HTML;
        } catch (NumberFormatException e) {
            return guessMimeType(blobId);
        }
    }

    @Nullable
    private DictionaryContent getResourceContent(@NonNull String name) {
        if (resources == null) return null;
        try {
            byte[] data = resources.read(name);
            return data == null ? null : new DictionaryContent(guessMimeType(name), ByteBuffer.wrap(data));
        } catch (IOException e) {
            Log.w(TAG, "Failed to read media " + name + " from " + filePath, e);
            return null;
        }
    }

    private int findStartIndex(@NonNull String key, @NonNull Slob.Strength strength) {
        Slob.KeyComparator comparator = strength.comparator;
        Slob.Keyed lookupKeyed = new Slob.Keyed(key);
        int low = 0;
        int high = keys.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (comparator.compare(new Slob.Keyed(keys.get(middle)), lookupKeyed) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    // -----------------------------------------------------------------------
    // Compressed source
    // -----------------------------------------------------------------------

    private static boolean isGzip(@NonNull FileChannel sourceChannel) throws IOException {
        ByteBuffer magic = ByteBuffer.allocate(2);
        sourceChannel.read(magic, 0);
        return magic.position() == 2 && (magic.get(0) & 0xFF) == 0x1F && (magic.get(1) & 0xFF) == 0x8B;
    }

    /**
     * Converts a plain gzip {@code .dsl.gz} into a dictzip copy in the
     * persisted data directory, once per source size. Returns the copy.
     */
    @NonNull
    private static File convertOnce(@NonNull Context context, @NonNull String filePath,
                                    @NonNull FileChannel sourceChannel, long sourceSize) throws IOException {
        File directory = convertedDir(context, filePath);
        File converted = new File(directory, sourceSize + ".dsl.dz");
        if (converted.isFile()) return converted;

        deleteRecursively(directory);
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create " + directory);
        }
        File temporary = new File(directory, converted.getName() + ".tmp");
        File work = new File(directory, converted.getName() + ".chunks");
        sourceChannel.position(0);
        try (InputStream input = new GZIPInputStream(Channels.newInputStream(sourceChannel), BUFFER_SIZE)) {
            DictZipWriter.write(input, temporary, work, DictZipWriter.DEFAULT_CHUNK_LENGTH);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw e;
        }
        if (!temporary.renameTo(converted)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("Cannot rename " + temporary);
        }
        Log.d(TAG, "Converted " + filePath + " to dictzip " + converted);
        return converted;
    }

    @NonNull
    private static byte[] readAll(@NonNull Context context, @NonNull Uri uri) throws IOException {
        InputStream raw = context.getContentResolver().openInputStream(uri);
        if (raw == null) throw new IOException("Cannot open " + uri);
        try (InputStream input = new BufferedInputStream(raw, BUFFER_SIZE)) {
            input.mark(2);
            boolean gzip = input.read() == 0x1F && input.read() == 0x8B;
            input.reset();
            InputStream source = gzip ? new GZIPInputStream(input, BUFFER_SIZE) : input;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = source.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    // -----------------------------------------------------------------------
    // Persisted data
    // -----------------------------------------------------------------------

    @NonNull
    private static File baseDir(@NonNull Context context) {
        return new File(context.getFilesDir(), "dicts/dsl");
    }

    @NonNull
    private static String stableName(@NonNull String filePath) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < filePath.length(); index++) {
            hash ^= filePath.charAt(index);
            hash *= 0x100000001b3L;
        }
        return Long.toHexString(hash & Long.MAX_VALUE);
    }

    @NonNull
    private static File cacheFile(@NonNull Context context, @NonNull String filePath) {
        return new File(baseDir(context), stableName(filePath) + ".cache");
    }

    @NonNull
    private static File convertedDir(@NonNull Context context, @NonNull String filePath) {
        return new File(baseDir(context), stableName(filePath));
    }

    /** Deletes the index cache and any dictzip copy created for this dictionary. */
    public static void cleanupPersistedData(@NonNull Context context, @NonNull String filePath) {
        File cache = cacheFile(context, filePath);
        if (!cache.delete() && cache.exists()) {
            Log.w(TAG, "Could not delete DSL index cache: " + cache);
        }
        deleteRecursively(convertedDir(context, filePath));
    }

    private static void deleteRecursively(@NonNull File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete() && file.exists()) {
            Log.w(TAG, "Could not delete " + file);
        }
    }

    @Nullable
    private static DslDictionary tryLoadFromCache(@NonNull File cacheFile, @NonNull CardSource source,
                                                  @NonNull String filePath, long sourceSize) {
        if (!cacheFile.exists()) return null;
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(cacheFile), BUFFER_SIZE))) {
            byte[] magic = new byte[CACHE_MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(magic, CACHE_MAGIC)) return null;
            if ((input.readByte() & 0xFF) != CACHE_VERSION) return null;
            if (input.readLong() != sourceSize) {
                Log.d(TAG, "Index cache stale for " + filePath);
                return null;
            }
            Charset charset = Charset.forName(input.readUTF());
            int cardCount = input.readInt();
            int tagCount = input.readShort() & 0xFFFF;
            Map<String, String> tags = new HashMap<>(tagCount);
            for (int index = 0; index < tagCount; index++) tags.put(input.readUTF(), input.readUTF());
            String id = input.readUTF();
            int keyCount = input.readInt();
            List<String> keys = new ArrayList<>(keyCount);
            long[] offsets = new long[keyCount];
            int[] lengths = new int[keyCount];
            for (int index = 0; index < keyCount; index++) {
                keys.add(input.readUTF());
                offsets[index] = input.readLong();
                lengths[index] = input.readInt();
            }
            Log.d(TAG, "Loaded index from cache for " + filePath + " (" + keyCount + " keys)");
            return new DslDictionary(id, filePath, tags, charset, cardCount, keys, offsets, lengths, source);
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "Failed to load index cache for " + filePath, e);
            return null;
        }
    }

    private static void saveToCache(@NonNull File cacheFile, @NonNull DslDictionary dict, long sourceSize) {
        File parent = cacheFile.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) {
            Log.w(TAG, "Cannot create cache directory for " + cacheFile);
            return;
        }
        File temporary = new File(parent, cacheFile.getName() + ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temporary), BUFFER_SIZE))) {
                output.write(CACHE_MAGIC);
                output.writeByte(CACHE_VERSION);
                output.writeLong(sourceSize);
                output.writeUTF(dict.charset.name());
                output.writeInt(dict.cardCount);
                output.writeShort(dict.tags.size());
                for (Map.Entry<String, String> tag : dict.tags.entrySet()) {
                    output.writeUTF(tag.getKey());
                    output.writeUTF(tag.getValue());
                }
                output.writeUTF(dict.id);
                output.writeInt(dict.keys.size());
                for (int index = 0; index < dict.keys.size(); index++) {
                    output.writeUTF(dict.keys.get(index));
                    output.writeLong(dict.offsets[index]);
                    output.writeInt(dict.lengths[index]);
                }
            }
            if (!temporary.renameTo(cacheFile)) {
                Log.w(TAG, "Failed to rename cache temp file to " + cacheFile);
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to save index cache for " + dict.filePath, e);
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @NonNull
    private static String shortName(@NonNull String path) {
        String decoded = Uri.decode(path);
        String name = decoded.substring(Math.max(decoded.lastIndexOf('/'), decoded.lastIndexOf(':')) + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : new String[]{".dsl.gz", ".dsl.dz", ".dsl"}) {
            if (lower.endsWith(extension)) return name.substring(0, name.length() - extension.length());
        }
        return name;
    }

    @NonNull
    private static UUID deterministicUuid(@NonNull String filePath) {
        // Same derivation as the MDict/StarDict readers: stable across loads, not RFC 4122 v5.
        byte[] bytes = filePath.getBytes(StandardCharsets.UTF_8);
        long most = 0;
        long least = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (index < 8) most = (most << 8) | (bytes[index] & 0xFF);
            else least = (least << 8) | (bytes[index % 8] & 0xFF);
        }
        most ^= filePath.hashCode();
        least ^= filePath.hashCode() * 0x9e3779b97f4a7c15L;
        most = (most & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000005000L;
        least = (least & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    @NonNull
    private static String guessMimeType(@NonNull String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".spx")) return "audio/x-speex+ogg";
        return "application/octet-stream";
    }
}

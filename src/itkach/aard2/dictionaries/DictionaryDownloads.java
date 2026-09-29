package itkach.aard2.dictionaries;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-Java helpers for dictionary downloads started from the in-app browser.
 * Kept free of Android framework calls so it can be unit tested.
 */
public final class DictionaryDownloads {

    /** Extensions of files the app can load, or attach to a dictionary as a companion. */
    private static final String[] DICTIONARY_EXTENSIONS = {
            ".slob",
            ".mdx", ".mdd",
            ".zip",
            ".dsl", ".dsl.gz", ".dsl.dz", ".ann",
            ".ifo", ".idx", ".idx.gz", ".dict", ".dict.dz", ".syn",
    };

    /** Extensions of archives whose dictionary files get extracted on install. */
    private static final String[] ARCHIVE_EXTENSIONS = {
            ".zip", ".7z",
            ".tar", ".tar.gz", ".tgz", ".tar.xz", ".txz", ".tar.bz2", ".tbz2",
    };

    /** DSL media archive: loaded as is, next to its dictionary. */
    private static final String DSL_RESOURCES_EXTENSION = ".files.zip";

    private static final Pattern EXTENDED_FILENAME = Pattern.compile(
            "filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_FILENAME = Pattern.compile(
            "filename\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAIN_FILENAME = Pattern.compile(
            "filename\\s*=\\s*([^;\\s]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9A-Fa-f]{2}");

    private static final String DEFAULT_FILE_NAME = "download";

    private DictionaryDownloads() {
    }

    /**
     * Resolves the name of a downloaded file from its {@code Content-Disposition} header,
     * falling back to the last segment of the URL path.
     */
    @NonNull
    public static String fileName(@NonNull String url, @Nullable String contentDisposition) {
        String name = null;
        if (contentDisposition != null) {
            name = fromContentDisposition(contentDisposition);
        }
        if (name == null || name.isEmpty()) {
            name = fromUrl(url);
        }
        name = sanitize(name);
        return name.isEmpty() ? DEFAULT_FILE_NAME : name;
    }

    /**
     * Whether a downloaded file with this name can be installed: a dictionary file, or an
     * archive whose dictionary files get extracted.
     */
    public static boolean isSupported(@NonNull String fileName) {
        return isDictionaryFile(fileName) || isArchive(fileName);
    }

    /** Whether the app can load this file, or attach it to a dictionary as a companion. */
    public static boolean isDictionaryFile(@NonNull String fileName) {
        return endsWithAny(fileName, DICTIONARY_EXTENSIONS);
    }

    /**
     * Whether this file is an archive to extract on install. A {@code .zip} counts as one:
     * dictionary sites pack DSL, StarDict or MDict files in them, and a StarDict archive
     * extracted gives the same dictionary. DSL media archives are kept as is.
     */
    public static boolean isArchive(@NonNull String fileName) {
        return endsWithAny(fileName, ARCHIVE_EXTENSIONS)
                && !fileName.toLowerCase(Locale.ROOT).endsWith(DSL_RESOURCES_EXTENSION);
    }

    private static boolean endsWithAny(@NonNull String fileName, @NonNull String[] extensions) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String fromContentDisposition(@NonNull String contentDisposition) {
        Matcher extended = EXTENDED_FILENAME.matcher(contentDisposition);
        if (extended.find()) {
            String charset = extended.group(1).trim();
            String decoded = decode(extended.group(2).trim(), charset.isEmpty() ? "UTF-8" : charset);
            if (decoded != null) {
                return decoded;
            }
        }
        Matcher quoted = QUOTED_FILENAME.matcher(contentDisposition);
        if (quoted.find()) {
            return decodeLegacy(quoted.group(1));
        }
        Matcher plain = PLAIN_FILENAME.matcher(contentDisposition);
        if (plain.find()) {
            return decodeLegacy(plain.group(1));
        }
        return null;
    }

    /**
     * Some servers (e.g. Nextcloud WebDAV) percent-encode the legacy {@code filename} parameter;
     * browsers decode it, so do the same when it holds escapes.
     */
    @NonNull
    private static String decodeLegacy(@NonNull String name) {
        if (!PERCENT_ESCAPE.matcher(name).find()) {
            return name;
        }
        String decoded = decode(name, "UTF-8");
        return decoded != null ? decoded : name;
    }

    @NonNull
    private static String fromUrl(@NonNull String url) {
        String path = url;
        int queryStart = indexOfAny(path, '?', '#');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        int lastSlash = path.lastIndexOf('/');
        String segment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        String decoded = decode(segment, "UTF-8");
        return decoded != null ? decoded : segment;
    }

    @Nullable
    private static String decode(@NonNull String value, @NonNull String charset) {
        try {
            // URLDecoder turns '+' into a space, which is wrong for URL paths
            return URLDecoder.decode(value.replace("+", "%2B"), charset);
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }

    @NonNull
    static String sanitize(@NonNull String name) {
        String trimmed = name.trim();
        int lastSeparator = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            trimmed = trimmed.substring(lastSeparator + 1);
        }
        if (trimmed.equals(".") || trimmed.equals("..")) {
            return "";
        }
        return trimmed;
    }

    private static int indexOfAny(@NonNull String value, char first, char second) {
        int firstIndex = value.indexOf(first);
        int secondIndex = value.indexOf(second);
        if (firstIndex < 0) return secondIndex;
        if (secondIndex < 0) return firstIndex;
        return Math.min(firstIndex, secondIndex);
    }
}

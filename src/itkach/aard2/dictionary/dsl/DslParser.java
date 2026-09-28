package itkach.aard2.dictionary.dsl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses ABBYY Lingvo DSL source files.
 *
 * <p>Pure Java (no Android, no slob) so it can be unit tested on the JVM.</p>
 *
 * <p>A DSL file is a header of {@code #DIRECTIVE "value"} lines followed by
 * cards. A card is one or more headword lines (starting at column 0) followed
 * by body lines (starting with a space or a tab). {@code {{…}}} comments may
 * span lines.</p>
 *
 * <p>Format reference:
 * http://lingvo.helpmax.net/en/troubleshooting/dsl-compiler/dsl-dictionary-structure/</p>
 */
public final class DslParser {

    public static final String TAG_INDEX_LANGUAGE = "index_language";
    public static final String TAG_CONTENTS_LANGUAGE = "contents_language";

    /** Headword variants from {@code (optional)} parts are capped to this many. */
    private static final int MAX_VARIANTS = 16;
    private static final int HEAD_SIZE = 4096;

    private DslParser() {
    }

    /** One index key pointing at the byte range of its card in the source file. */
    public static final class IndexEntry {
        @NonNull
        public final String key;
        public final long offset;
        public final int length;

        IndexEntry(@NonNull String key, long offset, int length) {
            this.key = key;
            this.offset = offset;
            this.length = length;
        }
    }

    /** Result of a full-file parse. */
    public static final class Result {
        @NonNull
        public final Map<String, String> tags;
        @NonNull
        public final Charset charset;
        @NonNull
        public final List<IndexEntry> entries;
        public final int cardCount;

        Result(@NonNull Map<String, String> tags, @NonNull Charset charset,
               @NonNull List<IndexEntry> entries, int cardCount) {
            this.tags = tags;
            this.charset = charset;
            this.entries = entries;
            this.cardCount = cardCount;
        }
    }

    /** A card split into raw headword lines and raw body lines (leading indent removed). */
    public static final class Card {
        @NonNull
        public final List<String> headwords = new ArrayList<>();
        @NonNull
        public final List<String> body = new ArrayList<>();
    }

    /** Detected text encoding and the length of its byte order mark. */
    public static final class Encoding {
        @NonNull
        public final Charset charset;
        public final int bomLength;

        Encoding(@NonNull Charset charset, int bomLength) {
            this.charset = charset;
            this.bomLength = bomLength;
        }

        boolean isUtf16() {
            return charset.equals(StandardCharsets.UTF_16LE) || charset.equals(StandardCharsets.UTF_16BE);
        }
    }

    // -----------------------------------------------------------------------
    // Encoding
    // -----------------------------------------------------------------------

    /**
     * Detects the encoding from the first bytes of a file: byte order mark
     * first, then a zero-byte heuristic for BOM-less UTF-16, else UTF-8.
     * {@code #SOURCE_CODE_PAGE} is applied later, while reading the header.
     */
    @NonNull
    public static Encoding detectEncoding(@NonNull byte[] head, int length) {
        if (length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return new Encoding(StandardCharsets.UTF_16LE, 2);
        }
        if (length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return new Encoding(StandardCharsets.UTF_16BE, 2);
        }
        if (length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return new Encoding(StandardCharsets.UTF_8, 3);
        }
        int evenZeros = 0;
        int oddZeros = 0;
        int pairs = length / 2;
        for (int index = 0; index + 1 < length; index += 2) {
            if (head[index] == 0) evenZeros++;
            if (head[index + 1] == 0) oddZeros++;
        }
        if (pairs > 0 && oddZeros * 10 > pairs * 3) {
            return new Encoding(StandardCharsets.UTF_16LE, 0);
        }
        if (pairs > 0 && evenZeros * 10 > pairs * 3) {
            return new Encoding(StandardCharsets.UTF_16BE, 0);
        }
        return new Encoding(StandardCharsets.UTF_8, 0);
    }

    /** Maps a {@code #SOURCE_CODE_PAGE} name to a charset, or {@code null} if unknown. */
    @Nullable
    static Charset codePageCharset(@NonNull String name) {
        String codePageName;
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "latin":
                codePageName = "windows-1252";
                break;
            case "cyrillic":
                codePageName = "windows-1251";
                break;
            case "easterneuropean":
                codePageName = "windows-1250";
                break;
            default:
                return null;
        }
        try {
            return Charset.forName(codePageName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Decodes a whole small DSL-family file ({@code .ann}, {@code _abrv.dsl})
     * using BOM / UTF-16 detection and {@code #SOURCE_CODE_PAGE}.
     */
    @NonNull
    public static String decode(@NonNull byte[] data) {
        Encoding encoding = detectEncoding(data, Math.min(data.length, HEAD_SIZE));
        String text = new String(data, encoding.bomLength, data.length - encoding.bomLength, encoding.charset);
        if (!encoding.isUtf16() && encoding.bomLength == 0) {
            for (String line : text.split("\n", 64)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#SOURCE_CODE_PAGE")) {
                    Charset charset = codePageCharset(unquote(trimmed.substring("#SOURCE_CODE_PAGE".length())));
                    if (charset != null) return new String(data, charset);
                }
            }
        }
        return text;
    }

    // -----------------------------------------------------------------------
    // Full-file parse
    // -----------------------------------------------------------------------

    /**
     * Streams a DSL file and returns its header tags and index entries. Entry
     * offsets are absolute byte positions in the stream, so a card can later be
     * read back with random access and decoded with {@link Result#charset}.
     */
    @NonNull
    public static Result parse(@NonNull InputStream input) throws IOException {
        byte[] buffer = new byte[65536];
        int headLength = readFully(input, buffer, HEAD_SIZE);
        Encoding encoding = detectEncoding(buffer, headLength);
        Charset charset = encoding.charset;
        boolean utf16 = encoding.isUtf16();
        boolean bigEndian = charset.equals(StandardCharsets.UTF_16BE);
        boolean codePageAllowed = !utf16 && encoding.bomLength == 0;

        FileState state = new FileState();
        byte[] line = new byte[256];
        int lineLength = 0;
        long position = encoding.bomLength;
        long lineStart = position;
        int bufferStart = encoding.bomLength;
        int bufferEnd = headLength;

        while (true) {
            for (int index = bufferStart; index < bufferEnd; index++) {
                byte value = buffer[index];
                if (lineLength == line.length) {
                    byte[] grown = new byte[line.length * 2];
                    System.arraycopy(line, 0, grown, 0, lineLength);
                    line = grown;
                }
                line[lineLength++] = value;
                position++;
                boolean endOfLine;
                if (utf16) {
                    endOfLine = (lineLength & 1) == 0
                            && (bigEndian
                            ? line[lineLength - 2] == 0 && line[lineLength - 1] == 0x0A
                            : line[lineLength - 2] == 0x0A && line[lineLength - 1] == 0);
                } else {
                    endOfLine = value == 0x0A;
                }
                if (endOfLine) {
                    int contentLength = lineLength - (utf16 ? 2 : 1);
                    String text = new String(line, 0, contentLength, charset);
                    Charset switched = state.onLine(text, lineStart, position, codePageAllowed);
                    if (switched != null) {
                        charset = switched;
                        codePageAllowed = false;
                    }
                    lineLength = 0;
                    lineStart = position;
                }
            }
            bufferEnd = input.read(buffer, 0, buffer.length);
            bufferStart = 0;
            if (bufferEnd < 0) break;
        }
        if (lineLength > 0) {
            state.onLine(new String(line, 0, lineLength, charset), lineStart, position, false);
        }
        state.finishCard();
        return new Result(state.tags, charset, state.entries, state.cardCount);
    }

    private static int readFully(@NonNull InputStream input, @NonNull byte[] buffer, int wanted) throws IOException {
        int total = 0;
        while (total < wanted) {
            int count = input.read(buffer, total, wanted - total);
            if (count < 0) break;
            total += count;
        }
        return total;
    }

    /** Line-by-line state machine shared by the streaming parse. */
    private static final class FileState extends CommentState {
        final Map<String, String> tags = new HashMap<>();
        final List<IndexEntry> entries = new ArrayList<>();
        int cardCount;

        boolean seenCard;
        final List<String> headwords = new ArrayList<>();
        boolean inBody;
        long cardStart = -1;
        long cardEnd;

        /** Returns a charset to switch to when a {@code #SOURCE_CODE_PAGE} directive is read. */
        @Nullable
        Charset onLine(@NonNull String rawLine, long lineStart, long lineEnd, boolean codePageAllowed) {
            String line = stripComments(rawLine, this);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.trim().isEmpty()) return null;

            char first = line.charAt(0);
            if (first == '\uFEFF') {
                line = line.substring(1);
                if (line.isEmpty()) return null;
                first = line.charAt(0);
            }
            if (!seenCard && first == '#') {
                return onDirective(line, codePageAllowed);
            }
            if (first == ' ' || first == '\t') {
                if (cardStart >= 0) {
                    inBody = true;
                    cardEnd = lineEnd;
                }
                return null;
            }
            if (inBody) finishCard();
            if (cardStart < 0) cardStart = lineStart;
            seenCard = true;
            headwords.add(line);
            cardEnd = lineEnd;
            return null;
        }

        @Nullable
        Charset onDirective(@NonNull String line, boolean codePageAllowed) {
            int split = 1;
            while (split < line.length() && !Character.isWhitespace(line.charAt(split))) split++;
            String name = line.substring(1, split).toUpperCase(Locale.ROOT);
            String value = unquote(line.substring(split));
            switch (name) {
                case "NAME":
                    tags.put("label", value);
                    break;
                case "INDEX_LANGUAGE":
                    tags.put(TAG_INDEX_LANGUAGE, value);
                    break;
                case "CONTENTS_LANGUAGE":
                    tags.put(TAG_CONTENTS_LANGUAGE, value);
                    break;
                case "SOURCE_CODE_PAGE":
                    return codePageAllowed ? codePageCharset(value) : null;
                default:
                    // #INCLUDE and unknown directives are not supported.
                    break;
            }
            return null;
        }

        void finishCard() {
            if (cardStart >= 0 && !headwords.isEmpty()) {
                int length = (int) (cardEnd - cardStart);
                Set<String> keys = new LinkedHashSet<>();
                for (String headword : headwords) {
                    keys.addAll(indexKeys(headword));
                }
                for (String key : keys) {
                    entries.add(new IndexEntry(key, cardStart, length));
                }
                cardCount++;
            }
            headwords.clear();
            inBody = false;
            cardStart = -1;
        }
    }

    // -----------------------------------------------------------------------
    // Card text
    // -----------------------------------------------------------------------

    /**
     * Splits decoded DSL text into cards. Header directives are skipped. Used
     * to re-read a single card for rendering and to load small companion
     * files such as {@code _abrv.dsl}.
     */
    @NonNull
    public static List<Card> parseCards(@NonNull String text) {
        List<Card> cards = new ArrayList<>();
        CommentState commentState = new CommentState();
        Card current = null;
        boolean seenCard = false;
        for (String rawLine : text.split("\n", -1)) {
            String line = stripComments(rawLine, commentState);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
            if (line.trim().isEmpty()) continue;
            char first = line.charAt(0);
            if (!seenCard && first == '#') continue;
            if (first == ' ' || first == '\t') {
                if (current != null) current.body.add(line.trim());
                continue;
            }
            if (current == null || !current.body.isEmpty()) {
                current = new Card();
                cards.add(current);
            }
            seenCard = true;
            current.headwords.add(line);
        }
        return cards;
    }

    /** Parses exactly one card's text; returns an empty card if none is found. */
    @NonNull
    public static Card parseCard(@NonNull String text) {
        List<Card> cards = parseCards(text);
        return cards.isEmpty() ? new Card() : cards.get(0);
    }

    /**
     * Reads an abbreviation dictionary ({@code _abrv.dsl}) into a map of
     * abbreviation to its plain-text meaning.
     */
    @NonNull
    public static Map<String, String> parseAbbreviations(@NonNull String text) {
        Map<String, String> abbreviations = new HashMap<>();
        for (Card card : parseCards(text)) {
            StringBuilder meaning = new StringBuilder();
            for (String line : card.body) {
                String plain = plainText(line);
                if (plain.isEmpty()) continue;
                if (meaning.length() > 0) meaning.append("; ");
                meaning.append(plain);
            }
            if (meaning.length() == 0) continue;
            for (String headword : card.headwords) {
                String abbreviation = tildeText(headword);
                if (!abbreviation.isEmpty()) abbreviations.put(abbreviation, meaning.toString());
            }
        }
        return abbreviations;
    }

    /** Strips tags, braces and escapes from a line of DSL markup. */
    @NonNull
    static String plainText(@NonNull String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\\' && index + 1 < line.length()) {
                out.append(line.charAt(++index));
            } else if (character == '[') {
                int close = line.indexOf(']', index);
                if (close < 0) out.append(character);
                else index = close;
            } else if (character != '{' && character != '}') {
                out.append(character);
            }
        }
        return collapseWhitespace(out.toString());
    }

    private static class CommentState {
        boolean inComment;
    }

    /** Removes {@code {{…}}} comments, tracking comments that span lines. */
    @NonNull
    static String stripComments(@NonNull String line, @NonNull CommentState state) {
        boolean inComment = state.inComment;
        if (!inComment && line.indexOf("{{") < 0) return line;
        StringBuilder out = new StringBuilder(line.length());
        int index = 0;
        while (index < line.length()) {
            char current = line.charAt(index);
            if (inComment) {
                if (current == '}' && index + 1 < line.length() && line.charAt(index + 1) == '}') {
                    inComment = false;
                    index += 2;
                } else {
                    index++;
                }
                continue;
            }
            if (current == '\\' && index + 1 < line.length()) {
                out.append(current).append(line.charAt(index + 1));
                index += 2;
                continue;
            }
            if (current == '{' && index + 1 < line.length() && line.charAt(index + 1) == '{') {
                inComment = true;
                index += 2;
                continue;
            }
            out.append(current);
            index++;
        }
        state.inComment = inComment;
        return out.toString();
    }

    // -----------------------------------------------------------------------
    // Headwords
    // -----------------------------------------------------------------------

    /**
     * Returns the lookup keys of a raw headword line: {@code {unsorted}} parts
     * and tags removed, {@code (optional)} parts expanded into every variant,
     * escapes resolved and whitespace collapsed.
     */
    @NonNull
    public static List<String> indexKeys(@NonNull String rawHeadword) {
        // Split into alternating fixed / optional parts.
        List<String> parts = new ArrayList<>();
        List<Boolean> optional = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inOptional = false;
        int braceDepth = 0;
        int index = 0;
        while (index < rawHeadword.length()) {
            char character = rawHeadword.charAt(index);
            if (character == '\\' && index + 1 < rawHeadword.length()) {
                if (braceDepth == 0) current.append(rawHeadword.charAt(index + 1));
                index += 2;
                continue;
            }
            if (character == '{') {
                braceDepth++;
            } else if (character == '}') {
                if (braceDepth > 0) braceDepth--;
            } else if (braceDepth > 0) {
                index++;  // unsorted part: not indexed
                continue;
            } else if (character == '[') {
                int close = rawHeadword.indexOf(']', index);
                if (close < 0) {
                    current.append(character);
                } else {
                    index = close;
                }
            } else if (character == '(' && !inOptional) {
                parts.add(current.toString());
                optional.add(false);
                current.setLength(0);
                inOptional = true;
            } else if (character == ')' && inOptional) {
                parts.add(current.toString());
                optional.add(true);
                current.setLength(0);
                inOptional = false;
            } else {
                current.append(character);
            }
            index++;
        }
        parts.add(current.toString());
        optional.add(false);

        int optionalCount = 0;
        for (Boolean isOptional : optional) {
            if (isOptional) optionalCount++;
        }
        Set<String> keys = new LinkedHashSet<>();
        if (optionalCount == 0 || (1 << Math.min(optionalCount, 30)) > MAX_VARIANTS) {
            // No optional parts, or too many: index "all included" and "all excluded".
            keys.add(joinParts(parts, optional, -1));
            if (optionalCount > 0) keys.add(joinParts(parts, optional, 0));
        } else {
            for (int mask = (1 << optionalCount) - 1; mask >= 0; mask--) {
                keys.add(joinParts(parts, optional, mask));
            }
        }
        keys.remove("");
        return new ArrayList<>(keys);
    }

    @NonNull
    private static String joinParts(@NonNull List<String> parts, @NonNull List<Boolean> optional, int mask) {
        StringBuilder out = new StringBuilder();
        int optionalIndex = 0;
        for (int index = 0; index < parts.size(); index++) {
            if (optional.get(index)) {
                boolean include = (mask & (1 << optionalIndex)) != 0;
                optionalIndex++;
                if (!include) continue;
            }
            out.append(parts.get(index));
        }
        return collapseWhitespace(out.toString());
    }

    /**
     * Headword text used to replace {@code ~}: unsorted {@code {…}} parts
     * removed, optional parentheses dropped but their content kept.
     */
    @NonNull
    public static String tildeText(@NonNull String rawHeadword) {
        StringBuilder out = new StringBuilder();
        int braceDepth = 0;
        for (int index = 0; index < rawHeadword.length(); index++) {
            char character = rawHeadword.charAt(index);
            if (character == '\\' && index + 1 < rawHeadword.length()) {
                if (braceDepth == 0) out.append(rawHeadword.charAt(index + 1));
                index++;
            } else if (character == '{') {
                braceDepth++;
            } else if (character == '}') {
                if (braceDepth > 0) braceDepth--;
            } else if (braceDepth == 0 && character != '(' && character != ')') {
                out.append(character);
            }
        }
        return collapseWhitespace(out.toString());
    }

    @NonNull
    private static String collapseWhitespace(@NonNull String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    @NonNull
    static String unquote(@NonNull String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"') {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}

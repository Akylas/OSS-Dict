package itkach.aard2.dictionary.dsl;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Unit tests for {@link DslParser}.
 *
 * <p>Fixtures under {@code src/test/resources/itkach/aard2/dictionary/dsl/}:
 * {@code test.dsl} (UTF-16LE with BOM, CRLF), {@code test.dsl.gz} (same text,
 * UTF-8 without BOM) and {@code test_abrv.dsl}. The dictionary has 4 cards:
 * "Africa"/"Afrique", "{to }have", "(re)turn(s)" and "Straße \[escaped\]".</p>
 */
public class DslParserTest {

    private static final String RES_DIR = "/itkach/aard2/dictionary/dsl/";

    // ── Encoding ─────────────────────────────────────────────────────────────

    @Test
    public void detectsByteOrderMarks() {
        assertEncoding(new byte[]{(byte) 0xFF, (byte) 0xFE, '#', 0}, StandardCharsets.UTF_16LE, 2);
        assertEncoding(new byte[]{(byte) 0xFE, (byte) 0xFF, 0, '#'}, StandardCharsets.UTF_16BE, 2);
        assertEncoding(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '#'}, StandardCharsets.UTF_8, 3);
    }

    @Test
    public void detectsUtf16WithoutByteOrderMark() {
        assertEncoding("#NAME \"x\"".getBytes(StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE, 0);
        assertEncoding("#NAME \"x\"".getBytes(StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE, 0);
        assertEncoding("#NAME \"x\"".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, 0);
    }

    private static void assertEncoding(byte[] head, Charset expected, int bomLength) {
        DslParser.Encoding encoding = DslParser.detectEncoding(head, head.length);
        assertEquals(expected, encoding.charset);
        assertEquals(bomLength, encoding.bomLength);
    }

    @Test
    public void appliesSourceCodePage() throws IOException {
        Charset cyrillic = Charset.forName("windows-1251");
        byte[] data = "#NAME \"Тест\"\n#SOURCE_CODE_PAGE \"Cyrillic\"\n\nслово\n\tword\n".getBytes(cyrillic);
        DslParser.Result result = DslParser.parse(new ByteArrayInputStream(data));
        assertEquals(cyrillic, result.charset);
        assertEquals(1, result.cardCount);
        assertEquals("слово", result.entries.get(0).key);
        assertEquals("слово\n\tword\n", decodeCard(data, result, result.entries.get(0)));
    }

    @Test
    public void decodeHandlesByteOrderMark() {
        byte[] data = concat(new byte[]{(byte) 0xFF, (byte) 0xFE}, "Annotation".getBytes(StandardCharsets.UTF_16LE));
        assertEquals("Annotation", DslParser.decode(data));
    }

    // ── Full parse ───────────────────────────────────────────────────────────

    @Test
    public void parsesUtf16Fixture() throws IOException {
        byte[] data = readResource("test.dsl");
        DslParser.Result result = DslParser.parse(new ByteArrayInputStream(data));
        assertEquals(StandardCharsets.UTF_16LE, result.charset);
        assertFixture(data, result);
    }

    @Test
    public void parsesGzipUtf8Fixture() throws IOException {
        byte[] data = gunzip(readResource("test.dsl.gz"));
        DslParser.Result result = DslParser.parse(new ByteArrayInputStream(data));
        assertEquals(StandardCharsets.UTF_8, result.charset);
        assertFixture(data, result);
    }

    private static void assertFixture(byte[] data, DslParser.Result result) {
        assertEquals("Test DSL", result.tags.get("label"));
        assertEquals("English", result.tags.get(DslParser.TAG_INDEX_LANGUAGE));
        assertEquals("French", result.tags.get(DslParser.TAG_CONTENTS_LANGUAGE));
        assertEquals(4, result.cardCount);

        Set<String> keys = new HashSet<>();
        for (DslParser.IndexEntry entry : result.entries) keys.add(entry.key);
        assertEquals(new HashSet<>(Arrays.asList("Africa", "Afrique", "have",
                "returns", "return", "turns", "turn", "Straße [escaped]")), keys);

        // Every entry's byte range decodes back to its own card.
        for (DslParser.IndexEntry entry : result.entries) {
            DslParser.Card card = DslParser.parseCard(decodeCard(data, result, entry));
            List<String> cardKeys = new ArrayList<>();
            for (String headword : card.headwords) cardKeys.addAll(DslParser.indexKeys(headword));
            assertTrue(entry.key + " not in its card " + card.headwords, cardKeys.contains(entry.key));
            assertFalse(card.body.isEmpty());
        }
    }

    @Test
    public void multiHeadwordCardSharesOneRange() throws IOException {
        byte[] data = readResource("test.dsl");
        DslParser.Result result = DslParser.parse(new ByteArrayInputStream(data));
        DslParser.IndexEntry africa = findEntry(result, "Africa");
        DslParser.IndexEntry afrique = findEntry(result, "Afrique");
        assertEquals(africa.offset, afrique.offset);
        assertEquals(africa.length, afrique.length);
        DslParser.Card card = DslParser.parseCard(decodeCard(data, result, africa));
        assertEquals(Arrays.asList("Africa", "Afrique"), card.headwords);
        assertEquals(Arrays.asList("[m1]Africa[/m]", "[m1]Afrique[/m]"), card.body);
    }

    @Test
    public void lastCardWithoutTrailingNewlineIsIndexed() throws IOException {
        byte[] data = "#NAME \"x\"\nword\n\tbody".getBytes(StandardCharsets.UTF_8);
        DslParser.Result result = DslParser.parse(new ByteArrayInputStream(data));
        assertEquals(1, result.cardCount);
        assertEquals("word\n\tbody", decodeCard(data, result, result.entries.get(0)));
    }

    // ── Headwords ────────────────────────────────────────────────────────────

    @Test
    public void unsortedPartIsNotIndexed() {
        assertEquals(Arrays.asList("have"), DslParser.indexKeys("{to }have"));
        assertEquals(Arrays.asList("CO2-Laser"), DslParser.indexKeys("CO{[sub]}2{[/sub]}-Laser"));
    }

    @Test
    public void optionalPartsExpandToAllVariants() {
        assertEquals(new HashSet<>(Arrays.asList("превращаться", "превращать", "вращаться", "вращать")),
                new HashSet<>(DslParser.indexKeys("(пре)вращать(ся)")));
    }

    @Test
    public void escapesAndWhitespaceAreNormalised() {
        assertEquals(Arrays.asList("a (b) c"), DslParser.indexKeys("a  \\(b\\)\tc "));
    }

    @Test
    public void tildeTextKeepsOptionalContent() {
        assertEquals("have", DslParser.tildeText("{to }have"));
        assertEquals("returns", DslParser.tildeText("(re)turn(s)"));
    }

    // ── Companions ───────────────────────────────────────────────────────────

    @Test
    public void parsesAbbreviations() throws IOException {
        Map<String, String> abbreviations = DslParser.parseAbbreviations(
                DslParser.decode(readResource("test_abrv.dsl")));
        assertEquals("verb", abbreviations.get("v."));
        assertEquals("noun", abbreviations.get("n."));
        assertEquals(2, abbreviations.size());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DslParser.IndexEntry findEntry(DslParser.Result result, String key) {
        for (DslParser.IndexEntry entry : result.entries) {
            if (entry.key.equals(key)) return entry;
        }
        throw new AssertionError("missing key " + key);
    }

    private static String decodeCard(byte[] data, DslParser.Result result, DslParser.IndexEntry entry) {
        return new String(data, (int) entry.offset, entry.length, result.charset).replace("\r", "");
    }

    static byte[] readResource(String name) throws IOException {
        try (InputStream input = DslParserTest.class.getResourceAsStream(RES_DIR + name)) {
            assertNotNull("missing fixture " + name, input);
            return readAll(input);
        }
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readAll(input);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}

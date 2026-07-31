package itkach.aard2.descriptor;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link BlobDescriptorBackup}, the bookmarks/history backup format.
 *
 * <p>Pure JVM: the class under test has no Android dependency, so these run despite the
 * {@code returnDefaultValues true} constraint of this module's unit tests.</p>
 */
public class BlobDescriptorBackupTest {

    private static final long EXPORTED_AT = 1753900000000L;

    private ObjectMapper mapper;

    @Before
    public void setUp() {
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static BlobDescriptor descriptor(String id, String slobId, String blobId,
                                             String key, String fragment) {
        BlobDescriptor descriptor = new BlobDescriptor();
        descriptor.id = id;
        descriptor.createdAt = 1000L;
        descriptor.lastAccess = 2000L;
        descriptor.slobId = slobId;
        descriptor.slobUri = "content://dict/" + slobId;
        descriptor.blobId = blobId;
        descriptor.key = key;
        descriptor.fragment = fragment;
        return descriptor;
    }

    private byte[] write(List<BlobDescriptor> bookmarks, List<BlobDescriptor> history) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BlobDescriptorBackup.write(out, mapper, bookmarks, history, EXPORTED_AT);
        return out.toByteArray();
    }

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    // ── write / read round trip ──────────────────────────────────────────────

    @Test
    public void roundTripKeepsBothListsAndEveryField() throws IOException {
        BlobDescriptor bookmark = descriptor("bookmark-1", "dict-a", "blob-1", "hello", "top");
        BlobDescriptor visited = descriptor("history-1", "dict-b", "blob-2", "world", null);

        byte[] backup = write(Collections.singletonList(bookmark), Collections.singletonList(visited));
        BlobDescriptorBackup.Content content =
                BlobDescriptorBackup.read(new ByteArrayInputStream(backup), mapper);

        assertEquals(1, content.bookmarks.size());
        assertEquals(1, content.history.size());

        BlobDescriptor readBookmark = content.bookmarks.get(0);
        assertEquals("bookmark-1", readBookmark.id);
        assertEquals(1000L, readBookmark.createdAt);
        assertEquals(2000L, readBookmark.lastAccess);
        assertEquals("dict-a", readBookmark.slobId);
        assertEquals("content://dict/dict-a", readBookmark.slobUri);
        assertEquals("blob-1", readBookmark.blobId);
        assertEquals("hello", readBookmark.key);
        assertEquals("top", readBookmark.fragment);

        BlobDescriptor readVisited = content.history.get(0);
        assertEquals("world", readVisited.key);
        assertNull("null fragment must stay null", readVisited.fragment);
    }

    @Test
    public void writtenFileCarriesFormatMarkerAndVersion() throws IOException {
        String json = new String(write(Collections.emptyList(), Collections.emptyList()),
                StandardCharsets.UTF_8);
        assertTrue(json, json.contains("\"format\":\"" + BlobDescriptorBackup.FORMAT + "\""));
        assertTrue(json, json.contains("\"version\":" + BlobDescriptorBackup.VERSION));
        assertTrue(json, json.contains("\"exportedAt\":" + EXPORTED_AT));
    }

    @Test
    public void readsEmptyListsWhenNothingWasExported() throws IOException {
        BlobDescriptorBackup.Content content = BlobDescriptorBackup.read(
                new ByteArrayInputStream(write(Collections.emptyList(), Collections.emptyList())), mapper);
        assertTrue(content.bookmarks.isEmpty());
        assertTrue(content.history.isEmpty());
    }

    // ── read rejects what is not our file ────────────────────────────────────

    @Test(expected = IOException.class)
    public void readRejectsForeignFormat() throws IOException {
        BlobDescriptorBackup.read(stream("{\"format\":\"something-else\",\"version\":1}"), mapper);
    }

    @Test(expected = IOException.class)
    public void readRejectsMissingFormat() throws IOException {
        BlobDescriptorBackup.read(stream("{\"version\":1,\"bookmarks\":[]}"), mapper);
    }

    @Test(expected = IOException.class)
    public void readRejectsFutureVersion() throws IOException {
        BlobDescriptorBackup.read(stream("{\"format\":\"" + BlobDescriptorBackup.FORMAT
                + "\",\"version\":" + (BlobDescriptorBackup.VERSION + 1) + "}"), mapper);
    }

    @Test(expected = IOException.class)
    public void readRejectsNonJsonContent() throws IOException {
        BlobDescriptorBackup.read(stream("this is not json at all"), mapper);
    }

    @Test(expected = IOException.class)
    public void readRejectsJsonArray() throws IOException {
        BlobDescriptorBackup.read(stream("[]"), mapper);
    }

    @Test
    public void readSkipsEntriesWithoutDictionaryOrKey() throws IOException {
        String json = "{\"format\":\"" + BlobDescriptorBackup.FORMAT + "\",\"version\":1,"
                + "\"bookmarks\":["
                + "{\"id\":\"a\",\"slobId\":\"dict-a\",\"key\":\"hello\"},"
                + "{\"id\":\"b\",\"key\":\"no dictionary\"},"
                + "{\"id\":\"c\",\"slobId\":\"dict-a\"}"
                + "],\"history\":[]}";
        BlobDescriptorBackup.Content content = BlobDescriptorBackup.read(stream(json), mapper);
        assertEquals(1, content.bookmarks.size());
        assertEquals("hello", content.bookmarks.get(0).key);
    }

    // ── selectNew (merge, skip duplicates) ───────────────────────────────────

    @Test
    public void selectNewKeepsEntriesNotAlreadyPresent() {
        List<BlobDescriptor> existing = Collections.singletonList(
                descriptor("existing-1", "dict-a", "blob-1", "hello", null));
        List<BlobDescriptor> imported = Collections.singletonList(
                descriptor("imported-1", "dict-a", "blob-9", "goodbye", null));

        List<BlobDescriptor> accepted = BlobDescriptorBackup.selectNew(existing, imported);

        assertEquals(1, accepted.size());
        assertEquals("goodbye", accepted.get(0).key);
        assertEquals("id must be kept when free", "imported-1", accepted.get(0).id);
    }

    @Test
    public void selectNewSkipsExactDuplicates() {
        List<BlobDescriptor> existing = Collections.singletonList(
                descriptor("existing-1", "dict-a", "blob-1", "hello", "top"));
        List<BlobDescriptor> imported = Collections.singletonList(
                descriptor("imported-1", "dict-a", "blob-1", "hello", "top"));

        assertTrue(BlobDescriptorBackup.selectNew(existing, imported).isEmpty());
    }

    @Test
    public void selectNewSkipsSameKeyInSameDictionaryWithDifferentBlobId() {
        List<BlobDescriptor> existing = Collections.singletonList(
                descriptor("existing-1", "dict-a", "blob-1", "hello", null));
        // Same word, same dictionary, but the dictionary file was rebuilt: new blob id.
        List<BlobDescriptor> imported = Collections.singletonList(
                descriptor("imported-1", "dict-a", "blob-42", "hello", null));

        assertTrue(BlobDescriptorBackup.selectNew(existing, imported).isEmpty());
    }

    @Test
    public void selectNewKeepsSameKeyFromAnotherDictionary() {
        List<BlobDescriptor> existing = Collections.singletonList(
                descriptor("existing-1", "dict-a", "blob-1", "hello", null));
        List<BlobDescriptor> imported = Collections.singletonList(
                descriptor("imported-1", "dict-b", "blob-1", "hello", null));

        assertEquals(1, BlobDescriptorBackup.selectNew(existing, imported).size());
    }

    @Test
    public void selectNewDedupesWithinImportedBatch() {
        List<BlobDescriptor> imported = Arrays.asList(
                descriptor("imported-1", "dict-a", "blob-1", "hello", null),
                descriptor("imported-2", "dict-a", "blob-1", "hello", null));

        List<BlobDescriptor> accepted = BlobDescriptorBackup.selectNew(new ArrayList<>(), imported);

        assertEquals(1, accepted.size());
        assertEquals("imported-1", accepted.get(0).id);
    }

    @Test
    public void selectNewReplacesIdCollidingWithExistingEntry() {
        List<BlobDescriptor> existing = Collections.singletonList(
                descriptor("same-id", "dict-a", "blob-1", "hello", null));
        List<BlobDescriptor> imported = Collections.singletonList(
                descriptor("same-id", "dict-b", "blob-2", "goodbye", null));

        List<BlobDescriptor> accepted = BlobDescriptorBackup.selectNew(existing, imported);

        assertEquals(1, accepted.size());
        assertNotEquals("id would overwrite the stored entry file", "same-id", accepted.get(0).id);
        assertNotNull(accepted.get(0).id);
    }

    @Test
    public void selectNewAssignsIdWhenMissing() {
        List<BlobDescriptor> imported = Collections.singletonList(
                descriptor(null, "dict-a", "blob-1", "hello", null));

        List<BlobDescriptor> accepted = BlobDescriptorBackup.selectNew(new ArrayList<>(), imported);

        assertEquals(1, accepted.size());
        assertNotNull(accepted.get(0).id);
        assertFalse(accepted.get(0).id.isEmpty());
    }

    @Test
    public void selectNewLeavesExistingListUntouched() {
        List<BlobDescriptor> existing = new ArrayList<>(Collections.singletonList(
                descriptor("existing-1", "dict-a", "blob-1", "hello", null)));
        List<BlobDescriptor> imported = Collections.singletonList(
                descriptor("imported-1", "dict-b", "blob-2", "goodbye", null));

        BlobDescriptorBackup.selectNew(existing, imported);

        assertEquals(1, existing.size());
        assertEquals("existing-1", existing.get(0).id);
    }
}

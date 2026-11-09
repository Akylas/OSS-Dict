package itkach.aard2.descriptor;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BlobDescriptorStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private DescriptorStore<BlobDescriptor> store;

    @Before
    public void setUp() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        store = new DescriptorStore<>(mapper, folder.newFolder("bookmarks"));
    }

    private static BlobDescriptor descriptor() {
        BlobDescriptor descriptor = new BlobDescriptor();
        descriptor.id = "4f8d1c9e-6a53-4d0b-9c1f-0a2b3c4d5e6f";
        descriptor.createdAt = 1000L;
        descriptor.lastAccess = 2000L;
        descriptor.slobId = "dict-a";
        descriptor.slobUri = "content://dict/dict-a";
        descriptor.blobId = "blob-1";
        descriptor.key = "lopen";
        return descriptor;
    }

    private BlobDescriptor saveAndReload(BlobDescriptor descriptor) {
        store.save(descriptor);
        List<BlobDescriptor> loaded = store.load(BlobDescriptor.class);
        assertEquals(1, loaded.size());
        return loaded.get(0);
    }

    private void writeRaw(String id, String json) throws IOException {
        File saved = new File(folder.getRoot(), "bookmarks/" + id);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(saved), StandardCharsets.UTF_8)) {
            writer.write(json);
        }
    }

    @Test
    public void foldersAndNoteSurviveRoundTrip() {
        BlobDescriptor descriptor = descriptor();
        descriptor.folders = new ArrayList<>(Arrays.asList("Verbs", "Week 1"));
        descriptor.note = "to walk\nliep, gelopen";

        BlobDescriptor reloaded = saveAndReload(descriptor);

        assertEquals(Arrays.asList("Verbs", "Week 1"), reloaded.folders);
        assertEquals("to walk\nliep, gelopen", reloaded.note);
        assertEquals("lopen", reloaded.key);
        assertEquals("blob-1", reloaded.blobId);
    }

    @Test
    public void bookmarkWithoutFoldersOrNoteStaysEmpty() {
        BlobDescriptor reloaded = saveAndReload(descriptor());

        assertNotNull(reloaded.folders);
        assertTrue(reloaded.folders.isEmpty());
        assertNull(reloaded.note);
    }

    @Test
    public void bookmarkStoredBeforeFoldersAndNotesExistedStillLoads() throws IOException {
        BlobDescriptor descriptor = descriptor();
        writeRaw(descriptor.id, "{\"id\":\"" + descriptor.id + "\",\"createdAt\":1000,"
                + "\"slobId\":\"dict-a\",\"blobId\":\"blob-1\",\"key\":\"lopen\",\"fragment\":null}");

        List<BlobDescriptor> loaded = store.load(BlobDescriptor.class);

        assertEquals(1, loaded.size());
        assertEquals("lopen", loaded.get(0).key);
        assertTrue(loaded.get(0).folders.isEmpty());
        assertNull(loaded.get(0).note);
    }

    @Test
    public void legacyRecoveryKeepsFoldersAndNote() throws IOException {
        BlobDescriptor descriptor = descriptor();
        // createdAt as a string makes the regular read fail, so load() falls back to the
        // field by field recovery
        writeRaw(descriptor.id, "{\"id\":\"" + descriptor.id + "\",\"createdAt\":\"not a number\","
                + "\"slobId\":\"dict-a\",\"blobId\":\"blob-1\",\"key\":\"lopen\","
                + "\"folders\":[\"Verbs\",\"Week 1\"],\"note\":\"to walk\"}");

        List<BlobDescriptor> loaded = store.load(BlobDescriptor.class);

        assertEquals(1, loaded.size());
        assertEquals(Arrays.asList("Verbs", "Week 1"), loaded.get(0).folders);
        assertEquals("to walk", loaded.get(0).note);
    }
}

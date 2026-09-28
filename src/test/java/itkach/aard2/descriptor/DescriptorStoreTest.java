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
import java.util.List;

import itkach.aard2.slob.SlobTags;

/**
 * Unit tests for {@link DescriptorStore} persistence of {@link SlobDescriptor}.
 *
 * <p>{@code save()} serialises the descriptor field by field, so a field added to the
 * model is silently dropped unless it is written there too — these tests guard the
 * round trip.</p>
 */
public class DescriptorStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private DescriptorStore<SlobDescriptor> store;

    @Before
    public void setUp() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        store = new DescriptorStore<>(mapper, folder.newFolder("dictionaries"));
    }

    private static SlobDescriptor descriptor() {
        SlobDescriptor descriptor = new SlobDescriptor();
        descriptor.id = "9c90918b-b27c-5e04-a8d6-118623023162";
        descriptor.format = SlobDescriptor.FORMAT_STARDICT;
        descriptor.path = "content://dictionaries/wikipedia-en.ifo";
        descriptor.tags.put(SlobTags.TAG_LABEL, "Wikipedia (en)");
        descriptor.blobCount = 4;
        return descriptor;
    }

    private SlobDescriptor saveAndReload(SlobDescriptor descriptor) {
        store.save(descriptor);
        List<SlobDescriptor> loaded = store.load(SlobDescriptor.class);
        assertEquals(1, loaded.size());
        return loaded.get(0);
    }

    @Test
    public void displayNameSurvivesRoundTrip() {
        SlobDescriptor descriptor = descriptor();
        descriptor.displayName = "Simple Wikipedia";
        SlobDescriptor reloaded = saveAndReload(descriptor);
        assertEquals("Simple Wikipedia", reloaded.displayName);
        assertEquals("Simple Wikipedia", reloaded.getLabel());
        assertEquals("Wikipedia (en)", reloaded.getOriginalLabel());
    }

    @Test
    public void missingDisplayNameStaysNull() {
        SlobDescriptor reloaded = saveAndReload(descriptor());
        assertNull(reloaded.displayName);
        assertEquals("Wikipedia (en)", reloaded.getLabel());
    }

    @Test
    public void legacyRecoveryKeepsDisplayName() throws IOException {
        SlobDescriptor descriptor = descriptor();
        // blobCount as a string makes the annotation-driven read fail, so load() falls
        // back to the field-by-field legacy recovery
        String json = "{\"id\":\"" + descriptor.id + "\","
                + "\"format\":\"stardict\","
                + "\"path\":\"" + descriptor.path + "\","
                + "\"tags\":{\"label\":\"Wikipedia (en)\"},"
                + "\"displayName\":\"Simple Wikipedia\","
                + "\"blobCount\":\"not a number\"}";
        File saved = new File(folder.getRoot(), "dictionaries/" + descriptor.id);
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(saved), StandardCharsets.UTF_8)) {
            writer.write(json);
        }

        List<SlobDescriptor> loaded = store.load(SlobDescriptor.class);
        assertEquals(1, loaded.size());
        assertEquals("Simple Wikipedia", loaded.get(0).displayName);
        assertEquals("Simple Wikipedia", loaded.get(0).getLabel());
    }
}

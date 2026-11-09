package itkach.aard2.dictionary.dsl;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@link DslResourceZip} on an archive with more entries than a classic zip
 * end record can count (65535), as shipped with large pronunciation packs.
 * {@link ZipOutputStream} switches to ZIP64 records for such archives.
 */
public class DslResourceZipLargeArchiveTest {

    private static final int ENTRY_COUNT = 70000;

    private File archive;
    private FileInputStream input;
    private DslResourceZip zip;

    @Before
    public void setUp() throws IOException {
        archive = File.createTempFile("large", ".dsl.files.zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            for (int index = 0; index < ENTRY_COUNT; index++) {
                output.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "word%06d.mp3", index)));
                output.write(String.format(Locale.ROOT, "sound %d", index).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        input = new FileInputStream(archive);
        zip = new DslResourceZip(input.getChannel());
    }

    @After
    public void tearDown() throws IOException {
        input.close();
        //noinspection ResultOfMethodCallIgnored
        archive.delete();
    }

    @Test
    public void readsFirstEntry() throws IOException {
        assertArrayEquals("sound 0".getBytes(StandardCharsets.UTF_8), zip.read("word000000.mp3"));
    }

    @Test
    public void readsEntryBeyondClassicEntryLimit() throws IOException {
        assertArrayEquals("sound 69999".getBytes(StandardCharsets.UTF_8), zip.read("word069999.mp3"));
    }
}

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
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Unit tests for {@link DslResourceZip}, reading an archive written by
 * {@link ZipOutputStream} with one stored and one deflated entry.
 */
public class DslResourceZipTest {

    private static final byte[] STORED = "stored image bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DEFLATED = "deflated sound, deflated sound, deflated sound"
            .getBytes(StandardCharsets.UTF_8);

    private File archive;
    private FileInputStream input;
    private DslResourceZip zip;

    @Before
    public void setUp() throws IOException {
        archive = File.createTempFile("test", ".dsl.files.zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            ZipEntry stored = new ZipEntry("media/Cat.PNG");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(STORED.length);
            CRC32 crc = new CRC32();
            crc.update(STORED);
            stored.setCrc(crc.getValue());
            output.putNextEntry(stored);
            output.write(STORED);
            output.closeEntry();

            output.putNextEntry(new ZipEntry("have.wav"));
            output.write(DEFLATED);
            output.closeEntry();
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
    public void readsStoredEntryByFileNameIgnoringCaseAndFolder() throws IOException {
        assertArrayEquals(STORED, zip.read("cat.png"));
    }

    @Test
    public void readsDeflatedEntry() throws IOException {
        assertArrayEquals(DEFLATED, zip.read("have.wav"));
    }

    @Test
    public void missingEntryIsNull() throws IOException {
        assertNull(zip.read("missing.wav"));
    }
}

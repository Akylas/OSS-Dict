package itkach.aard2.dictionary.dsl;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Unit tests for {@link DictZipWriter} and {@link DictZipReader}: round trip,
 * reads across chunk boundaries, gzip compatibility and plain-gzip rejection.
 */
public class DictZipTest {

    private static final int CHUNK_LENGTH = 1000;

    private File directory;
    private byte[] original;

    @Before
    public void setUp() throws IOException {
        directory = File.createTempFile("dictzip", "");
        assertTrue(directory.delete());
        assertTrue(directory.mkdir());
        // Mixed compressible text and random bytes, not a multiple of the chunk length.
        Random random = new Random(42);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int line = 0; line < 400; line++) {
            data.write(("headword " + line + "\n\t[m1]body " + line + "[/m]\n").getBytes());
            byte[] noise = new byte[random.nextInt(20)];
            random.nextBytes(noise);
            data.write(noise);
        }
        original = data.toByteArray();
        assertTrue(original.length > 5 * CHUNK_LENGTH);
        assertTrue(original.length % CHUNK_LENGTH != 0);
    }

    @After
    public void tearDown() {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        directory.delete();
    }

    @Test
    public void randomReadsMatchOriginal() throws IOException {
        File dictzip = write(original);
        try (FileInputStream input = new FileInputStream(dictzip)) {
            DictZipReader reader = DictZipReader.open(input.getChannel());
            assertNotNull(reader);
            assertSlice(reader, 0, 10);
            assertSlice(reader, CHUNK_LENGTH - 5, 10);            // crosses one boundary
            assertSlice(reader, 2 * CHUNK_LENGTH - 1, 2 * CHUNK_LENGTH + 2); // spans three chunks
            assertSlice(reader, 17, 3);
            assertSlice(reader, original.length - 7, 7);          // end of the last, short chunk
            assertSlice(reader, 0, original.length);
        }
    }

    @Test
    public void readPastEndFails() throws IOException {
        File dictzip = write(original);
        try (FileInputStream input = new FileInputStream(dictzip)) {
            DictZipReader reader = DictZipReader.open(input.getChannel());
            assertNotNull(reader);
            try {
                reader.read(original.length - 2, 5);
                fail("expected IOException");
            } catch (IOException expected) {
                // expected
            }
        }
    }

    @Test
    public void outputIsValidGzip() throws IOException {
        File dictzip = write(original);
        try (InputStream input = new GZIPInputStream(new FileInputStream(dictzip))) {
            assertArrayEquals(original, readAll(input));
        }
    }

    @Test
    public void emptyInputRoundTrips() throws IOException {
        File dictzip = write(new byte[0]);
        try (InputStream input = new GZIPInputStream(new FileInputStream(dictzip))) {
            assertEquals(0, readAll(input).length);
        }
        try (FileInputStream input = new FileInputStream(dictzip)) {
            assertNotNull(DictZipReader.open(input.getChannel()));
        }
    }

    @Test
    public void plainGzipIsNotDictZip() throws IOException {
        File gzip = new File(directory, "plain.gz");
        try (GZIPOutputStream output = new GZIPOutputStream(new FileOutputStream(gzip))) {
            output.write(original);
        }
        try (FileInputStream input = new FileInputStream(gzip)) {
            assertNull(DictZipReader.open(input.getChannel()));
        }
    }

    @Test
    public void parserReadsDictZipStream() throws IOException {
        byte[] dsl = DslParserTest.readResource("test.dsl");
        File dictzip = write(dsl);
        DslParser.Result result;
        try (InputStream input = new GZIPInputStream(new FileInputStream(dictzip))) {
            result = DslParser.parse(input);
        }
        assertEquals(4, result.cardCount);
        try (FileInputStream input = new FileInputStream(dictzip)) {
            DictZipReader reader = DictZipReader.open(input.getChannel());
            assertNotNull(reader);
            for (DslParser.IndexEntry entry : result.entries) {
                byte[] card = reader.read(entry.offset, entry.length);
                assertArrayEquals(Arrays.copyOfRange(dsl, (int) entry.offset, (int) entry.offset + entry.length), card);
            }
        }
    }

    private File write(byte[] data) throws IOException {
        File output = new File(directory, "out.dz");
        File work = new File(directory, "out.chunks");
        DictZipWriter.write(new ByteArrayInputStream(data), output, work, CHUNK_LENGTH);
        assertFalse("work file must be removed", work.exists());
        return output;
    }

    private void assertSlice(DictZipReader reader, int offset, int length) throws IOException {
        assertArrayEquals("slice " + offset + "+" + length,
                Arrays.copyOfRange(original, offset, offset + length), reader.read(offset, length));
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}

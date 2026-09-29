package itkach.aard2.dictionaries;

import static org.junit.Assert.*;

import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link DictionaryArchives}.
 */
public class DictionaryArchivesTest {

    /** Entry name to content, as found in FreeMdict archives (folder, icon, companions). */
    private static final String[][] ENTRIES = {
            {"Simple Dict/afr-eng.dsl.dz", "dsl"},
            {"Simple Dict/afr-eng.ann", "ann"},
            {"Simple Dict/afr-eng.bmp", "icon"},
            {"Simple Dict/afr-eng.dsl.files.zip", "media"},
            {"readme.txt", "readme"},
    };

    private static final List<String> EXPECTED = Arrays.asList(
            "afr-eng.ann", "afr-eng.dsl.dz", "afr-eng.dsl.files.zip");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void extract_zip() throws IOException {
        File archive = temporaryFolder.newFile("dict.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(archive)) {
            writeEntries(out, name -> new ZipArchiveEntry(name));
        }
        assertExtracted(archive);
    }

    @Test
    public void extract_tarGz() throws IOException {
        File archive = temporaryFolder.newFile("dict.tar.gz");
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(
                new GzipCompressorOutputStream(new FileOutputStream(archive)))) {
            writeTarEntries(out);
        }
        assertExtracted(archive);
    }

    @Test
    public void extract_tarXz() throws IOException {
        File archive = temporaryFolder.newFile("dict.tar.xz");
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(
                new XZCompressorOutputStream(new FileOutputStream(archive)))) {
            writeTarEntries(out);
        }
        assertExtracted(archive);
    }

    @Test
    public void extract_sevenZ() throws IOException {
        File archive = temporaryFolder.newFile("dict (DSL).7z");
        try (SevenZOutputFile out = new SevenZOutputFile(archive)) {
            for (String[] entry : ENTRIES) {
                SevenZArchiveEntry sevenZEntry = new SevenZArchiveEntry();
                sevenZEntry.setName(entry[0]);
                out.putArchiveEntry(sevenZEntry);
                out.write(entry[1].getBytes(StandardCharsets.UTF_8));
                out.closeArchiveEntry();
            }
        }
        assertExtracted(archive);
    }

    @Test
    public void extract_keepsTraversingEntriesInsideTargetFolder() throws IOException {
        File archive = temporaryFolder.newFile("evil.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(archive)) {
            out.putArchiveEntry(new ZipArchiveEntry("../../evil.slob"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }
        File targetDir = temporaryFolder.newFolder("target");

        List<File> extracted = DictionaryArchives.extract(archive, targetDir);

        assertEquals(Collections.singletonList(new File(targetDir, "evil.slob")), extracted);
        assertFalse(new File(temporaryFolder.getRoot(), "evil.slob").exists());
    }

    @Test
    public void extract_archiveWithoutDictionaryGivesNothing() throws IOException {
        File archive = temporaryFolder.newFile("docs.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(archive)) {
            out.putArchiveEntry(new ZipArchiveEntry("readme.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }

        List<File> extracted = DictionaryArchives.extract(archive, temporaryFolder.newFolder("target"));

        assertTrue(extracted.isEmpty());
    }

    private interface EntryFactory {
        org.apache.commons.compress.archivers.ArchiveEntry create(String name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void writeEntries(ArchiveOutputStream out, EntryFactory factory) throws IOException {
        for (String[] entry : ENTRIES) {
            out.putArchiveEntry(factory.create(entry[0]));
            out.write(entry[1].getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }
    }

    private static void writeTarEntries(TarArchiveOutputStream out) throws IOException {
        for (String[] entry : ENTRIES) {
            byte[] content = entry[1].getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry tarEntry = new TarArchiveEntry(entry[0]);
            tarEntry.setSize(content.length);
            out.putArchiveEntry(tarEntry);
            out.write(content);
            out.closeArchiveEntry();
        }
    }

    private void assertExtracted(File archive) throws IOException {
        File targetDir = temporaryFolder.newFolder("target");

        List<File> extracted = DictionaryArchives.extract(archive, targetDir);

        List<String> names = new ArrayList<>();
        for (File file : extracted) {
            assertEquals(targetDir, file.getParentFile());
            names.add(file.getName());
        }
        Collections.sort(names);
        assertEquals(EXPECTED, names);
        assertEquals("dsl", readText(new File(targetDir, "afr-eng.dsl.dz")));
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] content = new byte[(int) file.length()];
            int offset = 0;
            while (offset < content.length) {
                int read = in.read(content, offset, content.length - offset);
                if (read == -1) break;
                offset += read;
            }
            return new String(content, 0, offset, StandardCharsets.UTF_8);
        }
    }
}

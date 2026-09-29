package itkach.aard2.dictionaries;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for {@link DictionaryDownloads}.
 */
public class DictionaryDownloadsTest {

    @Test
    public void fileName_prefersExtendedFilename() {
        String name = DictionaryDownloads.fileName("https://example.com/download",
                "attachment; filename=\"fallback.mdx\"; filename*=UTF-8''%E6%B1%89%E8%AF%AD%20dict.mdx");
        assertEquals("汉语 dict.mdx", name);
    }

    @Test
    public void fileName_usesQuotedFilename() {
        String name = DictionaryDownloads.fileName("https://example.com/download",
                "attachment; filename=\"Oxford Dict.slob\"");
        assertEquals("Oxford Dict.slob", name);
    }

    @Test
    public void fileName_usesUnquotedFilename() {
        String name = DictionaryDownloads.fileName("https://example.com/download",
                "attachment; filename=wordnet.zip");
        assertEquals("wordnet.zip", name);
    }

    @Test
    public void fileName_fallsBackToUrlPath() {
        String name = DictionaryDownloads.fileName(
                "https://example.com/files/My%20Dict+v2.dsl.dz?token=abc#top", null);
        assertEquals("My Dict+v2.dsl.dz", name);
    }

    @Test
    public void fileName_nextcloudShareUsesContentDisposition() {
        String name = DictionaryDownloads.fileName(
                "https://cloud.example.com/index.php/s/TOKEN/download?path=%2F&files=Longman.mdx",
                "attachment; filename*=UTF-8''Longman.mdx; filename=\"Longman.mdx\"");
        assertEquals("Longman.mdx", name);
    }

    @Test
    public void fileName_decodesPercentEncodedLegacyFilename() {
        // Nextcloud WebDAV sends only a percent-encoded legacy filename, as browsers decode it
        String name = DictionaryDownloads.fileName(
                "https://cloud.example.com/public.php/dav/files/TOKEN/Afr/Afrikaans%20Dictionary%20(BGL).7z",
                "attachment; filename=\"Afrikaans%20Dictionary%20%28BGL%29.7z\"");
        assertEquals("Afrikaans Dictionary (BGL).7z", name);
    }

    @Test
    public void fileName_keepsLiteralPercentInLegacyFilename() {
        String name = DictionaryDownloads.fileName("https://example.com/download",
                "attachment; filename=\"100% words.slob\"");
        assertEquals("100% words.slob", name);
    }

    @Test
    public void fileName_stripsPathSeparators() {
        String name = DictionaryDownloads.fileName("https://example.com/download",
                "attachment; filename=\"../../evil/dict.slob\"");
        assertEquals("dict.slob", name);
    }

    @Test
    public void fileName_neverEmpty() {
        assertEquals("download", DictionaryDownloads.fileName("https://example.com/", null));
        assertEquals("download", DictionaryDownloads.fileName("https://example.com/..", null));
    }

    @Test
    public void isSupported_acceptsDictionaryFormats() {
        String[] names = {
                "a.slob", "a.mdx", "a.mdd", "a.zip", "a.dsl.files.zip",
                "a.dsl", "a.dsl.gz", "a.dsl.dz", "a.ann", "a_abrv.dsl",
                "a.ifo", "a.idx", "a.idx.gz", "a.dict", "a.dict.dz", "a.syn",
                "UPPER.MDX",
        };
        for (String name : names) {
            assertTrue(name, DictionaryDownloads.isSupported(name));
        }
    }

    @Test
    public void isSupported_acceptsArchives() {
        String[] names = {"a.zip", "a (DSL).7z", "a.tar", "a.tar.gz", "a.tgz", "a.tar.xz", "a.txz",
                "a.tar.bz2", "a.tbz2"};
        for (String name : names) {
            assertTrue(name, DictionaryDownloads.isSupported(name));
            assertTrue(name, DictionaryDownloads.isArchive(name));
        }
    }

    @Test
    public void isSupported_rejectsOtherFiles() {
        String[] names = {"a.rar", "a.pdf", "a.gz", "a.mdx.part", "a.bmp", "download"};
        for (String name : names) {
            assertFalse(name, DictionaryDownloads.isSupported(name));
        }
    }

    @Test
    public void isArchive_keepsDslResourcesAndDictionaryFiles() {
        assertFalse(DictionaryDownloads.isArchive("a.dsl.files.zip"));
        assertFalse(DictionaryDownloads.isArchive("a.files.zip"));
        assertFalse(DictionaryDownloads.isArchive("a.mdx"));
        assertFalse(DictionaryDownloads.isArchive("a.dict.dz"));
        assertTrue(DictionaryDownloads.isDictionaryFile("a.dsl.files.zip"));
    }
}

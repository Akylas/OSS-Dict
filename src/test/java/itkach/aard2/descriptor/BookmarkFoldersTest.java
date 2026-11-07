package itkach.aard2.descriptor;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class BookmarkFoldersTest {

    private static BlobDescriptor bookmark(String key, String... folders) {
        BlobDescriptor descriptor = new BlobDescriptor();
        descriptor.id = "id-" + key;
        descriptor.slobId = "dict-a";
        descriptor.blobId = "blob-" + key;
        descriptor.key = key;
        descriptor.folders = new ArrayList<>(Arrays.asList(folders));
        return descriptor;
    }

    @Test
    public void normalizeNameTrimsAndRejectsBlank() {
        assertEquals("Verbs", BookmarkFolders.normalizeName("  Verbs \n"));
        assertEquals("Two words", BookmarkFolders.normalizeName("Two\nwords"));
        assertNull(BookmarkFolders.normalizeName("   "));
        assertNull(BookmarkFolders.normalizeName(null));
    }

    @Test
    public void findIgnoreCaseReturnsTheStoredSpelling() {
        List<String> names = Arrays.asList("Verbs", "Глаголы");
        assertEquals("Verbs", BookmarkFolders.findIgnoreCase(names, "verbs"));
        assertEquals("Глаголы", BookmarkFolders.findIgnoreCase(names, "глаголы"));
        assertNull(BookmarkFolders.findIgnoreCase(names, "Nouns"));
    }

    @Test
    public void searchIgnoresCaseAndAccents() {
        List<String> names = Arrays.asList("Café words", "Verbs", "Глаголы");
        assertEquals(Collections.singletonList("Café words"), BookmarkFolders.search(names, "CAFE"));
        assertEquals(Collections.singletonList("Café words"), BookmarkFolders.search(names, "café"));
        assertEquals(Collections.singletonList("Verbs"), BookmarkFolders.search(names, "ERB"));
        assertEquals(Collections.singletonList("Глаголы"), BookmarkFolders.search(names, "глаг"));
    }

    @Test
    public void searchLooksForEveryWordTyped() {
        List<String> names = Arrays.asList("Chapter 1", "Chapter 12", "Week 12");
        assertEquals(Collections.singletonList("Chapter 12"), BookmarkFolders.search(names, " ch  12 "));
        assertEquals(Arrays.asList("Chapter 12", "Week 12"), BookmarkFolders.search(names, "12"));
        assertTrue(BookmarkFolders.search(names, "ch 7").isEmpty());
    }

    @Test
    public void searchWithNothingTypedKeepsEveryNameInOrder() {
        List<String> names = Arrays.asList("Verbs", "Nouns");
        assertEquals(names, BookmarkFolders.search(names, ""));
        assertEquals(names, BookmarkFolders.search(names, "   "));
        assertEquals(names, BookmarkFolders.search(names, null));
    }

    @Test
    public void newBookmarkIsUnfiled() {
        BlobDescriptor descriptor = new BlobDescriptor();
        assertNotNull(descriptor.folders);
        assertTrue(BookmarkFolders.isUnfiled(descriptor));
        assertFalse(BookmarkFolders.hasAnnotations(descriptor));
    }

    @Test
    public void bookmarkCanBeInSeveralFolders() {
        BlobDescriptor descriptor = bookmark("lopen");

        assertTrue(BookmarkFolders.update(descriptor, Arrays.asList("Verbs", "Week 1"),
                Collections.emptyList()));

        assertTrue(BookmarkFolders.isIn(descriptor, "Verbs"));
        assertTrue(BookmarkFolders.isIn(descriptor, "Week 1"));
        assertFalse(BookmarkFolders.isIn(descriptor, "Nouns"));
        assertFalse(BookmarkFolders.isUnfiled(descriptor));
        assertTrue(BookmarkFolders.hasAnnotations(descriptor));
    }

    @Test
    public void updateAddsAndRemovesAndLeavesOtherFoldersAlone() {
        BlobDescriptor descriptor = bookmark("lopen", "Verbs", "Week 1", "Hard");

        assertTrue(BookmarkFolders.update(descriptor,
                Collections.singletonList("Exam"), Collections.singletonList("Week 1")));

        assertEquals(Arrays.asList("Verbs", "Hard", "Exam"), descriptor.folders);
    }

    @Test
    public void updateReportsNoChangeWhenNothingChanges() {
        BlobDescriptor descriptor = bookmark("lopen", "Verbs");

        assertFalse(BookmarkFolders.update(descriptor,
                Collections.singletonList("Verbs"), Collections.singletonList("Nouns")));
        assertEquals(Collections.singletonList("Verbs"), descriptor.folders);
    }

    @Test
    public void updateNeverStoresAFolderTwice() {
        BlobDescriptor descriptor = bookmark("lopen", "Verbs");

        BookmarkFolders.update(descriptor, Arrays.asList("Verbs", "Verbs", "Hard"),
                Collections.emptyList());

        assertEquals(Arrays.asList("Verbs", "Hard"), descriptor.folders);
    }

    @Test
    public void updateReplacesTheListInsteadOfChangingItInPlace() {
        BlobDescriptor descriptor = bookmark("lopen", "Verbs");
        List<String> before = descriptor.folders;

        BookmarkFolders.update(descriptor, Collections.singletonList("Hard"), Collections.emptyList());

        assertNotSame(before, descriptor.folders);
        assertEquals(Collections.singletonList("Verbs"), before);
    }

    @Test
    public void updateCopesWithMissingFolderList() {
        BlobDescriptor descriptor = bookmark("lopen");
        descriptor.folders = null;

        assertTrue(BookmarkFolders.isUnfiled(descriptor));
        assertFalse(BookmarkFolders.isIn(descriptor, "Verbs"));
        assertTrue(BookmarkFolders.update(descriptor, Collections.singletonList("Verbs"),
                Collections.emptyList()));
        assertEquals(Collections.singletonList("Verbs"), descriptor.folders);
    }

    @Test
    public void countCountsBookmarksPerFolder() {
        List<BlobDescriptor> bookmarks = Arrays.asList(
                bookmark("lopen", "Verbs", "Week 1"), bookmark("huis", "Nouns", "Week 1"),
                bookmark("snel"), bookmark("zijn", "Verbs", "Verbs"));

        Map<String, Integer> counts = BookmarkFolders.count(bookmarks);

        assertEquals(Arrays.asList("Verbs", "Week 1", "Nouns"), new ArrayList<>(counts.keySet()));
        assertEquals(Integer.valueOf(2), counts.get("Verbs"));
        assertEquals(Integer.valueOf(2), counts.get("Week 1"));
        assertEquals(Integer.valueOf(1), counts.get("Nouns"));
        assertNull(counts.get("Empty"));
        assertEquals(1, BookmarkFolders.countUnfiled(bookmarks));
    }

    @Test
    public void renameChangesTheFolderOnEveryBookmarkInIt() {
        BlobDescriptor lopen = bookmark("lopen", "Verbs", "Week 1");
        BlobDescriptor huis = bookmark("huis", "Nouns");
        BlobDescriptor zijn = bookmark("zijn", "Verbs");

        List<BlobDescriptor> changed = BookmarkFolders.rename(
                Arrays.asList(lopen, huis, zijn), "Verbs", "Werkwoorden");

        assertEquals(Arrays.asList(lopen, zijn), changed);
        assertEquals(Arrays.asList("Werkwoorden", "Week 1"), lopen.folders);
        assertEquals(Collections.singletonList("Nouns"), huis.folders);
        assertEquals(Collections.singletonList("Werkwoorden"), zijn.folders);
    }

    @Test
    public void renameOntoAFolderTheBookmarkIsAlreadyInKeepsOneEntry() {
        BlobDescriptor lopen = bookmark("lopen", "Verbs", "Werkwoorden");

        BookmarkFolders.rename(Collections.singletonList(lopen), "Verbs", "Werkwoorden");

        assertEquals(Collections.singletonList("Werkwoorden"), lopen.folders);
    }

    @Test
    public void removeTakesBookmarksOutOfTheFolderButKeepsThem() {
        BlobDescriptor lopen = bookmark("lopen", "Verbs", "Week 1");
        BlobDescriptor huis = bookmark("huis", "Nouns");
        List<BlobDescriptor> bookmarks = new ArrayList<>(Arrays.asList(lopen, huis));

        List<BlobDescriptor> changed = BookmarkFolders.remove(bookmarks, "Verbs");

        assertEquals(Collections.singletonList(lopen), changed);
        assertEquals(2, bookmarks.size());
        assertEquals(Collections.singletonList("Week 1"), lopen.folders);
        assertEquals(Collections.singletonList("Nouns"), huis.folders);
    }

    @Test
    public void setNoteStoresTrimmedTextAndReportsChange() {
        BlobDescriptor descriptor = bookmark("lopen");

        assertTrue(BookmarkFolders.setNote(descriptor, "  to walk\nliep, gelopen\n"));
        assertEquals("to walk\nliep, gelopen", descriptor.note);
        assertTrue(BookmarkFolders.hasAnnotations(descriptor));
        assertFalse(BookmarkFolders.setNote(descriptor, "to walk\nliep, gelopen"));
    }

    @Test
    public void blankNoteClearsIt() {
        BlobDescriptor descriptor = bookmark("lopen");
        descriptor.note = "old";

        assertTrue(BookmarkFolders.setNote(descriptor, "   "));
        assertNull(descriptor.note);
        assertFalse(BookmarkFolders.setNote(descriptor, null));
    }

    @Test
    public void mergeIntoAddsFoldersWithoutRemovingAny() {
        BlobDescriptor stored = bookmark("lopen", "Verbs");
        BlobDescriptor imported = bookmark("lopen", "Week 1");

        assertTrue(BookmarkFolders.mergeInto(stored, imported));
        assertEquals(Arrays.asList("Verbs", "Week 1"), stored.folders);
    }

    @Test
    public void mergeIntoFillsAMissingNoteOnly() {
        BlobDescriptor stored = bookmark("lopen");
        BlobDescriptor imported = bookmark("lopen");
        imported.note = "Theirs";

        assertTrue(BookmarkFolders.mergeInto(stored, imported));
        assertEquals("Theirs", stored.note);

        imported.note = "Changed";
        assertFalse(BookmarkFolders.mergeInto(stored, imported));
        assertEquals("Theirs", stored.note);
    }
}

package itkach.aard2.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import itkach.aard2.descriptor.BlobDescriptor;

/**
 * Unit tests for {@link RecentShortcuts#selectRecentWords}, the only part of the class with no
 * Android dependency.
 */
public class RecentShortcutsTest {

    private static BlobDescriptor entry(String key, long lastAccess) {
        BlobDescriptor descriptor = new BlobDescriptor();
        descriptor.key = key;
        descriptor.lastAccess = lastAccess;
        return descriptor;
    }

    @Test
    public void emptyHistoryGivesNoWords() {
        assertTrue(RecentShortcuts.selectRecentWords(Collections.emptyList(), 4).isEmpty());
    }

    @Test
    public void mostRecentWordsComeFirst() {
        List<BlobDescriptor> history = Arrays.asList(entry("old", 1), entry("new", 3), entry("mid", 2));
        assertEquals(Arrays.asList("new", "mid", "old"), RecentShortcuts.selectRecentWords(history, 4));
    }

    @Test
    public void resultIsCappedToMax() {
        List<BlobDescriptor> history = Arrays.asList(
                entry("a", 1), entry("b", 2), entry("c", 3), entry("d", 4), entry("e", 5));
        assertEquals(Arrays.asList("e", "d", "c"), RecentShortcuts.selectRecentWords(history, 3));
    }

    @Test
    public void sameWordFromSeveralDictionariesAppearsOnce() {
        List<BlobDescriptor> history = Arrays.asList(entry("hello", 1), entry("world", 2), entry("hello", 3));
        assertEquals(Arrays.asList("hello", "world"), RecentShortcuts.selectRecentWords(history, 4));
    }

    @Test
    public void duplicatesDoNotUseUpTheCap() {
        List<BlobDescriptor> history = Arrays.asList(
                entry("hello", 4), entry("hello", 3), entry("world", 2), entry("again", 1));
        assertEquals(Arrays.asList("hello", "world"), RecentShortcuts.selectRecentWords(history, 2));
    }

    @Test
    public void entriesWithoutAKeyAreSkipped() {
        List<BlobDescriptor> history = Arrays.asList(entry(null, 3), entry("", 2), entry("  ", 2), entry("word", 1));
        assertEquals(Collections.singletonList("word"), RecentShortcuts.selectRecentWords(history, 4));
    }

    @Test
    public void nullEntriesAreSkipped() {
        List<BlobDescriptor> history = Arrays.asList(null, entry("word", 1), null);
        assertEquals(Collections.singletonList("word"), RecentShortcuts.selectRecentWords(history, 4));
    }

    @Test
    public void inputListIsNotModified() {
        List<BlobDescriptor> history = new ArrayList<>(Arrays.asList(entry("old", 1), entry("new", 2)));
        List<BlobDescriptor> before = new ArrayList<>(history);
        RecentShortcuts.selectRecentWords(history, 4);
        assertEquals(before, history);
    }
}

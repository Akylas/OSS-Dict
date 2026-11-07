package itkach.aard2.prefs;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

// Every folder name, so that a folder outlives its last bookmark
public class BookmarkFolderPrefs extends Prefs {
    private static final String PREF_NAMES = "names";

    private static BookmarkFolderPrefs instance;

    private static BookmarkFolderPrefs getInstance() {
        if (instance == null) {
            instance = new BookmarkFolderPrefs();
        }
        return instance;
    }

    protected BookmarkFolderPrefs() {
        super("bookmarkFolders");
    }

    @NonNull
    public static Set<String> getNames() {
        return new HashSet<>(getInstance().prefs.getStringSet(PREF_NAMES, Collections.emptySet()));
    }

    public static void add(@NonNull String name) {
        addAll(Collections.singleton(name));
    }

    public static void addAll(@NonNull Collection<String> names) {
        Set<String> stored = getNames();
        if (stored.addAll(names)) {
            setNames(stored);
        }
    }

    public static void remove(@NonNull String name) {
        Set<String> stored = getNames();
        if (stored.remove(name)) {
            setNames(stored);
        }
    }

    public static void rename(@NonNull String oldName, @NonNull String newName) {
        Set<String> stored = getNames();
        stored.remove(oldName);
        stored.add(newName);
        setNames(stored);
    }

    private static void setNames(@NonNull Set<String> names) {
        getInstance().prefs.edit().putStringSet(PREF_NAMES, names).apply();
    }
}

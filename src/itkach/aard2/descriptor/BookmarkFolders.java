package itkach.aard2.descriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class BookmarkFolders {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private BookmarkFolders() {
    }

    @Nullable
    public static String normalizeName(@Nullable String name) {
        String normalized = name == null ? "" : name.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @Nullable
    public static String findIgnoreCase(@NonNull Collection<String> names, @NonNull String name) {
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    // Ignores case and accents, and looks for each word typed apart: "ch 12" finds "Chapter 12"
    @NonNull
    public static List<String> search(@NonNull Collection<String> names, @Nullable String text) {
        String[] words = fold(text == null ? "" : text).trim().split("\\s+");
        List<String> found = new ArrayList<>();
        for (String name : names) {
            String folded = fold(name);
            boolean hasEveryWord = true;
            for (String word : words) {
                hasEveryWord &= folded.contains(word);
            }
            if (hasEveryWord) {
                found.add(name);
            }
        }
        return found;
    }

    @NonNull
    private static String fold(@NonNull String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    public static boolean isIn(@NonNull BlobDescriptor descriptor, @NonNull String folder) {
        return descriptor.folders != null && descriptor.folders.contains(folder);
    }

    public static boolean isUnfiled(@NonNull BlobDescriptor descriptor) {
        return descriptor.folders == null || descriptor.folders.isEmpty();
    }

    @NonNull
    public static Map<String, Integer> count(@NonNull Collection<BlobDescriptor> descriptors) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BlobDescriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.folders == null) {
                continue;
            }
            for (String folder : new LinkedHashSet<>(descriptor.folders)) {
                if (folder != null && !folder.isEmpty()) {
                    counts.merge(folder, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    public static int countUnfiled(@NonNull Collection<BlobDescriptor> descriptors) {
        int count = 0;
        for (BlobDescriptor descriptor : descriptors) {
            if (descriptor != null && isUnfiled(descriptor)) {
                count++;
            }
        }
        return count;
    }

    public static boolean update(@NonNull BlobDescriptor descriptor, @NonNull Collection<String> add,
                                 @NonNull Collection<String> remove) {
        Set<String> updated = descriptor.folders == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(descriptor.folders);
        updated.removeAll(remove);
        for (String folder : add) {
            if (folder != null && !folder.isEmpty()) {
                updated.add(folder);
            }
        }
        List<String> result = new ArrayList<>(updated);
        if (result.equals(descriptor.folders)) {
            return false;
        }
        descriptor.folders = result;
        return true;
    }

    @NonNull
    public static List<BlobDescriptor> rename(@NonNull Collection<BlobDescriptor> descriptors,
                                              @NonNull String oldName, @NonNull String newName) {
        List<BlobDescriptor> changed = new ArrayList<>();
        if (oldName.equals(newName)) {
            return changed;
        }
        for (BlobDescriptor descriptor : descriptors) {
            if (descriptor != null && isIn(descriptor, oldName)) {
                Set<String> updated = new LinkedHashSet<>();
                for (String folder : descriptor.folders) {
                    updated.add(oldName.equals(folder) ? newName : folder);
                }
                descriptor.folders = new ArrayList<>(updated);
                changed.add(descriptor);
            }
        }
        return changed;
    }

    @NonNull
    public static List<BlobDescriptor> remove(@NonNull Collection<BlobDescriptor> descriptors,
                                              @NonNull String name) {
        List<BlobDescriptor> changed = new ArrayList<>();
        for (BlobDescriptor descriptor : descriptors) {
            if (descriptor != null && isIn(descriptor, name)) {
                update(descriptor, Collections.emptyList(), Collections.singleton(name));
                changed.add(descriptor);
            }
        }
        return changed;
    }

    public static boolean setNote(@NonNull BlobDescriptor descriptor, @Nullable String note) {
        String trimmed = note == null || note.trim().isEmpty() ? null : note.trim();
        if (Objects.equals(trimmed, descriptor.note)) {
            return false;
        }
        descriptor.note = trimmed;
        return true;
    }

    public static boolean hasAnnotations(@NonNull BlobDescriptor descriptor) {
        return !isUnfiled(descriptor) || (descriptor.note != null && !descriptor.note.isEmpty());
    }

    // What is stored wins: folders are only added, and the imported note only fills an empty one
    public static boolean mergeInto(@NonNull BlobDescriptor stored, @NonNull BlobDescriptor imported) {
        boolean changed = !isUnfiled(imported)
                && update(stored, imported.folders, Collections.emptyList());
        if (stored.note == null || stored.note.isEmpty()) {
            changed |= setNote(stored, imported.note);
        }
        return changed;
    }
}

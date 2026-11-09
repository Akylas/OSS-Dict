package itkach.aard2.utils;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import itkach.aard2.MainActivity;
import itkach.aard2.R;
import itkach.aard2.SlobHelper;
import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.prefs.AppPrefs;

/**
 * Publishes the most recent history words as launcher (dynamic) shortcuts, so a launcher can
 * jump straight to a word. Opt-in through {@link AppPrefs#recentWordShortcuts()}.
 */
public final class RecentShortcuts {
    private static final String TAG = RecentShortcuts.class.getSimpleName();
    private static final String ID_PREFIX = "recent:";
    /** Leaves room for the static lookup shortcut under the lowest launcher limit. */
    public static final int MAX_SHORTCUTS = 4;

    private RecentShortcuts() {
    }

    /**
     * Returns up to {@code max} distinct words, most recently accessed first. Entries without a
     * key are skipped.
     */
    @NonNull
    public static List<String> selectRecentWords(@NonNull List<BlobDescriptor> history, int max) {
        //The history is read while other threads may still be changing it, hence the null check
        List<BlobDescriptor> sorted = new ArrayList<>(history.size());
        for (BlobDescriptor descriptor : history) {
            if (descriptor != null) {
                sorted.add(descriptor);
            }
        }
        Collections.sort(sorted, (first, second) -> Long.compare(second.lastAccess, first.lastAccess));
        Set<String> words = new LinkedHashSet<>();
        for (BlobDescriptor descriptor : sorted) {
            if (words.size() >= max) {
                break;
            }
            if (descriptor.key != null && !descriptor.key.trim().isEmpty()) {
                words.add(descriptor.key);
            }
        }
        return new ArrayList<>(words);
    }

    /**
     * Rebuilds the shortcuts from the current history, off the calling thread. Cheap enough to
     * call on every history change: runs are serialized and each one reads the history when it
     * starts, so the last run always reflects the latest state (clearing the history notifies
     * once per removed entry from a background thread, and the pool does not keep order).
     */
    public static void refreshAsync(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        ThreadUtils.postOnBackgroundThread(() -> {
            synchronized (RecentShortcuts.class) {
                refresh(appContext);
            }
        });
    }

    private static void refresh(@NonNull Context context) {
        try {
            //The launcher limit also counts the static lookup shortcut
            int max = Math.min(MAX_SHORTCUTS,
                    ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - 1);
            List<String> words = AppPrefs.recentWordShortcuts() && max > 0
                    ? selectRecentWords(new ArrayList<>(SlobHelper.getInstance().history.getList()), max)
                    : Collections.emptyList();
            if (words.isEmpty()) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context);
                return;
            }
            List<ShortcutInfoCompat> shortcuts = new ArrayList<>(words.size());
            for (int rank = 0; rank < words.size(); rank++) {
                String word = words.get(rank);
                Intent intent = new Intent(Intent.ACTION_SEARCH)
                        .setClass(context, MainActivity.class)
                        .putExtra(SearchManager.QUERY, word);
                shortcuts.add(new ShortcutInfoCompat.Builder(context, ID_PREFIX + word)
                        .setShortLabel(word)
                        .setLongLabel(word)
                        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_search))
                        .setIntent(intent)
                        .setRank(rank)
                        .build());
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts);
        } catch (IllegalStateException | IllegalArgumentException e) {
            //The shortcut manager refuses calls when it is rate limited or the user is locked
            Log.w(TAG, "Could not update recent word shortcuts", e);
        }
    }
}

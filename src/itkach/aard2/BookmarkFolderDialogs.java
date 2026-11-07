package itkach.aard2;

import android.content.Context;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.descriptor.BookmarkFolders;
import itkach.aard2.prefs.BookmarkFolderPrefs;

public final class BookmarkFolderDialogs {

    private interface OnEdited {
        void onEdited(@NonNull Set<String> addFolders, @NonNull Set<String> removeFolders,
                      @Nullable String note);
    }

    private BookmarkFolderDialogs() {
    }

    @NonNull
    public static List<String> getAllFolderNames() {
        Set<String> names = BookmarkFolderPrefs.getNames();
        names.addAll(SlobHelper.getInstance().bookmarks.getFolderCounts().keySet());
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted, Collator.getInstance());
        return sorted;
    }

    @MainThread
    public static void editArticle(@NonNull Context context, @NonNull Uri articleUrl,
                                   @Nullable Runnable onSaved) {
        BlobDescriptorList bookmarks = SlobHelper.getInstance().bookmarks;
        BlobDescriptor bookmark = bookmarks.find(articleUrl);
        BlobDescriptor article = bookmark != null ? bookmark : BlobDescriptor.fromUri(articleUrl);
        List<BlobDescriptor> current = bookmark == null
                ? Collections.emptyList() : Collections.singletonList(bookmark);
        showEditDialog(context, article == null ? null : article.key, current, true,
                (addFolders, removeFolders, note) -> {
                    // Looked up again: the bookmark may have come or gone while the dialog was open
                    BlobDescriptor target = bookmarks.find(articleUrl);
                    if (target == null) {
                        if (addFolders.isEmpty() && (note == null || note.trim().isEmpty())) {
                            return;
                        }
                        target = bookmarks.add(articleUrl);
                        if (target == null) {
                            return;
                        }
                    }
                    bookmarks.annotate(Collections.singletonList(target), addFolders, removeFolders, note);
                }, onSaved);
    }

    @MainThread
    public static void editBookmarks(@NonNull Context context, @NonNull List<BlobDescriptor> targets,
                                     @Nullable Runnable onSaved) {
        if (targets.isEmpty()) {
            return;
        }
        boolean single = targets.size() == 1;
        String title = single ? targets.get(0).key
                : context.getString(R.string.specified_number_of_items_selected, targets.size());
        showEditDialog(context, title, targets, single, (addFolders, removeFolders, note) ->
                SlobHelper.getInstance().bookmarks.annotate(targets, addFolders, removeFolders, note),
                onSaved);
    }

    private static void showEditDialog(@NonNull Context context, @Nullable CharSequence title,
                                       @NonNull List<BlobDescriptor> current, boolean withNote,
                                       @NonNull OnEdited onEdited, @Nullable Runnable onSaved) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        View content = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_bookmark_edit, null);
        EditText noteInput = content.findViewById(R.id.bookmark_note_input);
        ViewGroup folderContainer = content.findViewById(R.id.bookmark_folders_container);
        View noFoldersLabel = content.findViewById(R.id.bookmark_folders_empty);
        boolean[] folderCreated = {false};

        if (withNote) {
            String note = current.isEmpty() ? null : current.get(0).note;
            noteInput.setText(note == null ? "" : note);
        } else {
            content.findViewById(R.id.bookmark_note_layout).setVisibility(View.GONE);
        }
        for (String name : getAllFolderNames()) {
            addFolderCheckBox(folderContainer, name, getFolderState(current, name));
        }
        noFoldersLabel.setVisibility(folderContainer.getChildCount() == 0 ? View.VISIBLE : View.GONE);
        content.findViewById(R.id.bookmark_new_folder).setOnClickListener(view ->
                promptFolderName(context, R.string.folders_new, null, name -> {
                    MaterialCheckBox existing = findFolderCheckBox(folderContainer, name);
                    if (existing != null) {
                        existing.setChecked(true);
                        return;
                    }
                    BookmarkFolderPrefs.add(name);
                    folderCreated[0] = true;
                    addFolderCheckBox(folderContainer, name, MaterialCheckBox.STATE_CHECKED);
                    noFoldersLabel.setVisibility(View.GONE);
                }));

        AlertDialog editDialog = builder.setTitle(title)
                .setView(content)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    Set<String> addFolders = new LinkedHashSet<>();
                    Set<String> removeFolders = new LinkedHashSet<>();
                    for (int index = 0; index < folderContainer.getChildCount(); index++) {
                        MaterialCheckBox checkBox = (MaterialCheckBox) folderContainer.getChildAt(index);
                        String name = (String) checkBox.getTag();
                        int state = checkBox.getCheckedState();
                        // Indeterminate (only some of the bookmarks are in it) is left as it is
                        if (state == MaterialCheckBox.STATE_CHECKED) {
                            addFolders.add(name);
                        } else if (state == MaterialCheckBox.STATE_UNCHECKED) {
                            removeFolders.add(name);
                        }
                    }
                    onEdited.onEdited(addFolders, removeFolders,
                            withNote ? noteInput.getText().toString() : null);
                    if (onSaved != null) {
                        onSaved.run();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .setOnDismissListener(dialog -> {
                    // A folder created here stays even if the edit is cancelled
                    if (folderCreated[0]) {
                        SlobHelper.getInstance().bookmarks.notifyDataSetChanged();
                    }
                })
                .create();
        if (withNote) {
            noteInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable text) {
                    // A note being typed is not lost to a stray tap next to the dialog
                    editDialog.setCanceledOnTouchOutside(false);
                }
            });
        }
        editDialog.show();
    }

    private static int getFolderState(@NonNull List<BlobDescriptor> current, @NonNull String folder) {
        int inFolder = 0;
        for (BlobDescriptor bookmark : current) {
            if (BookmarkFolders.isIn(bookmark, folder)) {
                inFolder++;
            }
        }
        if (inFolder == 0) {
            return MaterialCheckBox.STATE_UNCHECKED;
        }
        return inFolder == current.size()
                ? MaterialCheckBox.STATE_CHECKED : MaterialCheckBox.STATE_INDETERMINATE;
    }

    private static void addFolderCheckBox(@NonNull ViewGroup container, @NonNull String name, int state) {
        MaterialCheckBox checkBox = new MaterialCheckBox(container.getContext());
        checkBox.setText(name);
        checkBox.setTag(name);
        checkBox.setCheckedState(state);
        container.addView(checkBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Nullable
    private static MaterialCheckBox findFolderCheckBox(@NonNull ViewGroup container, @NonNull String name) {
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            if (child instanceof MaterialCheckBox && name.equalsIgnoreCase((String) child.getTag())) {
                return (MaterialCheckBox) child;
            }
        }
        return null;
    }

    @MainThread
    public static void showManageDialog(@NonNull Context context) {
        List<String> names = getAllFolderNames();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.folders_manage)
                .setPositiveButton(R.string.folders_new, (dialog, which) -> promptNewFolder(context))
                .setNegativeButton(R.string.action_cancel, null);
        if (names.isEmpty()) {
            builder.setMessage(R.string.folders_none_yet);
        } else {
            builder.setItems(names.toArray(new String[0]),
                    (dialog, which) -> showFolderActions(context, names.get(which)));
        }
        builder.show();
    }

    @MainThread
    public static void showSearchDialog(@NonNull Context context, @NonNull Consumer<String> onPicked) {
        List<String> names = getAllFolderNames();
        Map<String, Integer> counts = SlobHelper.getInstance().bookmarks.getFolderCounts();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        View content = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_folder_search, null);
        EditText input = content.findViewById(R.id.folder_search_input);
        ListView list = content.findViewById(R.id.folder_search_list);
        ArrayAdapter<String> found = new ArrayAdapter<String>(builder.getContext(),
                R.layout.folder_search_item, new ArrayList<>(names)) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                String name = getItem(position);
                Integer count = counts.get(name);
                row.setText(context.getString(R.string.folders_chip_label, name, count == null ? 0 : count));
                return row;
            }
        };
        list.setAdapter(found);
        list.setEmptyView(content.findViewById(R.id.folder_search_empty));
        int inset = context.getResources().getDimensionPixelSize(R.dimen.folder_search_inset);
        AlertDialog dialog = builder.setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .setBackgroundInsetTop(inset)
                .setBackgroundInsetBottom(inset)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String name = found.getItem(position);
            dialog.dismiss();
            if (name != null) {
                onPicked.accept(name);
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                found.clear();
                found.addAll(BookmarkFolders.search(names, text.toString()));
            }
        });
        Window window = dialog.getWindow();
        if (window != null) {
            // At the top, so that the field stays put while the folders found come and go
            window.setGravity(Gravity.TOP);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        input.requestFocus();
        dialog.show();
    }

    @MainThread
    public static void showFolderActions(@NonNull Context context, @NonNull String folder) {
        String[] actions = {context.getString(R.string.action_rename), context.getString(R.string.delete)};
        new MaterialAlertDialogBuilder(context)
                .setTitle(folder)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        promptRenameFolder(context, folder);
                    } else {
                        confirmDeleteFolder(context, folder);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @MainThread
    public static void promptNewFolder(@NonNull Context context) {
        promptFolderName(context, R.string.folders_new, null, name -> {
            if (BookmarkFolders.findIgnoreCase(getAllFolderNames(), name) != null) {
                Toast.makeText(context, R.string.folders_exists, Toast.LENGTH_SHORT).show();
                return;
            }
            BookmarkFolderPrefs.add(name);
            SlobHelper.getInstance().bookmarks.notifyDataSetChanged();
        });
    }

    private static void promptRenameFolder(@NonNull Context context, @NonNull String oldName) {
        promptFolderName(context, R.string.folders_rename_title, oldName, newName -> {
            if (newName.equals(oldName)) {
                return;
            }
            String clash = BookmarkFolders.findIgnoreCase(getAllFolderNames(), newName);
            // Changing only the case of the name clashes with the folder itself
            if (clash != null && !clash.equals(oldName)) {
                Toast.makeText(context, R.string.folders_exists, Toast.LENGTH_SHORT).show();
                return;
            }
            BookmarkFolderPrefs.rename(oldName, newName);
            SlobHelper.getInstance().bookmarks.renameFolder(oldName, newName);
        });
    }

    private static void confirmDeleteFolder(@NonNull Context context, @NonNull String folder) {
        new MaterialAlertDialogBuilder(context)
                .setMessage(context.getString(R.string.folders_confirm_delete, folder))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    BookmarkFolderPrefs.remove(folder);
                    SlobHelper.getInstance().bookmarks.deleteFolder(folder);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void promptFolderName(@NonNull Context context, @StringRes int title,
                                         @Nullable String initialName, @NonNull Consumer<String> onNamed) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        View content = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_folder_name, null);
        EditText input = content.findViewById(R.id.folder_name_input);
        if (initialName != null) {
            input.setText(initialName);
            input.setSelection(input.getText().length());
        }
        AlertDialog dialog = builder.setTitle(title)
                .setView(content)
                .setPositiveButton(R.string.action_save, (dialogInterface, which) -> {
                    String name = BookmarkFolders.normalizeName(input.getText().toString());
                    if (name != null) {
                        onNamed.accept(name);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        input.requestFocus();
        dialog.show();
    }
}

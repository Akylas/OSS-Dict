package itkach.aard2;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuCompat;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.utils.ThreadUtils;

abstract class BlobDescriptorListFragment extends BaseListFragment implements ActionMode.Callback {
    protected ActionMode actionMode;

    private Drawable icClock;
    private Drawable icList;
    private Drawable icArrowUp;
    private Drawable icArrowDown;

    private BlobDescriptorListAdapter listAdapter;
    private AlertDialog deleteConfirmationDialog = null;

    private final static String PREF_SORT_ORDER = "sortOrder";
    private final static String PREF_SORT_DIRECTION = "sortDir";
    private final static String PREF_FOLDER = "folder";

    private static final Object ALL_FOLDERS = new Object();

    private static final int MENU_GROUP_FOLDERS = 1;
    private static final int MENU_GROUP_ACTIONS = 2;
    private static final int MENU_GROUP_SEARCH = 3;
    // Fewer folders fit on one screen of the folder list
    private static final int FOLDER_SEARCH_MIN_FOLDERS = 8;

    private static final class FolderEntry {
        // A folder name, ALL_FOLDERS or BlobDescriptorList.FOLDER_UNFILED
        @NonNull
        final Object tag;
        @NonNull
        final String label;

        FolderEntry(@NonNull Object tag, @NonNull String label) {
            this.tag = tag;
            this.label = label;
        }

        boolean isUserFolder() {
            return tag != ALL_FOLDERS && !BlobDescriptorList.FOLDER_UNFILED.equals(tag);
        }
    }

    private MenuItem miFilter = null;

    @Nullable
    private View folderBar;
    @Nullable
    private ChipGroup folderChips;
    @Nullable
    private HorizontalScrollView folderScroll;
    @Nullable
    private String folderChipsContent;
    @Nullable
    private Object folderChipShown;
    @Nullable
    private Object folderChipToReveal;
    private final DataSetObserver folderObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            // The list also changes on background threads
            ThreadUtils.postOnMainThread(BlobDescriptorListFragment.this::refreshFolderSelector);
        }
    };

    abstract BlobDescriptorList getDescriptorList();

    abstract String getItemClickAction();

    abstract int getDeleteConfirmationItemCountResId();

    abstract String getPreferencesNS();

    boolean supportsFolders() {
        return false;
    }

    @NonNull
    private SharedPreferences prefs() {
        return requireActivity().getSharedPreferences(getPreferencesNS(), Activity.MODE_PRIVATE);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final AppCompatActivity activity = (AppCompatActivity) requireActivity();

        BlobDescriptorList descriptorList = getDescriptorList();

        SharedPreferences p = this.prefs();

        String sortOrderStr = p.getString(PREF_SORT_ORDER,
                BlobDescriptorList.SortOrder.TIME.name());
        BlobDescriptorList.SortOrder sortOrder = BlobDescriptorList.SortOrder.valueOf(sortOrderStr);

        boolean sortDir = p.getBoolean(PREF_SORT_DIRECTION, false);

        descriptorList.setSort(sortOrder, sortDir);

        if (supportsFolders()) {
            descriptorList.setFolderFilter(p.getString(PREF_FOLDER, null));
            folderChips = view.findViewById(R.id.folder_chips);
            folderScroll = view.findViewById(R.id.folder_scroll);
            folderBar = view.findViewById(R.id.folder_bar);
            View folderMenu = view.findViewById(R.id.folder_menu);
            TooltipCompat.setTooltipText(folderMenu, folderMenu.getContentDescription());
            folderMenu.setOnClickListener(this::showFolderMenu);
            folderChips.addOnLayoutChangeListener((chips, left, top, right, bottom,
                                                   oldLeft, oldTop, oldRight, oldBottom) ->
                    chips.post(this::revealShownFolder));
            descriptorList.registerDataSetObserver(folderObserver);
            refreshFolderSelector();
        }

        listAdapter = new BlobDescriptorListAdapter(descriptorList, getItemClickAction());
        listAdapter.setOnSelectionStartedListener(new BlobDescriptorListAdapter.OnSelectionChangeListener() {
            @Override
            public void selectionStarted() {
                activity.startSupportActionMode(BlobDescriptorListFragment.this);
            }

            @Override
            public void selectionChanged(int selectionCount) {
                if (actionMode != null) {
                    actionMode.setTitle(getString(R.string.specified_number_of_items_selected, selectionCount));
                }
            }

            @Override
            public void selectionCanceled() {
                finishActionMode();
            }
        });

        icClock = ContextCompat.getDrawable(activity, R.drawable.ic_clock_time_nine);
        icList = ContextCompat.getDrawable(activity, R.drawable.ic_format_list_bulleted);
        icArrowUp = ContextCompat.getDrawable(activity, R.drawable.ic_sort_ascending);
        icArrowDown = ContextCompat.getDrawable(activity, R.drawable.ic_sort_descending);

        recyclerView.setAdapter(listAdapter);
    }

    @Override
    public void onDestroyView() {
        if (folderChips != null) {
            getDescriptorList().unregisterDataSetObserver(folderObserver);
            folderBar = null;
            folderChips = null;
            folderScroll = null;
            folderChipsContent = null;
            folderChipShown = null;
            folderChipToReveal = null;
        }
        super.onDestroyView();
    }

    private void refreshFolderSelector() {
        ChipGroup chips = folderChips;
        if (chips == null || folderBar == null || getContext() == null) {
            return;
        }
        // Hidden until the bookmarks are loaded: changes to folders made before would miss them
        BlobDescriptorList list = getDescriptorList();
        folderBar.setVisibility(list.isLoaded() ? View.VISIBLE : View.GONE);
        if (!list.isLoaded()) {
            return;
        }
        String shown = list.getFolderFilter();
        List<FolderEntry> entries = getFolderEntries();
        StringBuilder content = new StringBuilder();
        for (FolderEntry entry : entries) {
            content.append(entry.tag).append('\t').append(entry.label).append('\n');
        }
        // Only rebuilt when they change, as that resets the scroll position
        if (!content.toString().equals(folderChipsContent)) {
            folderChipsContent = content.toString();
            chips.removeAllViews();
            for (FolderEntry entry : entries) {
                addFolderChip(chips, entry);
            }
            Chip newFolderChip = new Chip(chips.getContext());
            newFolderChip.setText(R.string.folders_new);
            newFolderChip.setChipIconResource(R.drawable.ic_add);
            newFolderChip.setChipIconVisible(true);
            newFolderChip.setCheckable(false);
            newFolderChip.setOnClickListener(view -> BookmarkFolderDialogs.promptNewFolder(requireActivity()));
            chips.addView(newFolderChip);
        }

        Object shownTag = shown == null ? ALL_FOLDERS : shown;
        for (int index = 0; index < chips.getChildCount(); index++) {
            View child = chips.getChildAt(index);
            if (child instanceof Chip && shownTag.equals(child.getTag())) {
                ((Chip) child).setChecked(true);
            }
        }
        if (!shownTag.equals(folderChipShown)) {
            folderChipShown = shownTag;
            folderChipToReveal = shownTag;
            chips.post(this::revealShownFolder);
        }

        // Saved here, as the folder shown also changes when it is renamed or deleted
        SharedPreferences.Editor editor = prefs().edit();
        if (shown == null) {
            editor.remove(PREF_FOLDER);
        } else {
            editor.putString(PREF_FOLDER, shown);
        }
        editor.apply();

        TextView emptyText = emptyView.findViewById(R.id.empty_text);
        emptyText.setText(shown == null || BlobDescriptorList.FOLDER_UNFILED.equals(shown)
                ? getEmptyText() : getString(R.string.folders_empty_folder));
    }

    // Waits for the chips to be laid out: it runs again after every layout of the row
    private void revealShownFolder() {
        ChipGroup chips = folderChips;
        HorizontalScrollView bar = folderScroll;
        Object tag = folderChipToReveal;
        if (chips == null || bar == null || tag == null) {
            return;
        }
        for (int index = 0; index < chips.getChildCount(); index++) {
            View chip = chips.getChildAt(index);
            if (!tag.equals(chip.getTag())) {
                continue;
            }
            if (!chip.isLaidOut() || chip.isLayoutRequested()) {
                return;
            }
            folderChipToReveal = null;
            int margin = bar.getHorizontalFadingEdgeLength();
            int left = Math.max(0, chips.getLeft() + chip.getLeft() - margin);
            int right = chips.getLeft() + chip.getRight() + margin;
            if (left < bar.getScrollX()) {
                bar.smoothScrollTo(left, 0);
            } else if (right > bar.getScrollX() + bar.getWidth()) {
                bar.smoothScrollTo(right - bar.getWidth(), 0);
            }
            return;
        }
    }

    @NonNull
    private List<FolderEntry> getFolderEntries() {
        BlobDescriptorList list = getDescriptorList();
        String shown = list.getFolderFilter();
        boolean showsUnfiled = BlobDescriptorList.FOLDER_UNFILED.equals(shown);
        List<String> names = BookmarkFolderDialogs.getAllFolderNames();
        if (shown != null && !showsUnfiled && !names.contains(shown)) {
            names.add(shown);
        }
        Map<String, Integer> counts = list.getFolderCounts();
        int total = list.getTotalCount();
        int unfiled = list.getUnfiledCount();
        List<FolderEntry> entries = new ArrayList<>();
        entries.add(new FolderEntry(ALL_FOLDERS, getFolderLabel(getString(R.string.folders_all), total)));
        for (String name : names) {
            Integer count = counts.get(name);
            entries.add(new FolderEntry(name, getFolderLabel(name, count == null ? 0 : count)));
        }
        // With all bookmarks or none in no folder, "No folder" would only repeat "All" or show nothing
        if (showsUnfiled || (unfiled > 0 && unfiled < total)) {
            entries.add(new FolderEntry(BlobDescriptorList.FOLDER_UNFILED,
                    getFolderLabel(getString(R.string.folders_unfiled), unfiled)));
        }
        return entries;
    }

    @NonNull
    private String getFolderLabel(@NonNull String name, int count) {
        return getString(R.string.folders_chip_label, name, count);
    }

    private void showFolder(@NonNull FolderEntry entry) {
        getDescriptorList().setFolderFilter(entry.tag == ALL_FOLDERS ? null : (String) entry.tag);
    }

    private void addFolderChip(@NonNull ChipGroup chips, @NonNull FolderEntry entry) {
        Chip chip = (Chip) LayoutInflater.from(chips.getContext()).inflate(R.layout.folder_chip, chips, false);
        chip.setText(entry.label);
        chip.setTag(entry.tag);
        chip.setOnClickListener(view -> showFolder(entry));
        if (entry.isUserFolder()) {
            chip.setOnLongClickListener(view -> {
                BookmarkFolderDialogs.showFolderActions(requireActivity(), (String) entry.tag);
                return true;
            });
        }
        chips.addView(chip);
    }

    private void showFolderMenu(@NonNull View anchor) {
        String shown = getDescriptorList().getFolderFilter();
        Object shownTag = shown == null ? ALL_FOLDERS : shown;
        // The activity's context: the anchor's would give the menu the colours of a chip
        PopupMenu popup = new PopupMenu(requireActivity(), anchor, Gravity.END);
        Menu menu = popup.getMenu();
        List<FolderEntry> entries = getFolderEntries();
        int userFolders = 0;
        for (FolderEntry entry : entries) {
            if (entry.isUserFolder()) {
                userFolders++;
            }
        }
        if (userFolders >= FOLDER_SEARCH_MIN_FOLDERS) {
            menu.add(MENU_GROUP_SEARCH, Menu.NONE, Menu.NONE, R.string.folders_search)
                    .setOnMenuItemClickListener(picked -> {
                        BookmarkFolderDialogs.showSearchDialog(requireActivity(),
                                folder -> getDescriptorList().setFolderFilter(folder));
                        return true;
                    });
        }
        TextPaint titlePaint = getMenuTitlePaint();
        float titleRoom = getResources().getDimension(R.dimen.folder_menu_title_max_width);
        MenuItem shownItem = null;
        for (FolderEntry entry : entries) {
            // A menu cuts titles off at the end, and with them the count: shortened in the
            // middle, long names that begin alike also stay apart
            CharSequence title = TextUtils.ellipsize(entry.label, titlePaint, titleRoom,
                    TextUtils.TruncateAt.MIDDLE);
            MenuItem item = menu.add(MENU_GROUP_FOLDERS, Menu.NONE, Menu.NONE, title);
            item.setOnMenuItemClickListener(picked -> {
                showFolder(entry);
                return true;
            });
            if (shownTag.equals(entry.tag)) {
                shownItem = item;
            }
        }
        menu.setGroupCheckable(MENU_GROUP_FOLDERS, true, true);
        if (shownItem != null) {
            shownItem.setChecked(true);
        }
        menu.add(MENU_GROUP_ACTIONS, Menu.NONE, Menu.NONE, R.string.folders_new)
                .setOnMenuItemClickListener(picked -> {
                    BookmarkFolderDialogs.promptNewFolder(requireActivity());
                    return true;
                });
        if (userFolders > 0) {
            menu.add(MENU_GROUP_ACTIONS, Menu.NONE, Menu.NONE, R.string.folders_manage)
                    .setOnMenuItemClickListener(picked -> {
                        BookmarkFolderDialogs.showManageDialog(requireActivity());
                        return true;
                    });
        }
        MenuCompat.setGroupDividerEnabled(menu, true);
        popup.show();
    }

    @NonNull
    private TextPaint getMenuTitlePaint() {
        TypedArray attributes = requireActivity().obtainStyledAttributes(
                new int[]{androidx.appcompat.R.attr.textAppearanceLargePopupMenu});
        int appearance = attributes.getResourceId(0, 0);
        attributes.recycle();
        TextView sizer = new TextView(requireActivity());
        if (appearance != 0) {
            TextViewCompat.setTextAppearance(sizer, appearance);
        }
        return sizer.getPaint();
    }

    @NonNull
    private List<BlobDescriptor> getSelectedItems() {
        List<BlobDescriptor> selected = new ArrayList<>();
        SparseBooleanArray checkedItems = listAdapter.getCheckedItemPositions();
        for (int index = 0; index < checkedItems.size(); index++) {
            int position = checkedItems.keyAt(index);
            if (checkedItems.valueAt(index) && position >= 0 && position < listAdapter.getItemCount()) {
                selected.add(listAdapter.getItem(position));
            }
        }
        return selected;
    }

    protected void deleteSelectedItems() {
        SparseBooleanArray checkedItems = listAdapter.getCheckedItemPositions();
        for (int i = checkedItems.size() - 1; i > -1; --i) {
            int position = checkedItems.keyAt(i);
            boolean checked = checkedItems.valueAt(i);
            if (checked) {
                getDescriptorList().remove(position);
            }
        }
    }

    public void finishActionMode() {
        if (actionMode != null) {
            actionMode.finish();
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.blob_descriptor_list, menu);
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        BlobDescriptorList list = getDescriptorList();

        miFilter = menu.findItem(R.id.action_filter);

        View filterActionView = miFilter.getActionView();
        SearchView searchView = filterActionView
                .findViewById(R.id.search);
        searchView.setQueryHint(miFilter.getTitle());
        searchView.setQuery(list.getFilter(), true);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                BlobDescriptorList list = getDescriptorList();
                if (!newText.equals(list.getFilter())) {
                    getDescriptorList().setFilter(newText);
                }
                return true;
            }
        });
        setSortOrder(menu.findItem(R.id.action_sort_order), list.getSortOrder());
        setAscending(menu.findItem(R.id.action_sort_asc), list.isAscending());

        super.onPrepareOptionsMenu(menu);
    }

    private void setSortOrder(MenuItem mi, BlobDescriptorList.SortOrder order) {
        Drawable icon;
        int textRes;
        if (order == BlobDescriptorList.SortOrder.TIME) {
            icon = icClock;
            textRes = R.string.action_sort_by_time;
        } else {
            icon = icList;
            textRes = R.string.action_sort_by_title;
        }
        mi.setIcon(icon);
        mi.setTitle(textRes);
        SharedPreferences p = this.prefs();
        SharedPreferences.Editor editor = p.edit();
        editor.putString(PREF_SORT_ORDER, order.name());
        editor.apply();
    }

    private void setAscending(MenuItem mi, boolean ascending) {
        Drawable icon;
        int textRes;
        if (ascending) {
            icon = icArrowUp;
            textRes = R.string.action_ascending;
        } else {
            icon = icArrowDown;
            textRes = R.string.action_descending;
        }
        mi.setIcon(icon);
        mi.setTitle(textRes);
        SharedPreferences p = this.prefs();
        SharedPreferences.Editor editor = p.edit();
        editor.putBoolean(PREF_SORT_DIRECTION, ascending);
        editor.apply();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem mi) {
        BlobDescriptorList list = getDescriptorList();
        int itemId = mi.getItemId();
        if (itemId == R.id.action_sort_asc) {
            list.setSort(!list.isAscending());
            setAscending(mi, list.isAscending());
            return true;
        }
        if (itemId == R.id.action_sort_order) {
            if (list.getSortOrder() == BlobDescriptorList.SortOrder.TIME) {
                list.setSort(BlobDescriptorList.SortOrder.NAME);
            } else {
                list.setSort(BlobDescriptorList.SortOrder.TIME);
            }
            setSortOrder(mi, list.getSortOrder());
            return true;
        }
        return super.onOptionsItemSelected(mi);
    }


    @Override
    public void onPause() {
        super.onPause();
        if (deleteConfirmationDialog != null) {
            deleteConfirmationDialog.dismiss();
        }
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        actionMode = mode;
        if (mode != null) {
            mode.getMenuInflater().inflate(R.menu.blob_descriptor_selection, menu);
            menu.findItem(R.id.blob_descriptor_edit_folders).setVisible(supportsFolders());
        }
        listAdapter.setSelectionMode(true);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.blob_descriptor_delete) {
            int count = listAdapter.getCheckedItemCount();
            String countStr = getResources().getQuantityString(getDeleteConfirmationItemCountResId(), count, count);
            String message = getString(R.string.blob_descriptor_confirm_delete, countStr);
            deleteConfirmationDialog = new MaterialAlertDialogBuilder(requireActivity())
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle("")
                    .setMessage(message)
                    .setPositiveButton(R.string.action_yes, (dialog, which) -> {
                        deleteSelectedItems();
                        mode.finish();
                        deleteConfirmationDialog = null;
                    })
                    .setNegativeButton(R.string.action_no, null)
                    .create();
            deleteConfirmationDialog.setOnDismissListener(dialogInterface -> deleteConfirmationDialog = null);
            deleteConfirmationDialog.show();
            return true;
        } else if (itemId == R.id.blob_descriptor_edit_folders) {
            BookmarkFolderDialogs.editBookmarks(requireActivity(), getSelectedItems(), mode::finish);
            return true;
        } else if (itemId == R.id.blob_descriptor_select_all) {
            listAdapter.selectAll();
            return true;
        }
        return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        actionMode = null;
        listAdapter.setSelectionMode(false);
    }
}

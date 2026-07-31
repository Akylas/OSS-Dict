package itkach.aard2.prefs;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.text.HtmlCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.webkit.WebViewFeature;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.elevation.SurfaceColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import itkach.aard2.BuildConfig;
import itkach.aard2.R;
import itkach.aard2.utils.Utils;

public class SettingsListAdapter extends RecyclerView.Adapter<SettingsListAdapter.ViewHolder>
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final static String TAG = SettingsListAdapter.class.getSimpleName();

    // View types
    private static final int VIEW_TYPE_SECTION_HEADER = 0;
    private static final int VIEW_TYPE_SWITCH         = 1;
    private static final int VIEW_TYPE_UI_THEME       = 2;
    private static final int VIEW_TYPE_REMOTE_CONTENT = 3;
    private static final int VIEW_TYPE_AUTO_LOAD_FOLDER = 4;
    private static final int VIEW_TYPE_USER_STYLES    = 5;
    private static final int VIEW_TYPE_CLEAR_CACHE    = 6;
    private static final int VIEW_TYPE_ABOUT          = 7;
    private static final int VIEW_TYPE_BACKUP         = 8;

    // Stable item IDs – never change these values
    private static final long ID_SECTION_APPEARANCE   = 101;
    private static final long ID_SECTION_LIBRARY      = 102;
    private static final long ID_SECTION_CONTENT      = 103;
    private static final long ID_SECTION_ARTICLE_VIEW = 104;
    private static final long ID_SECTION_LOOKUP       = 105;
    private static final long ID_SECTION_FEATURES     = 106;
    private static final long ID_SECTION_BACKUP       = 107;

    private static final long ID_UI_THEME             = 201;
    private static final long ID_FORCE_DARK           = 202;
    private static final long ID_DISABLE_TAB_LABELS   = 203;

    private static final long ID_AUTO_LOAD_FOLDER     = 301;
    private static final long ID_AUTO_MOVE_TO_FOLDER  = 302;

    private static final long ID_REMOTE_CONTENT       = 401;
    private static final long ID_REMOTE_CONTENT_CACHE = 402;
    private static final long ID_OPEN_MISSING_BROWSER = 403;

    private static final long ID_DISABLE_JS           = 501;
    private static final long ID_USER_STYLES          = 502;
    private static final long ID_CLEAR_CACHE          = 503;
    private static final long ID_DISABLE_SWIPE_NAV    = 504;

    private static final long ID_SORT_BY_RANK         = 601;
    private static final long ID_AUTO_PASTE           = 602;
    private static final long ID_SHOW_KEYBOARD_LOOKUP = 603;
    private static final long ID_USE_VOLUME_FOR_NAV   = 604;

    private static final long ID_FAV_RANDOM           = 701;
    private static final long ID_DISABLE_RANDOM_LOOKUP = 702;
    private static final long ID_DISABLE_BOOKMARKS    = 703;
    private static final long ID_DISABLE_HISTORY      = 704;

    private static final long ID_ABOUT                = 801;

    private static final long ID_BACKUP               = 901;

    // -------------------------------------------------------------------------
    // Functional interface for boolean preference read/write
    // -------------------------------------------------------------------------
    interface BoolPref {
        boolean get();
        void set(boolean newValue);
    }

    // -------------------------------------------------------------------------
    // Item model hierarchy
    // -------------------------------------------------------------------------
    abstract static class Item {
        abstract int getViewType();
        abstract long getStableId();
    }

    static class SectionHeaderItem extends Item {
        private final long id;
        @StringRes final int titleRes;

        SectionHeaderItem(long id, @StringRes int titleRes) {
            this.id = id;
            this.titleRes = titleRes;
        }

        @Override public int getViewType()   { return VIEW_TYPE_SECTION_HEADER; }
        @Override public long getStableId()  { return id; }
    }

    static class SwitchItem extends Item {
        private final long id;
        @StringRes final int titleRes;
        @StringRes final int subtitleRes;       // 0 = no subtitle
        @StringRes final int disabledHintRes;   // 0 = no disabled hint
        final BoolPref pref;
        @Nullable final BoolPref enabledCheck;  // null = always enabled

        SwitchItem(long id, @StringRes int titleRes, BoolPref pref) {
            this(id, titleRes, 0, 0, pref, null);
        }

        SwitchItem(long id, @StringRes int titleRes, @StringRes int subtitleRes, BoolPref pref) {
            this(id, titleRes, subtitleRes, 0, pref, null);
        }

        SwitchItem(long id, @StringRes int titleRes, @StringRes int subtitleRes,
                @StringRes int disabledHintRes, BoolPref pref, @Nullable BoolPref enabledCheck) {
            this.id = id;
            this.titleRes = titleRes;
            this.subtitleRes = subtitleRes;
            this.disabledHintRes = disabledHintRes;
            this.pref = pref;
            this.enabledCheck = enabledCheck;
        }

        @Override public int getViewType()  { return VIEW_TYPE_SWITCH; }
        @Override public long getStableId() { return id; }
    }

    static class UiThemeItem extends Item {
        @Override public int getViewType()  { return VIEW_TYPE_UI_THEME; }
        @Override public long getStableId() { return ID_UI_THEME; }
    }

    static class RemoteContentItem extends Item {
        @Override public int getViewType()  { return VIEW_TYPE_REMOTE_CONTENT; }
        @Override public long getStableId() { return ID_REMOTE_CONTENT; }
    }

    static class AutoLoadFolderItem extends Item {
        @Override public int getViewType()  { return VIEW_TYPE_AUTO_LOAD_FOLDER; }
        @Override public long getStableId() { return ID_AUTO_LOAD_FOLDER; }
    }

    static class UserStylesItem extends Item {
        @Override public int getViewType()  { return VIEW_TYPE_USER_STYLES; }
        @Override public long getStableId() { return ID_USER_STYLES; }
    }

    static class ClearCacheItem extends Item {
        @Override public int getViewType()  { return VIEW_TYPE_CLEAR_CACHE; }
        @Override public long getStableId() { return ID_CLEAR_CACHE; }
    }

    static class BackupItem extends Item {
        @Override public int getViewType()  { return VIEW_TYPE_BACKUP; }
        @Override public long getStableId() { return ID_BACKUP; }
    }

    static class AboutItem extends Item {
        @Override public int getViewType()  { return VIEW_TYPE_ABOUT; }
        @Override public long getStableId() { return ID_ABOUT; }
    }

    // -------------------------------------------------------------------------
    // Adapter state
    // -------------------------------------------------------------------------
    private final Activity context;
    private final Fragment fragment;
    private final SharedPreferences userStylePrefs;
    private final View.OnClickListener onDeleteUserStyle;
    private final List<Item> items;

    // -------------------------------------------------------------------------
    // Constructor / lifecycle
    // -------------------------------------------------------------------------
    SettingsListAdapter(Fragment fragment) {
        this.fragment = fragment;
        this.context  = fragment.requireActivity();
        this.userStylePrefs = context.getSharedPreferences("userStyles", Activity.MODE_PRIVATE);
        this.onDeleteUserStyle = view -> deleteUserStyle((String) view.getTag());
        this.items = buildItems();
        setHasStableIds(true);

        this.userStylePrefs.registerOnSharedPreferenceChangeListener(this);
        AppPrefs.getPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    public void destroy() {
        userStylePrefs.unregisterOnSharedPreferenceChangeListener(this);
        AppPrefs.getPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    // -------------------------------------------------------------------------
    // Build the ordered item list (sections + items)
    // -------------------------------------------------------------------------
    private List<Item> buildItems() {
        List<Item> list = new ArrayList<>();

        // --- Appearance ---
        list.add(new SectionHeaderItem(ID_SECTION_APPEARANCE, R.string.settings_section_appearance));
        list.add(new UiThemeItem());
        list.add(new SwitchItem(ID_FORCE_DARK, R.string.setting_enable_force_dark_web_view,
                0, 0,
                new BoolPref() {
                    @Override public boolean get() { return ArticleViewPrefs.enableForceDark(); }
                    @Override public void set(boolean v) { ArticleViewPrefs.setEnableForceDark(v); }
                },
                new BoolPref() {
                    @Override public boolean get() {
                        return WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING);
                    }
                    @Override public void set(boolean v) { throw new UnsupportedOperationException(); }
                }));
        list.add(new SwitchItem(ID_DISABLE_TAB_LABELS, R.string.setting_disable_bottom_nav_labels,
                new BoolPref() {
                    @Override public boolean get() { return AppPrefs.disableBottomNavLabels(); }
                    @Override public void set(boolean v) { AppPrefs.setDisableBottomNavLabels(v); }
                }));

        // --- Library ---
        list.add(new SectionHeaderItem(ID_SECTION_LIBRARY, R.string.settings_section_library));
        list.add(new AutoLoadFolderItem());
        list.add(new SwitchItem(ID_AUTO_MOVE_TO_FOLDER, R.string.setting_auto_move_to_folder,
                0, R.string.setting_auto_move_to_folder_subtitle_disabled,
                new BoolPref() {
                    @Override public boolean get() { return AppPrefs.autoMoveToFolder(); }
                    @Override public void set(boolean v) { AppPrefs.setAutoMoveToFolder(v); }
                },
                new BoolPref() {
                    @Override public boolean get() { return !AppPrefs.getAutoLoadDictFolderUri().isEmpty(); }
                    @Override public void set(boolean v) { throw new UnsupportedOperationException(); }
                }));

        // --- Content ---
        list.add(new SectionHeaderItem(ID_SECTION_CONTENT, R.string.settings_section_content));
        list.add(new RemoteContentItem());
        list.add(new SwitchItem(ID_REMOTE_CONTENT_CACHE, R.string.setting_remote_content_cache_first,
                new BoolPref() {
                    @Override public boolean get() { return ArticleViewPrefs.loadRemoteContentOnlyIfNotCached(); }
                    @Override public void set(boolean v) { ArticleViewPrefs.setLoadRemoteContentOnlyIfNotCached(v); }
                }));
        list.add(new SwitchItem(ID_OPEN_MISSING_BROWSER, R.string.setting_open_missing_browser,
                new BoolPref() {
                    @Override public boolean get() { return AppPrefs.openMissingInBrowser(); }
                    @Override public void set(boolean v) { AppPrefs.setOpenMissingInBrowser(v); }
                }));

        // --- Article View ---
        list.add(new SectionHeaderItem(ID_SECTION_ARTICLE_VIEW, R.string.settings_section_article_view));
        list.add(new SwitchItem(ID_DISABLE_JS, R.string.setting_disable_javascript_title,
                R.string.setting_disable_javascript_subtitle,
                new BoolPref() {
                    @Override public boolean get() { return ArticleViewPrefs.disableJavaScript(); }
                    @Override public void set(boolean v) { ArticleViewPrefs.setDisableJavaScript(v); }
                }));
        list.add(new SwitchItem(ID_DISABLE_SWIPE_NAV, R.string.setting_disable_swipe_navigation_title,
                R.string.setting_disable_swipe_navigation_subtitle,
                new BoolPref() {
                    @Override public boolean get() { return ArticleViewPrefs.disableSwipeNavigation(); }
                    @Override public void set(boolean v) { ArticleViewPrefs.setDisableSwipeNavigation(v); }
                }));
        list.add(new UserStylesItem());
        list.add(new ClearCacheItem());

        // --- Lookup ---
        list.add(new SectionHeaderItem(ID_SECTION_LOOKUP, R.string.settings_section_lookup));
        list.add(new SwitchItem(ID_SORT_BY_RANK, R.string.setting_sort_lookup_by_rank,
                new BoolPref() {
                    @Override public boolean get() { return AppPrefs.sortLookupResultsByRank(); }
                    @Override public void set(boolean v) { AppPrefs.setSortLookupResultsByRank(v); }
                }));
        list.add(new SwitchItem(ID_AUTO_PASTE, R.string.setting_auto_paste,
                new BoolPref() {
                    @Override public boolean get() { return AppPrefs.autoPasteInLookup(); }
                    @Override public void set(boolean v) { AppPrefs.setAutoPasteInLookup(v); }
                }));
        list.add(new SwitchItem(ID_SHOW_KEYBOARD_LOOKUP, R.string.setting_show_keyboard_lookup,
                new BoolPref() {
                    @Override public boolean get() { return AppPrefs.showKeyboarOnLookup(); }
                    @Override public void set(boolean v) { AppPrefs.setShowKeyboarOnLookup(v); }
                }));
        list.add(new SwitchItem(ID_USE_VOLUME_FOR_NAV, R.string.setting_use_volume_for_nav,
                new BoolPref() {
                    @Override public boolean get() { return AppPrefs.useVolumeKeysForNavigation(); }
                    @Override public void set(boolean v) { AppPrefs.setUseVolumeKeysForNavigation(v); }
                }));

        // --- Features ---
        list.add(new SectionHeaderItem(ID_SECTION_FEATURES, R.string.settings_section_features));
        list.add(new SwitchItem(ID_FAV_RANDOM, R.string.setting_fav_random_search,
                new BoolPref() {
                    @Override public boolean get() { return AppPrefs.useOnlyFavoritesForRandomLookups(); }
                    @Override public void set(boolean v) { AppPrefs.setUseOnlyFavoritesForRandomLookups(v); }
                }));
        list.add(new SwitchItem(ID_DISABLE_RANDOM_LOOKUP, R.string.setting_disable_random_lookup,
                new BoolPref() {
                    @Override public boolean get() { return AppPrefs.disableRandomLookup(); }
                    @Override public void set(boolean v) { AppPrefs.setDisableRandomLookup(v); }
                }));
        list.add(new SwitchItem(ID_DISABLE_BOOKMARKS, R.string.setting_disable_bookmarks,
                new BoolPref() {
                    @Override public boolean get() { return AppPrefs.disableBookmarks(); }
                    @Override public void set(boolean v) { AppPrefs.setDisableBookmarks(v); }
                }));
        list.add(new SwitchItem(ID_DISABLE_HISTORY, R.string.setting_disable_history,
                new BoolPref() {
                    @Override public boolean get() { return AppPrefs.disableHistory(); }
                    @Override public void set(boolean v) { AppPrefs.setDisableHistory(v); }
                }));

        // --- Backup ---
        list.add(new SectionHeaderItem(ID_SECTION_BACKUP, R.string.settings_section_backup));
        list.add(new BackupItem());

        // --- About (always last) ---
        list.add(new AboutItem());

        return list;
    }

    // -------------------------------------------------------------------------
    // RecyclerView.Adapter implementation
    // -------------------------------------------------------------------------

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).getStableId();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).getViewType();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutRes;
        switch (viewType) {
            case VIEW_TYPE_SECTION_HEADER:   layoutRes = R.layout.settings_section_header;        break;
            case VIEW_TYPE_UI_THEME:         layoutRes = R.layout.settings_ui_theme_item;         break;
            case VIEW_TYPE_REMOTE_CONTENT:   layoutRes = R.layout.settings_remote_content_item;   break;
            case VIEW_TYPE_SWITCH:           layoutRes = R.layout.settings_switch;                break;
            case VIEW_TYPE_AUTO_LOAD_FOLDER: layoutRes = R.layout.settings_auto_load_folder_item; break;
            case VIEW_TYPE_USER_STYLES:      layoutRes = R.layout.settings_user_styles_item;      break;
            case VIEW_TYPE_CLEAR_CACHE:      layoutRes = R.layout.settings_clear_cache_item;      break;
            case VIEW_TYPE_BACKUP:           layoutRes = R.layout.settings_backup_item;           break;
            case VIEW_TYPE_ABOUT:            layoutRes = R.layout.settings_about_item;            break;
            default: throw new RuntimeException("Unknown view type: " + viewType);
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Item item = items.get(position);
        switch (item.getViewType()) {
            case VIEW_TYPE_SECTION_HEADER:   bindSectionHeader(holder, (SectionHeaderItem) item);  break;
            case VIEW_TYPE_SWITCH:           bindSwitch(holder, (SwitchItem) item);                break;
            case VIEW_TYPE_UI_THEME:         bindUiTheme(holder);                                  break;
            case VIEW_TYPE_REMOTE_CONTENT:   bindRemoteContent(holder);                            break;
            case VIEW_TYPE_AUTO_LOAD_FOLDER: bindAutoLoadFolder(holder);                           break;
            case VIEW_TYPE_USER_STYLES:      bindUserStyles(holder);                               break;
            case VIEW_TYPE_CLEAR_CACHE:      bindClearCache(holder);                               break;
            case VIEW_TYPE_BACKUP:           bindBackup(holder);                                   break;
            case VIEW_TYPE_ABOUT:            bindAbout(holder);                                    break;
        }
    }

    // -------------------------------------------------------------------------
    // SharedPreferences change listener
    // -------------------------------------------------------------------------

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("autoLoadDictFolder".equals(key)) {
            notifyItemsByStableIds(ID_AUTO_LOAD_FOLDER, ID_AUTO_MOVE_TO_FOLDER);
            return;
        }
        notifyDataSetChanged();
    }

    /**
     * Notifies items by their stable IDs without relying on position constants.
     */
    private void notifyItemsByStableIds(long... ids) {
        for (int i = 0; i < items.size(); i++) {
            long itemId = items.get(i).getStableId();
            for (long id : ids) {
                if (itemId == id) {
                    notifyItemChanged(i);
                    break;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Bind methods
    // -------------------------------------------------------------------------

    private void bindSectionHeader(@NonNull ViewHolder holder, SectionHeaderItem item) {
        ((TextView) holder.itemView).setText(item.titleRes);
    }

    private void bindSwitch(@NonNull ViewHolder holder, SwitchItem item) {
        View view = holder.itemView;
        MaterialSwitch toggle = view.findViewById(R.id.setting_switch);
        MaterialTextView subtitle = view.findViewById(R.id.setting_subtitle);

        toggle.setText(item.titleRes);

        boolean enabled = item.enabledCheck == null || item.enabledCheck.get();
        toggle.setEnabled(enabled);

        if (!enabled && item.disabledHintRes != 0) {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(item.disabledHintRes);
        } else if (item.subtitleRes != 0) {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(item.subtitleRes);
        } else {
            subtitle.setVisibility(View.GONE);
        }

        // Remove any old listener before setting checked state to avoid spurious triggers
        toggle.setOnClickListener(null);
        toggle.setChecked(item.pref.get());
        toggle.setOnClickListener(v -> {
            boolean newValue = !item.pref.get();
            item.pref.set(newValue);
            toggle.setChecked(newValue);
        });
    }

    private void bindUiTheme(@NonNull ViewHolder holder) {
        View view = holder.itemView;
        String currentValue = AppPrefs.getPreferredTheme();

        View.OnClickListener clickListener = v -> {
            String value = null;
            int id = v.getId();
            if (id == R.id.setting_ui_theme_auto)       value = AppPrefs.PREF_UI_THEME_AUTO;
            else if (id == R.id.setting_ui_theme_light) value = AppPrefs.PREF_UI_THEME_LIGHT;
            else if (id == R.id.setting_ui_theme_dark)  value = AppPrefs.PREF_UI_THEME_DARK;
            if (value != null) AppPrefs.setPreferredTheme(value);
            Utils.updateNightMode();
        };

        RadioButton btnAuto  = view.findViewById(R.id.setting_ui_theme_auto);
        RadioButton btnLight = view.findViewById(R.id.setting_ui_theme_light);
        RadioButton btnDark  = view.findViewById(R.id.setting_ui_theme_dark);
        btnAuto.setOnClickListener(clickListener);
        btnLight.setOnClickListener(clickListener);
        btnDark.setOnClickListener(clickListener);
        btnAuto.setChecked(currentValue.equals(AppPrefs.PREF_UI_THEME_AUTO));
        btnLight.setChecked(currentValue.equals(AppPrefs.PREF_UI_THEME_LIGHT));
        btnDark.setChecked(currentValue.equals(AppPrefs.PREF_UI_THEME_DARK));
    }

    private void bindRemoteContent(@NonNull ViewHolder holder) {
        View view = holder.itemView;
        String currentValue = ArticleViewPrefs.getRemoteContentPreference();

        View.OnClickListener clickListener = v -> {
            String value = null;
            int id = v.getId();
            if (id == R.id.setting_remote_content_always)    value = ArticleViewPrefs.PREF_REMOTE_CONTENT_ALWAYS;
            else if (id == R.id.setting_remote_content_wifi) value = ArticleViewPrefs.PREF_REMOTE_CONTENT_WIFI;
            else if (id == R.id.setting_remote_content_never) value = ArticleViewPrefs.PREF_REMOTE_CONTENT_NEVER;
            if (value != null) ArticleViewPrefs.setRemoteContentPreference(value);
        };

        RadioButton btnAlways = view.findViewById(R.id.setting_remote_content_always);
        RadioButton btnWiFi   = view.findViewById(R.id.setting_remote_content_wifi);
        RadioButton btnNever  = view.findViewById(R.id.setting_remote_content_never);
        btnAlways.setOnClickListener(clickListener);
        btnWiFi.setOnClickListener(clickListener);
        btnNever.setOnClickListener(clickListener);
        btnAlways.setChecked(currentValue.equals(ArticleViewPrefs.PREF_REMOTE_CONTENT_ALWAYS));
        btnWiFi.setChecked(currentValue.equals(ArticleViewPrefs.PREF_REMOTE_CONTENT_WIFI));
        btnNever.setChecked(currentValue.equals(ArticleViewPrefs.PREF_REMOTE_CONTENT_NEVER));
    }

    private void bindAutoLoadFolder(@NonNull ViewHolder holder) {
        View view = holder.itemView;
        if (holder.cardView != null) {
            holder.cardView.setCardBackgroundColor(SurfaceColors.SURFACE_1.getColor(context));
        }

        MaterialTextView title    = view.findViewById(R.id.title);
        MaterialTextView subtitle = view.findViewById(R.id.subtitle);
        MaterialButton selectBtn  = view.findViewById(R.id.select_folder_button);
        MaterialButton clearBtn   = view.findViewById(R.id.clear_folder_button);

        title.setText(R.string.setting_auto_load_folder);

        String folderUri = AppPrefs.getAutoLoadDictFolderUri();
        if (folderUri.isEmpty()) {
            subtitle.setText(R.string.setting_auto_load_folder_subtitle);
            clearBtn.setVisibility(View.GONE);
        } else {
            subtitle.setText(folderUri);
            clearBtn.setVisibility(View.VISIBLE);
        }

        selectBtn.setOnClickListener(v -> {
            if (fragment instanceof SettingsFragment) {
                ((SettingsFragment) fragment).selectAutoLoadFolder();
            }
        });

        clearBtn.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.dialog_clear_auto_load_folder_title)
                        .setMessage(R.string.dialog_clear_auto_load_folder_message)
                        .setPositiveButton(R.string.action_clear, (dialog, which) -> {
                            if (fragment instanceof SettingsFragment) {
                                ((SettingsFragment) fragment).clearAutoLoadFolder();
                            }
                            notifyItemsByStableIds(ID_AUTO_LOAD_FOLDER, ID_AUTO_MOVE_TO_FOLDER);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
    }

    private void bindUserStyles(@NonNull ViewHolder holder) {
        View view = holder.itemView;

        MaterialButton btnAdd = view.findViewById(R.id.setting_btn_add_user_style);
        btnAdd.setOnClickListener(v -> {
            try {
                ((SettingsFragment) fragment).userStylesChooser.launch("text/*");
            } catch (ActivityNotFoundException e) {
                Log.d(TAG, "No activity to get content", e);
                Toast.makeText(context, R.string.msg_no_activity_to_get_content, Toast.LENGTH_LONG).show();
            }
        });

        List<String> names = UserStylesPrefs.listStyleNames();
        Collections.sort(names);

        view.findViewById(R.id.setting_user_styles_empty)
                .setVisibility(names.isEmpty() ? View.VISIBLE : View.GONE);

        LinearLayoutCompat listLayout = view.findViewById(R.id.setting_user_styles_list);
        listLayout.removeAllViews();
        for (String name : names) {
            View itemView = View.inflate(view.getContext(), R.layout.user_styles_list_item, null);
            ImageView btnDelete = itemView.findViewById(R.id.user_styles_list_btn_delete);
            btnDelete.setOnClickListener(onDeleteUserStyle);
            btnDelete.setTag(name);
            ((TextView) itemView.findViewById(R.id.user_styles_list_name)).setText(name);
            listLayout.addView(itemView);
        }
    }

    private void bindClearCache(@NonNull ViewHolder holder) {
        holder.itemView.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(fragment.requireActivity())
                        .setMessage(R.string.confirm_clear_cached_content)
                        .setPositiveButton(R.string.action_yes, (dialog, id) -> {
                            WebView webView = new WebView(fragment.requireActivity());
                            webView.clearCache(true);
                        })
                        .setNegativeButton(R.string.action_no, null)
                        .show());
    }

    private void bindBackup(@NonNull ViewHolder holder) {
        View view = holder.itemView;
        MaterialButton exportBtn = view.findViewById(R.id.export_backup_button);
        MaterialButton importBtn = view.findViewById(R.id.import_backup_button);

        exportBtn.setOnClickListener(v -> {
            if (fragment instanceof SettingsFragment) {
                ((SettingsFragment) fragment).exportBackup();
            }
        });
        importBtn.setOnClickListener(v -> {
            if (fragment instanceof SettingsFragment) {
                ((SettingsFragment) fragment).importBackup();
            }
        });
    }

    private void bindAbout(@NonNull ViewHolder holder) {
        View view = holder.itemView;

        String appName = context.getString(R.string.app_name);
        ((TextView) view.findViewById(R.id.setting_about))
                .setText(context.getString(R.string.setting_about, appName));

        String licenseName = context.getString(R.string.application_license_name);
        final String licenseUrl = context.getString(R.string.application_license_url);
        String licenseHtml = context.getString(R.string.application_license, licenseUrl, licenseName);
        TextView licenseView = view.findViewById(R.id.application_license);
        licenseView.setText(HtmlCompat.fromHtml(licenseHtml.trim(), HtmlCompat.FROM_HTML_MODE_LEGACY));
        licenseView.setOnClickListener(v -> openUrl(licenseUrl));

        String versionName;
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            versionName = info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            versionName = "?";
        }
        ((TextView) view.findViewById(R.id.application_version))
                .setText(context.getString(R.string.application_version, versionName));

        // Source / repository row
        View sourceRow = view.findViewById(R.id.setting_about_source_row);
        MaterialTextView sourceUrlView = view.findViewById(R.id.application_home_url);
        sourceUrlView.setText(BuildConfig.REPO_URL);
        sourceRow.setOnClickListener(v -> openUrl(BuildConfig.REPO_URL));

        // GitHub Sponsors row
        View sponsorRow = view.findViewById(R.id.setting_about_sponsor_row);
        sponsorRow.setOnClickListener(v -> openUrl(BuildConfig.SPONSORS_URL));
    }

    private void openUrl(String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "No browser available", e);
        }
    }

    private void deleteUserStyle(final String name) {
        new MaterialAlertDialogBuilder(context)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.setting_user_styles)
                .setMessage(context.getString(R.string.setting_user_style_confirm_forget, name))
                .setPositiveButton(R.string.action_yes, (dialog, which) -> {
                    Log.d(TAG, "Deleting user style " + name);
                    UserStylesPrefs.removeStyle(name);
                })
                .setNegativeButton(R.string.action_no, null)
                .show();
    }

    // -------------------------------------------------------------------------
    // ViewHolder
    // -------------------------------------------------------------------------
    public static class ViewHolder extends RecyclerView.ViewHolder {
        @Nullable public final MaterialCardView cardView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.card_view);
            if (cardView != null) {
                cardView.setCardBackgroundColor(SurfaceColors.SURFACE_1.getColor(itemView.getContext()));
            }
        }
    }
}

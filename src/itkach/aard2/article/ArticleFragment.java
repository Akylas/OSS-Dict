package itkach.aard2.article;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import itkach.aard2.BlobDescriptorList;
import itkach.aard2.BookmarkFolderDialogs;
import itkach.aard2.R;
import itkach.aard2.SlobHelper;
import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.descriptor.BookmarkFolders;
import itkach.aard2.prefs.AppPrefs;
import itkach.aard2.prefs.ArticleCollectionPrefs;
import itkach.aard2.prefs.ArticleViewPrefs;
import itkach.aard2.utils.Utils;
import itkach.aard2.widget.ArticleWebView;


public class ArticleFragment extends Fragment {
    public static final String ARG_URI = "uri";

    private static final String TAG = ArticleFragment.class.getSimpleName();

    private ArticleWebView webView;
    private MenuItem bookmarkMenu;
    private MenuItem stylesMenu;
    private Uri url;
    private View noteBar;
    private TextView noteText;
    private TextView noteFolders;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.article, menu);
        bookmarkMenu = menu.findItem(R.id.action_bookmark_article);
        if (AppPrefs.disableBookmarks()) {
            bookmarkMenu.setVisible(false);
            menu.findItem(R.id.action_bookmark_folders).setVisible(false);
        }
        if (!ArticleCollectionPrefs.allowFullscreen()) {
            menu.findItem(R.id.action_fullscreen).setVisible(false);
        }
        stylesMenu = menu.findItem(R.id.action_select_style);
    }

    private void displayBookmarked(boolean value) {
        if (bookmarkMenu == null) {
            return;
        }
        if (value) {
            bookmarkMenu.setChecked(true);
            bookmarkMenu.setIcon(R.drawable.ic_bookmark);
        } else {
            bookmarkMenu.setChecked(false);
            bookmarkMenu.setIcon(R.drawable.ic_bookmark_border);
        }
    }

    @Nullable
    private BlobDescriptor findBookmark() {
        if (url == null || AppPrefs.disableBookmarks()) {
            return null;
        }
        try {
            return SlobHelper.getInstance().bookmarks.find(url);
        } catch (IllegalStateException e) {
            Log.d(TAG, "Bookmarks not available yet", e);
            return null;
        }
    }

    private void refreshBookmarkState() {
        BlobDescriptor bookmark = findBookmark();
        displayBookmarked(bookmark != null);
        if (noteBar == null) {
            return;
        }
        noteBar.setVisibility(bookmark != null ? View.VISIBLE : View.GONE);
        if (bookmark == null) {
            return;
        }
        boolean hasNote = !TextUtils.isEmpty(bookmark.note);
        boolean hasFolders = !BookmarkFolders.isUnfiled(bookmark);
        noteText.setVisibility(hasNote || !hasFolders ? View.VISIBLE : View.GONE);
        noteText.setText(hasNote ? bookmark.note
                : noteBar.getContext().getString(R.string.bookmark_add_note_hint));
        noteText.setAlpha(hasNote ? 1f : 0.7f);
        noteFolders.setVisibility(hasFolders ? View.VISIBLE : View.GONE);
        noteFolders.setText(hasFolders ? noteBar.getContext().getString(
                R.string.bookmark_folders_label, TextUtils.join(", ", bookmark.folders)) : null);
    }

    private void editFoldersAndNote() {
        if (url != null) {
            BookmarkFolderDialogs.editArticle(requireActivity(), url, this::refreshBookmarkState);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_find_in_page) {
            webView.showFindDialog(null, true);
            return true;
        }
        if (itemId == R.id.action_bookmark_article) {
            if (url != null) {
                BlobDescriptorList bookmarks = SlobHelper.getInstance().bookmarks;
                if (item.isChecked()) {
                    BlobDescriptor bookmark = findBookmark();
                    if (bookmark != null && BookmarkFolders.hasAnnotations(bookmark)) {
                        new MaterialAlertDialogBuilder(requireActivity())
                                .setMessage(R.string.bookmark_confirm_remove)
                                .setPositiveButton(R.string.action_yes, (dialog, which) -> {
                                    bookmarks.remove(url);
                                    refreshBookmarkState();
                                })
                                .setNegativeButton(R.string.action_no, null)
                                .show();
                    } else {
                        bookmarks.remove(url);
                        refreshBookmarkState();
                    }
                } else {
                    bookmarks.add(url);
                    refreshBookmarkState();
                }
            }
            return true;
        }
        if (itemId == R.id.action_bookmark_folders) {
            editFoldersAndNote();
            return true;
        }
        if (itemId == R.id.action_fullscreen) {
            ((ArticleCollectionActivity) requireActivity()).toggleFullScreen();
            return true;
        }
        if (itemId == R.id.action_zoom_in) {
            webView.textZoomIn();
            return true;
        }
        if (itemId == R.id.action_zoom_out) {
            webView.textZoomOut();
            return true;
        }
        if (itemId == R.id.action_zoom_reset) {
            webView.resetTextZoom();
            return true;
        }
        if (itemId == R.id.action_load_remote_content) {
            webView.setForceLoadRemoteContent(true);
            webView.reload();
            return true;
        }
        if (itemId == R.id.action_select_style) {
            final String[] styleTitles = webView.getAvailableStyles();
            new MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(R.string.select_style)
                    .setItems(styleTitles, (dialog, which) -> {
                        String title = styleTitles[which];
                        webView.saveStylePref(title);
                        webView.applyStylePref();
                    })
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        url = args == null ? null : args.getParcelable(ARG_URI);
        if (url == null) {
            View layout = inflater.inflate(R.layout.empty_view, container, false);
            ImageView icon = layout.findViewById(R.id.empty_icon);
            icon.setImageResource(R.drawable.ic_block);
            setHasOptionsMenu(false);
            return layout;
        }

        if (!SlobHelper.getInstance().isServerAvailable()) {
            // Articles are served over a local socket, which needs the internet permission
            View layout = inflater.inflate(R.layout.empty_view, container, false);
            ImageView icon = layout.findViewById(R.id.empty_icon);
            icon.setImageResource(R.drawable.ic_block);
            TextView emptyText = layout.findViewById(R.id.empty_text);
            emptyText.setText(R.string.article_internet_permission_required);
            setHasOptionsMenu(false);
            return layout;
        }

        View layout = inflater.inflate(R.layout.article_view, container, false);
        LinearProgressIndicator progressBar = layout.findViewById(R.id.progress_horizontal);
        webView = layout.findViewById(R.id.webView);
        if (!Utils.isNightMode(webView.getContext())) {
            webView.setBackgroundColor(MaterialColors.getColor(webView, com.google.android.material.R.attr.colorSurface));
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setOnHistoryChangedListener(() -> {
            if (getActivity() instanceof ArticleCollectionActivity) {
                ((ArticleCollectionActivity) getActivity()).updateWebViewBackCallback();
            }
        });
        webView.restoreState(savedInstanceState);
        webView.loadUrl(url.toString());
        noteBar = layout.findViewById(R.id.article_note_bar);
        noteText = layout.findViewById(R.id.article_note_text);
        noteFolders = layout.findViewById(R.id.article_note_folders);
        noteBar.setOnClickListener(view -> editFoldersAndNote());
        refreshBookmarkState();
        webView.setWebChromeClient(new WebChromeClient() {
            public void onProgressChanged(WebView view, final int newProgress) {
                final Activity activity = getActivity();
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        progressBar.setProgress(newProgress);
                        if (newProgress >= progressBar.getMax()) {
                            progressBar.setVisibility(ViewGroup.GONE);
                        }
                    });
                }
            }
        });

        return layout;
    }


    @Override
    public void onResume() {
        super.onResume();
        applyTextZoomPref();
        applyStylePref();
        refreshBookmarkState();
    }

    @Override
    public void onDestroyView() {
        noteBar = null;
        noteText = null;
        noteFolders = null;
        super.onDestroyView();
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        super.onPrepareOptionsMenu(menu);
        if (stylesMenu != null) {
            stylesMenu.setVisible(!ArticleViewPrefs.disableJavaScript());
        }
        if (url == null) {
            bookmarkMenu.setVisible(false);
        } else {
            try {
                boolean bookmarked = SlobHelper.getInstance().bookmarks.contains(url);
                displayBookmarked(bookmarked);
            } catch (Exception ex) {
                bookmarkMenu.setVisible(false);
                menu.findItem(R.id.action_bookmark_folders).setVisible(false);
            }
        }
        applyTextZoomPref();
        applyStylePref();
    }

    void applyTextZoomPref() {
        if (webView != null) {
            webView.applyTextZoomPref();
        }
    }

    void applyStylePref() {
        if (webView != null) {
            webView.applyStylePref();
        }
    }

    public ArticleWebView getWebView() {
        return webView;
    }

    @Override
    public void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        bookmarkMenu = null;
        super.onDestroy();
    }

}
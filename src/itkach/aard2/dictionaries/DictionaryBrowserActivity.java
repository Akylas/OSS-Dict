package itkach.aard2.dictionaries;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import itkach.aard2.R;
import itkach.aard2.utils.Utils;

/**
 * Browses dictionary download sites inside the app so that dictionary files can be downloaded
 * and added directly, instead of being saved by an external browser and picked by hand.
 */
public class DictionaryBrowserActivity extends AppCompatActivity {
    private static final String TAG = DictionaryBrowserActivity.class.getSimpleName();

    public static final String EXTRA_URL = "url";

    private WebView webView;
    private LinearProgressIndicator progressIndicator;

    private final OnBackPressedCallback webViewBackCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            webView.goBack();
        }
    };

    // Downloads run either way: the permission only affects the install notification
    private final ActivityResultLauncher<String> requestNotificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            });

    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull String url) {
        return new Intent(context, DictionaryBrowserActivity.class).putExtra(EXTRA_URL, url);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        Utils.updateNightMode();
        setContentView(R.layout.activity_dictionary_browser);
        setSupportActionBar(findViewById(R.id.toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layout), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            mlp.leftMargin = bars.left;
            mlp.bottomMargin = bars.bottom;
            mlp.topMargin = bars.top;
            mlp.rightMargin = bars.right;
            v.setLayoutParams(mlp);
            return WindowInsetsCompat.CONSUMED;
        });

        progressIndicator = findViewById(R.id.progress);
        webView = findViewById(R.id.webView);
        getOnBackPressedDispatcher().addCallback(this, webViewBackCallback);
        setUpWebView();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            String url = getIntent().getStringExtra(EXTRA_URL);
            if (url == null) {
                finish();
                return;
            }
            webView.loadUrl(url);
        }
    }

    private void setUpWebView() {
        WebSettings settings = webView.getSettings();
        // Sites like Nextcloud shares render their file lists with JavaScript
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                // mailto:, intent:… are handled by other apps
                openExternally(request.getUrl());
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                updateTitle(null, url);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                webViewBackCallback.setEnabled(view.canGoBack());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressIndicator.setProgressCompat(newProgress, true);
                progressIndicator.setVisibility(newProgress < 100 ? View.VISIBLE : View.INVISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                updateTitle(title, view.getUrl());
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                onDownloadRequested(url, userAgent, contentDisposition, contentLength));
    }

    private void updateTitle(@Nullable String title, @Nullable String url) {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        String host = url != null ? Uri.parse(url).getHost() : null;
        actionBar.setTitle(title != null && !title.isEmpty() ? title : host);
        actionBar.setSubtitle(host);
    }

    private void onDownloadRequested(@NonNull String url, @Nullable String userAgent,
                                     @Nullable String contentDisposition, long contentLength) {
        Log.d(TAG, "Download requested: " + url + " (" + contentDisposition + ")");
        String fileName = DictionaryDownloads.fileName(url, contentDisposition);
        if (!DictionaryDownloads.isSupported(fileName)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dictionary_download_unsupported_title)
                    .setMessage(getString(R.string.dictionary_download_unsupported_message, fileName))
                    .setPositiveButton(R.string.action_open_in_browser, (dialog, which) ->
                            openExternally(Uri.parse(url)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        String message = getString(DictionaryDownloader.installsIntoFolder(this)
                ? R.string.dictionary_download_message_folder
                : R.string.dictionary_download_message_app_storage, fileName);
        if (contentLength > 0) {
            message += "\n\n" + getString(R.string.dictionary_download_size,
                    Formatter.formatShortFileSize(this, contentLength));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dictionary_download_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_download_and_add, (dialog, which) ->
                        startDownload(url, userAgent, fileName))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.action_open_in_browser, (dialog, which) ->
                        openExternally(Uri.parse(url)))
                .show();
    }

    private void startDownload(@NonNull String url, @Nullable String userAgent, @NonNull String fileName) {
        if (DictionaryDownloader.enqueue(this, url, userAgent, fileName)) {
            Toast.makeText(this, getString(R.string.msg_dictionary_download_started, fileName),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.msg_dictionary_download_failed, fileName),
                    Toast.LENGTH_LONG).show();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void openExternally(@NonNull Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "No activity to open " + uri, e);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.dictionary_browser, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        }
        if (itemId == R.id.action_reload) {
            webView.reload();
            return true;
        }
        if (itemId == R.id.action_open_in_browser) {
            String url = webView.getUrl();
            if (url != null) {
                openExternally(Uri.parse(url));
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}

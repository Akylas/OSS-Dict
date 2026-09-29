package itkach.aard2.dictionaries;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import itkach.aard2.utils.ThreadUtils;

/**
 * Installs dictionaries once {@link DownloadManager} finishes downloading them. Registered in the
 * manifest so downloads completing after the app was closed still get installed.
 */
public class DictionaryDownloadReceiver extends BroadcastReceiver {
    private static final String TAG = DictionaryDownloadReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }
        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (downloadId == -1) {
            return;
        }
        Context appContext = context.getApplicationContext();
        // Keeps the process alive while the file is moved and the dictionary loaded
        PendingResult pendingResult = goAsync();
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                DictionaryDownloader.onDownloadComplete(appContext, downloadId);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to install download " + downloadId, e);
            } finally {
                pendingResult.finish();
            }
        });
    }
}

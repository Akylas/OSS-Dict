package itkach.aard2.dictionaries;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

import itkach.aard2.R;
import itkach.aard2.SlobHelper;
import itkach.aard2.descriptor.SlobDescriptor;
import itkach.aard2.prefs.AppPrefs;
import itkach.aard2.utils.ThreadUtils;

/**
 * Downloads dictionary files picked in {@link DictionaryBrowserActivity} with the system
 * {@link DownloadManager} (which shows the progress notification), then installs them into the
 * auto-load folder when it is writable, or into app storage otherwise.
 */
public final class DictionaryDownloader {
    private static final String TAG = DictionaryDownloader.class.getSimpleName();

    /** Where {@link DownloadManager} writes files until they are installed. */
    private static final String STAGING_DIR = "downloads";
    /** Where downloaded dictionaries live when no writable auto-load folder is set. */
    private static final String APP_DICTIONARIES_DIR = "dictionaries";
    private static final long INIT_TIMEOUT_MILLIS = 30_000;

    private DictionaryDownloader() {
    }

    /**
     * Folder holding dictionaries downloaded into app storage, or null when storage is unavailable.
     */
    @Nullable
    public static File getAppDictionariesDir(@NonNull Context context) {
        File dir = context.getExternalFilesDir(APP_DICTIONARIES_DIR);
        if (dir == null) {
            return null;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "Cannot create " + dir);
            return null;
        }
        return dir;
    }

    /**
     * Whether a downloaded dictionary would be saved into the auto-load folder rather than
     * into app storage.
     */
    public static boolean installsIntoFolder(@NonNull Context context) {
        return DictionaryFolderManager.getInstance(context).getWritableAutoLoadFolder() != null;
    }

    /**
     * Starts downloading a dictionary file.
     * @return true if the download was queued
     */
    public static boolean enqueue(@NonNull Context context, @NonNull String url, @Nullable String userAgent,
                                  @NonNull String fileName) {
        File stagingDir = context.getExternalFilesDir(STAGING_DIR);
        if (stagingDir == null) {
            Log.w(TAG, "External storage unavailable, cannot download " + url);
            return false;
        }
        // DownloadManager renames the file instead of overwriting a leftover one
        File stale = new File(stagingDir, fileName);
        if (stale.exists() && !stale.delete()) {
            Log.w(TAG, "Cannot delete stale download " + stale);
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(fileName)
                    .setDescription(context.getString(R.string.app_name))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(context, STAGING_DIR, fileName);
            if (userAgent != null) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            // Pass the browsing session along for sites needing it to serve the file
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                request.addRequestHeader("Cookie", cookies);
            }
            DownloadManager downloadManager = context.getSystemService(DownloadManager.class);
            long downloadId = downloadManager.enqueue(request);
            AppPrefs.addPendingDictionaryDownload(downloadId);
            Log.d(TAG, "Queued download " + downloadId + ": " + fileName);
            return true;
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.w(TAG, "Cannot download " + url, e);
            return false;
        }
    }

    /**
     * Handles the end of a download: installs the file when it succeeded.
     * Ignores downloads not started by {@link #enqueue}.
     */
    @WorkerThread
    public static void onDownloadComplete(@NonNull Context context, long downloadId) {
        if (!AppPrefs.removePendingDictionaryDownload(downloadId)) {
            return;
        }
        DownloadManager downloadManager = context.getSystemService(DownloadManager.class);
        int status = DownloadManager.STATUS_FAILED;
        String fileName = null;
        String localUri = null;
        try (Cursor cursor = downloadManager.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor != null && cursor.moveToFirst()) {
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                fileName = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            }
        }
        String label = fileName != null ? fileName : String.valueOf(downloadId);
        String localPath = localUri != null ? Uri.parse(localUri).getPath() : null;
        if (status != DownloadManager.STATUS_SUCCESSFUL || localPath == null) {
            Log.w(TAG, "Download " + downloadId + " failed with status " + status);
            // Also deletes the partial file (a cancelled download has no row left)
            downloadManager.remove(downloadId);
            showToast(context, context.getString(R.string.msg_dictionary_download_failed, label));
            return;
        }
        try {
            // The receiver can start the process: let the saved dictionary list load first
            if (!SlobHelper.getInstance().awaitInitialized(INIT_TIMEOUT_MILLIS)) {
                Log.w(TAG, "Dictionary list not loaded yet, installing anyway");
            }
            install(context, new File(localPath));
        } finally {
            // The file has been moved or copied away; drop the entry from the system list
            downloadManager.remove(downloadId);
        }
    }

    /**
     * Moves a downloaded file into the auto-load folder when writable, into app storage
     * otherwise, then rescans that folder so the dictionary, or the dictionary this companion
     * file belongs to, gets loaded.
     */
    @WorkerThread
    private static void install(@NonNull Context context, @NonNull File downloaded) {
        String name = downloaded.getName();
        DictionaryScanNotification notification = new DictionaryScanNotification(context);
        if (!DictionaryDownloads.isArchive(name)) {
            installFiles(context, Collections.singletonList(downloaded), notification);
            return;
        }
        // Extracting a large archive takes a while: show progress already
        notification.showScanStarted();
        File extractDir = new File(downloaded.getParentFile(), name + ".extracted");
        try {
            List<File> files;
            try {
                files = DictionaryArchives.extract(downloaded, extractDir);
            } catch (IOException | RuntimeException e) {
                // Archive parsing reports corrupt data with runtime exceptions too
                Log.w(TAG, "Cannot extract " + downloaded, e);
                notification.dismiss();
                showToast(context, context.getString(R.string.msg_dictionary_extract_failed, name));
                return;
            } finally {
                deleteFile(downloaded);
            }
            if (files.isEmpty()) {
                notification.dismiss();
                showToast(context, context.getString(R.string.msg_dictionary_archive_empty, name));
                return;
            }
            installFiles(context, files, notification);
        } finally {
            deleteDirectory(extractDir);
        }
    }

    /**
     * Moves dictionary files into the auto-load folder when writable, into app storage
     * otherwise, then rescans that folder.
     */
    @WorkerThread
    private static void installFiles(@NonNull Context context, @NonNull List<File> files,
                                     @NonNull DictionaryScanNotification notification) {
        DictionaryFolderManager folderManager = DictionaryFolderManager.getInstance(context);
        DictionaryFolderManager.ProgressCallback progress = createProgressCallback(notification);
        Uri folderUri = folderManager.getWritableAutoLoadFolder();
        if (folderUri != null) {
            try {
                for (File file : files) {
                    copyIntoFolder(context, file, folderUri);
                    deleteFile(file);
                }
                folderManager.scanAndSync(null, progress);
                return;
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "Cannot save dictionary files into " + folderUri + ", using app storage", e);
            }
        }
        File appDir = getAppDictionariesDir(context);
        if (appDir == null) {
            notification.dismiss();
            showToast(context, context.getString(R.string.msg_dictionary_download_failed, files.get(0).getName()));
            return;
        }
        for (File file : files) {
            // Files already copied into the folder before a failure are gone
            if (!file.exists()) continue;
            File target = new File(appDir, file.getName());
            if (target.exists()) {
                deleteFile(target);
            }
            if (!file.renameTo(target)) {
                try {
                    copy(file, target);
                    deleteFile(file);
                } catch (IOException e) {
                    Log.w(TAG, "Cannot move " + file + " to " + target, e);
                    notification.dismiss();
                    showToast(context, context.getString(R.string.msg_dictionary_download_failed, file.getName()));
                    return;
                }
            }
        }
        folderManager.syncAppDownloads(progress);
    }

    private static void deleteDirectory(@NonNull File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteFile(child);
            }
        }
        if (dir.exists()) {
            deleteFile(dir);
        }
    }

    /**
     * Deletes the files of a dictionary downloaded into app storage. Does nothing for
     * dictionaries living elsewhere.
     */
    @WorkerThread
    public static void deleteAppStorageFiles(@NonNull Context context, @NonNull SlobDescriptor descriptor) {
        File appDir = getAppDictionariesDir(context);
        if (appDir == null) {
            return;
        }
        String[] paths = {descriptor.path, descriptor.mddPath, descriptor.dslResourcesPath,
                descriptor.dslAnnPath, descriptor.dslAbbrevPath};
        for (String path : paths) {
            if (path == null) continue;
            Uri uri = Uri.parse(path);
            if (!"file".equals(uri.getScheme()) || uri.getPath() == null) continue;
            File file = new File(uri.getPath());
            if (appDir.equals(file.getParentFile())) {
                deleteFile(file);
            }
        }
    }

    private static void copyIntoFolder(@NonNull Context context, @NonNull File source, @NonNull Uri folderUri)
            throws IOException {
        DocumentFile folder = DocumentFile.fromTreeUri(context, folderUri);
        if (folder == null || !folder.isDirectory()) {
            throw new IOException("Not a folder: " + folderUri);
        }
        String name = source.getName();
        DocumentFile target = folder.findFile(name);
        if (target == null || !target.isFile()) {
            // octet-stream keeps the name as is: no extension gets appended
            target = folder.createFile("application/octet-stream", name);
        }
        if (target == null) {
            throw new IOException("Cannot create " + name + " in " + folderUri);
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = context.getContentResolver().openOutputStream(target.getUri(), "wt")) {
            if (out == null) {
                throw new IOException("Cannot write " + target.getUri());
            }
            copy(in, out);
        }
    }

    private static void copy(@NonNull File source, @NonNull File target) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
    }

    private static void copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void deleteFile(@NonNull File file) {
        if (!file.delete()) {
            Log.w(TAG, "Cannot delete " + file);
        }
    }

    @NonNull
    private static DictionaryFolderManager.ProgressCallback createProgressCallback(
            @NonNull DictionaryScanNotification notification) {
        return new DictionaryFolderManager.ProgressCallback() {
            @Override
            public void onScanStarted() {
                notification.showScanStarted();
            }

            @Override
            public void onDictionaryLoading(String dictionaryName, int current, int total) {
                notification.updateProgress(dictionaryName, current, total);
            }

            @Override
            public void onScanCompleted(int addedCount, int removedCount) {
                notification.showCompleted(addedCount, removedCount);
            }
        };
    }

    private static void showToast(@NonNull Context context, @NonNull String message) {
        Context appContext = context.getApplicationContext();
        ThreadUtils.postOnMainThread(() -> Toast.makeText(appContext, message, Toast.LENGTH_LONG).show());
    }
}

package itkach.aard2.prefs;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import itkach.aard2.MainActivity;
import itkach.aard2.R;
import itkach.aard2.SlobHelper;
import itkach.aard2.descriptor.BlobDescriptorBackup;
import itkach.aard2.dictionaries.DictionaryFolderManager;
import itkach.aard2.utils.ThreadUtils;
import itkach.aard2.utils.Utils;
import itkach.aard2.widget.RecyclerView;

public class SettingsFragment extends Fragment {
    private final static String TAG = SettingsFragment.class.getSimpleName();

    private RecyclerView recyclerView;
    public final ActivityResultLauncher<String> userStylesChooser = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri == null) {
                    return;
                }
                try {
                    InputStream is = requireActivity().getContentResolver().openInputStream(uri);
                    DocumentFile documentFile = DocumentFile.fromSingleUri(requireActivity(), uri);
                    if (documentFile == null) {
                        throw new IOException("Could not access file");
                    }
                    String fileName = documentFile.getName();
                    if (fileName == null) {
                        fileName = uri.getLastPathSegment();
                    }
                    String userCss = Utils.readStream(is, 256 * 1024);
                    Log.d(TAG, fileName);
                    Log.d(TAG, userCss);
                    int lastIndexOfDot = fileName.lastIndexOf(".");
                    if (lastIndexOfDot > -1) {
                        fileName = fileName.substring(0, lastIndexOfDot);
                    }
                    if (fileName.length() == 0) {
                        fileName = "???";
                    }

                    userCss = userCss.replace("\r", "").replace("\n", "\\n");

                    if (!UserStylesPrefs.addStyle(fileName, userCss)) {
                        Toast.makeText(requireActivity(), R.string.msg_failed_to_store_user_style,
                                Toast.LENGTH_LONG).show();
                    }
                } catch (IOException e) {
                    if ("Too big file".equals(e.getMessage())) {
                        Toast.makeText(requireActivity(), R.string.msg_file_too_big, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(requireActivity(), R.string.msg_failed_to_read_file, Toast.LENGTH_LONG).show();
                    }
                }
            });

    private static final String BACKUP_FILE_NAME = "oss-dict-backup.json";
    private static final int BACKUP_MAX_CHARS = 8 * 1024 * 1024;

    public final ActivityResultLauncher<String> backupExportChooser = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> {
                if (uri == null) {
                    return;
                }
                writeBackupTo(uri);
            });

    public final ActivityResultLauncher<String[]> backupImportChooser = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) {
                    return;
                }
                readBackupFrom(uri);
            });

    @Nullable private Uri pendingAutoLoadUri;
    private final ActivityResultLauncher<String> requestNotificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    if (pendingAutoLoadUri != null) {
                        DictionaryFolderManager.getInstance(requireContext()).setAutoLoadFolder(pendingAutoLoadUri, null);
                        pendingAutoLoadUri = null;
                        Toast.makeText(requireActivity(), R.string.msg_folder_selected, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(requireActivity(), R.string.msg_permission_denied_notifications, Toast.LENGTH_LONG).show();
                    pendingAutoLoadUri = null;
                }
            });


    public final ActivityResultLauncher<Intent> autoLoadFolderChooser = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result == null || result.getResultCode() != android.app.Activity.RESULT_OK) {
                    return;
                }
                android.content.Intent intent = result.getData();
                if (intent == null) {
                    return;
                }
                Uri uri = intent.getData();
                if (uri == null) {
                    return;
                }

                // If API < 33 we don't need runtime POST_NOTIFICATIONS permission
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    DictionaryFolderManager.getInstance(requireContext()).setAutoLoadFolder(uri, null);
                    Toast.makeText(requireActivity(), R.string.msg_folder_selected, Toast.LENGTH_SHORT).show();
                    return;
                }

                // For API 33+, check permission
                if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) {
                    DictionaryFolderManager.getInstance(requireContext()).setAutoLoadFolder(uri, null);
                    Toast.makeText(requireActivity(), R.string.msg_folder_selected, Toast.LENGTH_SHORT).show();
                } else {
                    // store the uri and request permission; on grant we'll call setAutoLoadFolder
                    pendingAutoLoadUri = uri;

                    // Optionally show rationale
                    if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                        Snackbar.make(requireView(), R.string.rationale_notifications_needed, Snackbar.LENGTH_LONG)
                                .setAction(R.string.action_allow, v -> requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS))
                                .show();
                    } else {
                        requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS);
                    }
                }
                
                // Refresh the settings to show the selected folder and enable auto-move
                if (recyclerView != null && recyclerView.getAdapter() != null) {
                    recyclerView.getAdapter().notifyDataSetChanged();
                }
                
                Toast.makeText(requireActivity(), R.string.msg_folder_selected, Toast.LENGTH_SHORT).show();
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup view = (ViewGroup) inflater.inflate(R.layout.fragment_list, container, false);
        recyclerView = view.findViewById(R.id.recycler_view);
        view.findViewById(R.id.empty_view).setVisibility(View.GONE);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        recyclerView.setLayoutManager(new LinearLayoutManager(view.getContext(), LinearLayoutManager.VERTICAL, false));
        recyclerView.setAdapter(new SettingsListAdapter(this));
    }

    @Override
    public void onResume() {
        super.onResume();
        FragmentActivity activity = requireActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).requireActionBar().setTitle(R.string.subtitle_settings);
            ((MainActivity) activity).requireActionBar().setSubtitle(null);
        }
    }

    @Override
    public void onDestroyView() {
        if (recyclerView != null && recyclerView.getAdapter() instanceof SettingsListAdapter) {
            ((SettingsListAdapter) recyclerView.getAdapter()).destroy();
        }
        super.onDestroyView();
    }

    public void selectAutoLoadFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            autoLoadFolderChooser.launch(intent);
        } catch (Exception e) {
            Log.d(TAG, "Failed to launch folder chooser", e);
            Toast.makeText(requireActivity(), R.string.msg_no_activity_to_select_folder, Toast.LENGTH_LONG).show();
        }
    }
    
    /** Asks the user where to write the bookmarks/history backup. */
    public void exportBackup() {
        try {
            backupExportChooser.launch(BACKUP_FILE_NAME);
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "No activity to create a document", e);
            Toast.makeText(requireActivity(), R.string.msg_no_activity_to_get_content, Toast.LENGTH_LONG).show();
        }
    }

    /** Asks the user for a backup file to merge into bookmarks and history. */
    public void importBackup() {
        try {
            // Providers label .json inconsistently, so accept anything rather than hide the file.
            backupImportChooser.launch(new String[]{"application/json", "text/plain", "*/*"});
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "No activity to open a document", e);
            Toast.makeText(requireActivity(), R.string.msg_no_activity_to_get_content, Toast.LENGTH_LONG).show();
        }
    }

    private void writeBackupTo(@NonNull Uri uri) {
        Context appContext = requireContext().getApplicationContext();
        SlobHelper slobHelper = SlobHelper.getInstance();
        // Snapshot here, on the main thread, so the file is written off a stable copy.
        BlobDescriptorBackup.Content snapshot = slobHelper.snapshotForBackup();
        int bookmarkCount = snapshot.bookmarks.size();
        int historyCount = snapshot.history.size();
        ThreadUtils.postOnBackgroundThread(() -> {
            try (OutputStream outputStream = appContext.getContentResolver().openOutputStream(uri, "wt")) {
                if (outputStream == null) {
                    throw new IOException("Could not open " + uri + " for writing");
                }
                slobHelper.writeBackup(outputStream, snapshot);
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "Failed to write backup", e);
                showToast(appContext, appContext.getString(R.string.msg_backup_export_failed));
                return;
            }
            showToast(appContext, appContext.getString(R.string.msg_backup_exported,
                    bookmarkCount, historyCount));
        });
    }

    private void readBackupFrom(@NonNull Uri uri) {
        Context appContext = requireContext().getApplicationContext();
        SlobHelper slobHelper = SlobHelper.getInstance();
        ThreadUtils.postOnBackgroundThread(() -> {
            BlobDescriptorBackup.Content content;
            try (InputStream inputStream = appContext.getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    throw new IOException("Could not open " + uri + " for reading");
                }
                // Bounded read: the picked file is arbitrary, don't let it exhaust memory.
                String json = Utils.readStream(inputStream, BACKUP_MAX_CHARS);
                content = slobHelper.readBackup(
                        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "Failed to read backup", e);
                showToast(appContext, appContext.getString(R.string.msg_backup_import_failed));
                return;
            }
            ThreadUtils.postOnMainThread(() -> {
                int bookmarksAdded = slobHelper.bookmarks.importDescriptors(content.bookmarks);
                int historyAdded = slobHelper.history.importDescriptors(content.history);
                Toast.makeText(appContext, appContext.getString(R.string.msg_backup_imported,
                        bookmarksAdded, historyAdded), Toast.LENGTH_LONG).show();
            });
        });
    }

    private static void showToast(@NonNull Context appContext, @NonNull String message) {
        ThreadUtils.postOnMainThread(() ->
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show());
    }

    public void clearAutoLoadFolder() {
        // Use DictionaryFolderManager singleton which handles everything
        DictionaryFolderManager.getInstance(requireContext()).clearAutoLoadFolder(null);
        
        // Refresh the settings to show the cleared folder
        if (recyclerView != null && recyclerView.getAdapter() != null) {
            recyclerView.getAdapter().notifyDataSetChanged();
        }
        
        Toast.makeText(requireActivity(), R.string.msg_folder_cleared, Toast.LENGTH_SHORT).show();
    }
}

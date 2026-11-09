package itkach.aard2.dictionaries;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import itkach.aard2.descriptor.SlobDescriptor;

/**
 * Scans a folder and its subfolders for dictionary files and detects changes
 * (additions, removals, breakage).
 * Designed to be extensible for supporting multiple dictionary formats.
 */
public class DictionaryScanner {
    private static final String TAG = "DictionaryScanner";

    /** Deepest subfolder level scanned below the root. */
    private static final int MAX_DEPTH = 8;

    /**
     * Most folders listed in one scan. Bounds the work on huge trees and on loops through
     * symlinks (the depth cap alone does not bound fan-out).
     */
    private static final int MAX_DIRECTORIES = 2000;

    /**
     * Represents a dictionary that may consist of multiple files.
     */
    public static class DictionaryFileSet {
        public final String id;  // Unique identifier based on main file
        public final String format;  // FORMAT_SLOB, FORMAT_MDICT, FORMAT_STARDICT, etc.
        public final List<DocumentFile> files;  // All files that make up this dictionary
        public final DocumentFile mainFile;  // The primary file to load (e.g., .ifo for StarDict, .mdx for MDict)
        public final boolean isComplete;  // True if all required files are present

        public DictionaryFileSet(String id, String format, DocumentFile mainFile, List<DocumentFile> files, boolean isComplete) {
            this.id = id;
            this.format = format;
            this.mainFile = mainFile;
            this.files = files;
            this.isComplete = isComplete;
        }
    }

    /**
     * Result of scanning a folder.
     */
    public static class ScanResult {
        public final List<DictionaryFileSet> dictionaries;
        public final Set<String> foundIds;  // IDs of all dictionaries found in the folder

        public ScanResult(List<DictionaryFileSet> dictionaries, Set<String> foundIds) {
            this.dictionaries = dictionaries;
            this.foundIds = foundIds;
        }
    }

    /**
     * Interface for dictionary format detectors. Allows easy extension for new formats.
     */
    public interface FormatDetector {
        /**
         * Returns true if this detector can handle the given file.
         */
        boolean canHandle(@NonNull DocumentFile file);

        /**
         * Attempts to build a complete dictionary file set from the given file.
         * Returns null if the file is part of a multi-file dictionary that's already been processed.
         *
         * @param folderFiles the entries of the folder holding {@code file}, where companion
         *                    files are looked up
         */
        @Nullable
        DictionaryFileSet buildFileSet(@NonNull Context context, @NonNull DocumentFile[] folderFiles,
                                        @NonNull DocumentFile file, @NonNull Set<String> processedNames);

        /**
         * Returns the format identifier (e.g., FORMAT_SLOB, FORMAT_MDICT).
         */
        @NonNull
        String getFormat();
    }

    // List of all format detectors (extensible for future formats)
    private static final List<FormatDetector> DETECTORS = new ArrayList<>();

    static {
        DETECTORS.add(new SlobFormatDetector());
        DETECTORS.add(new MDictFormatDetector());
        DETECTORS.add(new StarDictFormatDetector());
        DETECTORS.add(new StarDictArchiveFormatDetector());
        DETECTORS.add(new DslFormatDetector());
    }

    /**
     * Scans the given folder for dictionaries.
     */
    @WorkerThread
    @NonNull
    public static ScanResult scanFolder(@NonNull Context context, @NonNull Uri folderUri) {
        DocumentFile folder = DocumentFile.fromTreeUri(context, folderUri);
        if (folder == null) {
            Log.w(TAG, "Invalid folder URI: " + folderUri);
            return new ScanResult(new ArrayList<>(), new HashSet<>());
        }
        return scanFolder(context, folder);
    }

    /**
     * Scans the given folder for dictionaries. Also accepts a plain directory wrapped with
     * {@link DocumentFile#fromFile}.
     */
    @WorkerThread
    @NonNull
    public static ScanResult scanFolder(@NonNull Context context, @NonNull DocumentFile folder) {
        List<DictionaryFileSet> dictionaries = new ArrayList<>();
        Set<String> foundIds = new HashSet<>();

        if (!folder.isDirectory()) {
            Log.w(TAG, "Invalid folder: " + folder.getUri());
            return new ScanResult(dictionaries, foundIds);
        }

        scanDirectory(context, folder, dictionaries, foundIds, new int[]{MAX_DIRECTORIES}, 0);
        return new ScanResult(dictionaries, foundIds);
    }

    /**
     * Scans one directory, then its subdirectories. Companion files are only looked up in the
     * directory of the main file, and processed names are tracked per directory so that
     * same-named files in sibling folders do not hide each other.
     *
     * @param directoriesLeft single-element counter of folders that may still be listed
     */
    @WorkerThread
    private static void scanDirectory(@NonNull Context context, @NonNull DocumentFile folder,
                                      @NonNull List<DictionaryFileSet> dictionaries,
                                      @NonNull Set<String> foundIds,
                                      @NonNull int[] directoriesLeft, int depth) {
        if (directoriesLeft[0] <= 0) {
            Log.w(TAG, "Max folder count reached, not scanning " + folder.getUri());
            return;
        }
        directoriesLeft[0]--;

        // Listed once per folder and shared with the detectors for companion file lookups
        DocumentFile[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            return;
        }

        Set<String> processedFileNames = new HashSet<>();
        List<DocumentFile> subFolders = new ArrayList<>();

        // Process each file with appropriate detector
        for (DocumentFile file : files) {
            if (file == null) continue;
            if (!file.isFile()) {
                if (file.isDirectory()) subFolders.add(file);
                continue;
            }

            String name = file.getName();
            if (name == null || processedFileNames.contains(name)) continue;

            // Try each format detector
            for (FormatDetector detector : DETECTORS) {
                if (detector.canHandle(file)) {
                    DictionaryFileSet fileSet = detector.buildFileSet(context, files, file, processedFileNames);
                    if (fileSet != null) {
                        dictionaries.add(fileSet);
                        foundIds.add(fileSet.id);
                        // Mark all files in this set as processed
                        for (DocumentFile f : fileSet.files) {
                            String fn = f.getName();
                            if (fn != null) processedFileNames.add(fn);
                        }
                        break;  // File handled, move to next
                    }
                }
            }
        }

        if (subFolders.isEmpty()) return;
        if (depth >= MAX_DEPTH) {
            Log.w(TAG, "Max folder depth reached, not scanning subfolders of " + folder.getUri());
            return;
        }
        for (DocumentFile subFolder : subFolders) {
            scanDirectory(context, subFolder, dictionaries, foundIds, directoriesLeft, depth + 1);
        }
    }

    // -----------------------------------------------------------------------
    // Format Detectors
    // -----------------------------------------------------------------------

    /**
     * Detector for .slob files (single-file format).
     */
    private static class SlobFormatDetector implements FormatDetector {
        @Override
        public boolean canHandle(@NonNull DocumentFile file) {
            String name = file.getName();
            return name != null && name.toLowerCase().endsWith(".slob");
        }

        @Override
        @Nullable
        public DictionaryFileSet buildFileSet(@NonNull Context context, @NonNull DocumentFile[] folderFiles,
                                               @NonNull DocumentFile file, @NonNull Set<String> processedNames) {
            List<DocumentFile> files = new ArrayList<>();
            files.add(file);
            String id = file.getUri().toString();
            return new DictionaryFileSet(id, SlobDescriptor.FORMAT_SLOB, file, files, true);
        }

        @Override
        @NonNull
        public String getFormat() {
            return SlobDescriptor.FORMAT_SLOB;
        }
    }

    /**
     * Detector for .mdx files (may have companion .mdd resource files).
     */
    private static class MDictFormatDetector implements FormatDetector {
        @Override
        public boolean canHandle(@NonNull DocumentFile file) {
            String name = file.getName();
            return name != null && name.toLowerCase().endsWith(".mdx");
        }

        @Override
        @Nullable
        public DictionaryFileSet buildFileSet(@NonNull Context context, @NonNull DocumentFile[] folderFiles,
                                               @NonNull DocumentFile file, @NonNull Set<String> processedNames) {
            List<DocumentFile> files = new ArrayList<>();
            files.add(file);

            // Look for companion .mdd files (optional)
            String mdxName = file.getName();
            if (mdxName != null) {
                String baseName = mdxName.substring(0, mdxName.length() - 4);  // Remove .mdx
                if (folderFiles != null) {
                    for (DocumentFile f : folderFiles) {
                        if (f == null || !f.isFile()) continue;
                        String fn = f.getName();
                        if (fn != null && fn.toLowerCase().startsWith(baseName.toLowerCase()) &&
                                fn.toLowerCase().endsWith(".mdd")) {
                            files.add(f);
                        }
                    }
                }
            }

            String id = file.getUri().toString();
            return new DictionaryFileSet(id, SlobDescriptor.FORMAT_MDICT, file, files, true);
        }

        @Override
        @NonNull
        public String getFormat() {
            return SlobDescriptor.FORMAT_MDICT;
        }
    }

    /**
     * Detector for uncompressed StarDict files (.ifo + .idx + .dict or .dict.dz).
     */
    private static class StarDictFormatDetector implements FormatDetector {
        @Override
        public boolean canHandle(@NonNull DocumentFile file) {
            String name = file.getName();
            return name != null && name.toLowerCase().endsWith(".ifo");
        }

        @Override
        @Nullable
        public DictionaryFileSet buildFileSet(@NonNull Context context, @NonNull DocumentFile[] folderFiles,
                                               @NonNull DocumentFile file, @NonNull Set<String> processedNames) {
            String ifoName = file.getName();
            if (ifoName == null) return null;

            // Extract base name (without .ifo extension)
            String baseName = ifoName.substring(0, ifoName.length() - 4);
            List<DocumentFile> files = new ArrayList<>();
            files.add(file);  // .ifo file

            // Look for required companion files
            boolean hasIdx = false;
            boolean hasDict = false;

            if (folderFiles != null) {
                for (DocumentFile f : folderFiles) {
                    if (f == null || !f.isFile()) continue;
                    String fn = f.getName();
                    if (fn == null) continue;

                    String fnLower = fn.toLowerCase();
                    // Check for .idx or .idx.gz
                    if ((fnLower.equals(baseName.toLowerCase() + ".idx") ||
                            fnLower.equals(baseName.toLowerCase() + ".idx.gz"))) {
                        files.add(f);
                        hasIdx = true;
                    }
                    // Check for .dict or .dict.dz
                    else if ((fnLower.equals(baseName.toLowerCase() + ".dict") ||
                            fnLower.equals(baseName.toLowerCase() + ".dict.dz"))) {
                        files.add(f);
                        hasDict = true;
                    }
                    // Optional .syn file
                    else if (fnLower.equals(baseName.toLowerCase() + ".syn")) {
                        files.add(f);
                    }
                }
            }

            boolean isComplete = hasIdx && hasDict;
            String id = file.getUri().toString();
            return new DictionaryFileSet(id, SlobDescriptor.FORMAT_STARDICT, file, files, isComplete);
        }

        @Override
        @NonNull
        public String getFormat() {
            return SlobDescriptor.FORMAT_STARDICT;
        }
    }

    /**
     * Detector for DSL dictionaries (.dsl, .dsl.gz, .dsl.dz) with optional
     * companions: media archive (.dsl.files.zip or .files.zip), annotation
     * (.ann) and abbreviations (_abrv.dsl[.gz|.dz]).
     */
    private static class DslFormatDetector implements FormatDetector {
        private static final String[] EXTENSIONS = {".dsl", ".dsl.gz", ".dsl.dz"};

        @Nullable
        private static String baseName(@NonNull String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String extension : EXTENSIONS) {
                if (lower.endsWith(extension)) return name.substring(0, name.length() - extension.length());
            }
            return null;
        }

        @Override
        public boolean canHandle(@NonNull DocumentFile file) {
            String name = file.getName();
            if (name == null) return false;
            String base = baseName(name);
            // Abbreviation files are companions, not dictionaries.
            return base != null && !base.toLowerCase(Locale.ROOT).endsWith("_abrv");
        }

        @Override
        @Nullable
        public DictionaryFileSet buildFileSet(@NonNull Context context, @NonNull DocumentFile[] folderFiles,
                                               @NonNull DocumentFile file, @NonNull Set<String> processedNames) {
            String name = file.getName();
            if (name == null) return null;
            String base = baseName(name);
            if (base == null) return null;
            String baseLower = base.toLowerCase(Locale.ROOT);

            List<DocumentFile> files = new ArrayList<>();
            files.add(file);
            if (folderFiles != null) {
                for (DocumentFile f : folderFiles) {
                    if (f == null || !f.isFile()) continue;
                    String fn = f.getName();
                    if (fn == null) continue;
                    String fnLower = fn.toLowerCase(Locale.ROOT);
                    if (fnLower.equals(baseLower + ".dsl.files.zip")
                            || fnLower.equals(baseLower + ".files.zip")
                            || fnLower.equals(baseLower + ".ann")
                            || fnLower.equals(baseLower + "_abrv.dsl")
                            || fnLower.equals(baseLower + "_abrv.dsl.gz")
                            || fnLower.equals(baseLower + "_abrv.dsl.dz")) {
                        files.add(f);
                    }
                }
            }
            String id = file.getUri().toString();
            return new DictionaryFileSet(id, SlobDescriptor.FORMAT_DSL, file, files, true);
        }

        @Override
        @NonNull
        public String getFormat() {
            return SlobDescriptor.FORMAT_DSL;
        }
    }

    /**
     * Detector for StarDict .zip archives.
     */
    private static class StarDictArchiveFormatDetector implements FormatDetector {
        @Override
        public boolean canHandle(@NonNull DocumentFile file) {
            String name = file.getName();
            // "<name>.files.zip" is a DSL media archive, not a StarDict archive.
            return name != null && name.toLowerCase().endsWith(".zip")
                    && !name.toLowerCase().endsWith(".files.zip");
        }

        @Override
        @Nullable
        public DictionaryFileSet buildFileSet(@NonNull Context context, @NonNull DocumentFile[] folderFiles,
                                               @NonNull DocumentFile file, @NonNull Set<String> processedNames) {
            // For .zip files, we can't easily inspect contents without extracting,
            // so we assume it's a valid StarDict archive if it's a .zip file
            List<DocumentFile> files = new ArrayList<>();
            files.add(file);
            String id = file.getUri().toString();
            return new DictionaryFileSet(id, SlobDescriptor.FORMAT_STARDICT_ARCHIVE, file, files, true);
        }

        @Override
        @NonNull
        public String getFormat() {
            return SlobDescriptor.FORMAT_STARDICT_ARCHIVE;
        }
    }

    /**
     * Generates a unique ID for a dictionary based on its file URIs.
     * This is stable across scans as long as the files don't move.
     */
    @NonNull
    public static String generateDictionaryId(@NonNull DictionaryFileSet fileSet) {
        return fileSet.mainFile.getUri().toString();
    }

    /**
     * Creates a mapping from dictionary IDs to their file URIs for tracking.
     */
    @NonNull
    public static Map<String, Uri> buildIdToUriMap(@NonNull List<DictionaryFileSet> fileSets) {
        Map<String, Uri> map = new HashMap<>();
        for (DictionaryFileSet fileSet : fileSets) {
            map.put(fileSet.id, fileSet.mainFile.getUri());
        }
        return map;
    }
}

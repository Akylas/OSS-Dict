package itkach.aard2.dictionaries;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extracts the dictionary files of a downloaded archive. Plain Java so it can be unit tested.
 *
 * <p>Folders inside the archive are flattened: dictionary scanning only looks at the files
 * directly in a folder. Entries other than dictionary files (icons, readme…) are skipped.</p>
 */
public final class DictionaryArchives {

    private static final int BUFFER_SIZE = 64 * 1024;

    private DictionaryArchives() {
    }

    /**
     * Extracts the dictionary files of {@code archive} into {@code targetDir}, replacing files
     * with the same name.
     *
     * @return the extracted files, empty if the archive holds no dictionary file
     */
    @NonNull
    public static List<File> extract(@NonNull File archive, @NonNull File targetDir) throws IOException {
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            throw new IOException("Cannot create " + targetDir);
        }
        String name = archive.getName().toLowerCase(Locale.ROOT);
        List<File> extracted = new ArrayList<>();
        if (name.endsWith(".7z")) {
            extractSevenZ(archive, targetDir, extracted);
            return extracted;
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(archive), BUFFER_SIZE);
             ArchiveInputStream<? extends ArchiveEntry> archiveIn = openStream(name, in)) {
            ArchiveEntry entry;
            while ((entry = archiveIn.getNextEntry()) != null) {
                File target = targetFile(entry.isDirectory(), entry.getName(), targetDir);
                if (target != null && archiveIn.canReadEntryData(entry)) {
                    write(archiveIn, target);
                    extracted.add(target);
                }
            }
        }
        return extracted;
    }

    @NonNull
    private static ArchiveInputStream<? extends ArchiveEntry> openStream(@NonNull String name, @NonNull InputStream in)
            throws IOException {
        if (name.endsWith(".zip")) {
            return new ZipArchiveInputStream(in);
        }
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            return new TarArchiveInputStream(new GzipCompressorInputStream(in));
        }
        if (name.endsWith(".tar.xz") || name.endsWith(".txz")) {
            return new TarArchiveInputStream(new XZCompressorInputStream(in));
        }
        if (name.endsWith(".tar.bz2") || name.endsWith(".tbz2")) {
            return new TarArchiveInputStream(new BZip2CompressorInputStream(in));
        }
        if (name.endsWith(".tar")) {
            return new TarArchiveInputStream(in);
        }
        throw new IOException("Unsupported archive: " + name);
    }

    private static void extractSevenZ(@NonNull File archive, @NonNull File targetDir, @NonNull List<File> extracted)
            throws IOException {
        // The channel constructor avoids java.nio.file, missing before API 26
        try (RandomAccessFile file = new RandomAccessFile(archive, "r");
             SevenZFile sevenZ = new SevenZFile(file.getChannel(), archive.getName())) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                File target = targetFile(entry.isDirectory(), entry.getName(), targetDir);
                if (target != null && entry.hasStream()) {
                    try (OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int read;
                        while ((read = sevenZ.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                    extracted.add(target);
                }
            }
        }
    }

    /**
     * Where to extract an entry, or null to skip it. Only the last path segment is kept, which
     * also keeps entries such as {@code ../x} inside the target folder.
     */
    @Nullable
    private static File targetFile(boolean isDirectory, @Nullable String entryName, @NonNull File targetDir) {
        if (isDirectory || entryName == null) {
            return null;
        }
        String fileName = DictionaryDownloads.sanitize(entryName);
        if (fileName.isEmpty() || !DictionaryDownloads.isDictionaryFile(fileName)) {
            return null;
        }
        return new File(targetDir, fileName);
    }

    private static void write(@NonNull InputStream in, @NonNull File target) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}

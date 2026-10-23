package com.example.walking_meditation.utils;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class CacheFiles {
    private static final String[] SENSITIVE_CACHE_DIRECTORIES = {"routes", "scripts"};

    private CacheFiles() {}

    /**
     * Writes text to <cacheDir>/<subDir>/<prefix>_<timestamp>.<ext>
     * Returns absolute path to the created file.
     */
    @NonNull
    public static String writeText(
            @NonNull Context context,
            @NonNull String subDir,
            @NonNull String prefix,
            @NonNull String ext,
            @NonNull String text
    ) throws IOException {

        File dir = new File(context.getCacheDir(), subDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create cache directory: " + dir.getAbsolutePath());
        }

        String safeExt = ext.startsWith(".") ? ext : ("." + ext);
        File file = new File(dir, prefix + "_" + System.currentTimeMillis() + safeExt);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }

        return file.getAbsolutePath();
    }

    /** Reads a UTF-8 text file fully and returns its contents. */
    @NonNull
    public static String readText(@NonNull String absolutePath) throws IOException {
        File f = new File(absolutePath);
        if (!f.exists()) throw new IOException("File not found: " + absolutePath);
        if (!f.isFile()) throw new IOException("Not a file: " + absolutePath);

        StringBuilder sb = new StringBuilder((int) Math.min(f.length(), 1024 * 1024));
        try (FileInputStream fis = new FileInputStream(f);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {

            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** Optional convenience to delete a cached file by path. */
    public static boolean deleteQuietly(@NonNull String absolutePath) {
        try {
            return new File(absolutePath).delete();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean deleteOwnedFile(
            @NonNull Context context,
            @NonNull String absolutePath
    ) {
        File file = new File(absolutePath);
        try {
            String cacheRoot = context.getCacheDir().getCanonicalPath() + File.separator;
            if (!file.getCanonicalPath().startsWith(cacheRoot)) {
                return false;
            }
            return !file.exists() || file.delete();
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    public static void cleanupExpiredSensitiveFiles(
            @NonNull Context context,
            long retentionMs
    ) {
        long cutoff = System.currentTimeMillis() - retentionMs;
        for (String directoryName : SENSITIVE_CACHE_DIRECTORIES) {
            File[] files = new File(context.getCacheDir(), directoryName).listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file.isFile() && file.lastModified() < cutoff) {
                    file.delete();
                }
            }
        }
    }

    public static void clearSensitiveFiles(@NonNull Context context) {
        for (String directoryName : SENSITIVE_CACHE_DIRECTORIES) {
            deleteDirectoryContents(new File(context.getCacheDir(), directoryName));
        }
        File[] privateFiles = context.getFilesDir().listFiles();
        if (privateFiles == null) return;
        for (File file : privateFiles) {
            if (file.isFile()
                    && file.getName().startsWith("tts_")
                    && file.getName().endsWith(".mp3")) {
                file.delete();
            }
        }
    }

    private static void deleteDirectoryContents(@NonNull File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) file.delete();
        }
        directory.delete();
    }
}

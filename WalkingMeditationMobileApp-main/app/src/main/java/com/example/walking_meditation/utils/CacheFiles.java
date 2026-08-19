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
}
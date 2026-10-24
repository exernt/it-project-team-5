package com.example.walking_meditation.utils;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.walking_meditation.models.RoutePlan;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class RoutePlanFiles {
    private static final Gson GSON = new Gson();
    private static final String DIRECTORY_NAME = "navigation_routes";
    public static final long RETENTION_MS = 24L * 60L * 60L * 1000L;

    private RoutePlanFiles() {}

    @NonNull
    public static String save(@NonNull Context context, @NonNull RoutePlan plan)
            throws IOException {
        cleanupExpired(context);
        File directory = routeDirectory(context);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create navigation route directory");
        }
        File output = new File(directory, "route_" + plan.routeId + ".json");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(GSON.toJson(plan).getBytes(StandardCharsets.UTF_8));
            stream.flush();
        }
        return output.getAbsolutePath();
    }

    @NonNull
    public static RoutePlan read(@NonNull Context context, @NonNull String path)
            throws IOException {
        if (!isOwnedRoutePlan(context, new File(path))) {
            throw new IOException("Route plan is outside app-owned route storage");
        }
        RoutePlan plan = GSON.fromJson(CacheFiles.readText(path), RoutePlan.class);
        if (plan == null || plan.legs == null || plan.legs.isEmpty()) {
            throw new IOException("Selected route plan is empty");
        }
        return plan;
    }

    public static boolean delete(@NonNull Context context, @NonNull String path) {
        File file = new File(path);
        if (!isOwnedRoutePlan(context, file)) {
            return false;
        }
        return !file.exists() || file.delete();
    }

    /** Removes plans older than the documented one-day recovery window. */
    public static void cleanupExpired(@NonNull Context context) {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        deleteExpiredFiles(routeDirectory(context), cutoff);
        // Clean plans written by versions that used the backup-eligible files directory.
        deleteExpiredFiles(legacyRouteDirectory(context), cutoff);
    }

    public static void clearAll(@NonNull Context context) {
        deleteAllFiles(routeDirectory(context));
        deleteAllFiles(legacyRouteDirectory(context));
    }

    @NonNull
    private static File routeDirectory(@NonNull Context context) {
        return new File(context.getNoBackupFilesDir(), DIRECTORY_NAME);
    }

    @NonNull
    private static File legacyRouteDirectory(@NonNull Context context) {
        return new File(context.getFilesDir(), DIRECTORY_NAME);
    }

    private static boolean isOwnedRoutePlan(@NonNull Context context, @NonNull File file) {
        return isInside(file, routeDirectory(context))
                || isInside(file, legacyRouteDirectory(context));
    }

    private static boolean isInside(@NonNull File file, @NonNull File directory) {
        try {
            String filePath = file.getCanonicalPath();
            String directoryPath = directory.getCanonicalPath() + File.separator;
            return filePath.startsWith(directoryPath);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void deleteExpiredFiles(@NonNull File directory, long cutoff) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    private static void deleteAllFiles(@NonNull File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) file.delete();
        }
        directory.delete();
    }
}

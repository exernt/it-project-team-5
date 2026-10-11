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

    private RoutePlanFiles() {}

    @NonNull
    public static String save(@NonNull Context context, @NonNull RoutePlan plan)
            throws IOException {
        File directory = new File(context.getFilesDir(), "navigation_routes");
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
    public static RoutePlan read(@NonNull String path) throws IOException {
        RoutePlan plan = GSON.fromJson(CacheFiles.readText(path), RoutePlan.class);
        if (plan == null || plan.legs == null || plan.legs.isEmpty()) {
            throw new IOException("Selected route plan is empty");
        }
        return plan;
    }
}

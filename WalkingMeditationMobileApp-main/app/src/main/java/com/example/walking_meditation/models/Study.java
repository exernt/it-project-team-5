package com.example.walking_meditation.models;

import androidx.annotation.NonNull;

public final class Study {
    private final String id;
    private final String name;
    private final int duration;
    private final String description;
    private final String status;
    private final String scriptGenerationParameters;

    public Study(
            @NonNull String id,
            @NonNull String name,
            int duration,
            @NonNull String description,
            @NonNull String status
    ) {
        this(id, name, duration, description, status, "");
    }

    public Study(
            @NonNull String id,
            @NonNull String name,
            int duration,
            @NonNull String description,
            @NonNull String status,
            @NonNull String scriptGenerationParameters
    ) {
        this.id = id;
        this.name = name;
        this.duration = duration;
        this.description = description;
        this.status = status;
        this.scriptGenerationParameters = scriptGenerationParameters;
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public int getDuration() {
        return duration;
    }

    @NonNull
    public String getDescription() {
        return description;
    }

    @NonNull
    public String getStatus() {
        return status;
    }

    @NonNull
    public String getScriptGenerationParameters() {
        return scriptGenerationParameters;
    }

    public boolean isOpen() {
        return "recruiting".equals(status) || "active".equals(status);
    }
}

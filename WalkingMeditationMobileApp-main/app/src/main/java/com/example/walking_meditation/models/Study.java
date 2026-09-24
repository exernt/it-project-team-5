package com.example.walking_meditation.models;

import androidx.annotation.NonNull;

public final class Study {
    private final String id;
    private final String name;
    private final int duration;
    private final String description;
    private final String status;

    public Study(
            @NonNull String id,
            @NonNull String name,
            int duration,
            @NonNull String description,
            @NonNull String status
    ) {
        this.id = id;
        this.name = name;
        this.duration = duration;
        this.description = description;
        this.status = status;
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

    public boolean isOpen() {
        return "recruiting".equals(status) || "active".equals(status);
    }
}

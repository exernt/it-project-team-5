package com.example.walking_meditation.models;

import androidx.annotation.NonNull;

public final class Study {
    private final String id;
    private final String name;
    private final int duration;
    private final String description;
    private final String status;
    private final String controlScriptGenerationParameters;
    private final String experimentalScriptGenerationParameters;
    private final String controlTtsVoice;
    private final String experimentalTtsVoice;

    public Study(
            @NonNull String id,
            @NonNull String name,
            int duration,
            @NonNull String description,
            @NonNull String status
    ) {
        this(id, name, duration, description, status, "", "", "male", "male");
    }

    public Study(
            @NonNull String id,
            @NonNull String name,
            int duration,
            @NonNull String description,
            @NonNull String status,
            @NonNull String controlScriptGenerationParameters,
            @NonNull String experimentalScriptGenerationParameters
    ) {
        this(
                id,
                name,
                duration,
                description,
                status,
                controlScriptGenerationParameters,
                experimentalScriptGenerationParameters,
                "male",
                "male"
        );
    }

    public Study(
            @NonNull String id,
            @NonNull String name,
            int duration,
            @NonNull String description,
            @NonNull String status,
            @NonNull String controlScriptGenerationParameters,
            @NonNull String experimentalScriptGenerationParameters,
            @NonNull String controlTtsVoice,
            @NonNull String experimentalTtsVoice
    ) {
        this.id = id;
        this.name = name;
        this.duration = duration;
        this.description = description;
        this.status = status;
        this.controlScriptGenerationParameters = controlScriptGenerationParameters;
        this.experimentalScriptGenerationParameters = experimentalScriptGenerationParameters;
        this.controlTtsVoice = controlTtsVoice;
        this.experimentalTtsVoice = experimentalTtsVoice;
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
    public String getControlScriptGenerationParameters() {
        return controlScriptGenerationParameters;
    }

    @NonNull
    public String getExperimentalScriptGenerationParameters() {
        return experimentalScriptGenerationParameters;
    }

    @NonNull
    public String getControlTtsVoice() {
        return controlTtsVoice;
    }

    @NonNull
    public String getExperimentalTtsVoice() {
        return experimentalTtsVoice;
    }

    public boolean isOpen() {
        return "recruiting".equals(status) || "active".equals(status);
    }
}

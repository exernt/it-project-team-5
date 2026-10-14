package com.example.walking_meditation.models;

import androidx.annotation.NonNull;
import org.json.JSONObject;

public class QuestionnaireItem {
    private final String id;
    private final String title;
    private final String status;
    private final JSONObject definition;

    public QuestionnaireItem(@NonNull String id, @NonNull String title, @NonNull String status, @NonNull JSONObject definition) {
        this.id = id;
        this.title = title;
        this.status = status;
        this.definition = definition;
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getTitle() { return title; }
    @NonNull public String getStatus() { return status; }
    @NonNull public JSONObject getDefinition() { return definition; }
}

package com.example.walking_meditation.utils;

import androidx.annotation.NonNull;

public final class QuestionnairePublishing {
    private QuestionnairePublishing() {}

    public static boolean canPublish(
            @NonNull String studyStatus,
            @NonNull String questionnaireId,
            boolean questionnairePublished,
            boolean hasUnsavedChanges
    ) {
        return "active".equals(studyStatus)
                && !questionnaireId.isEmpty()
                && !questionnairePublished
                && !hasUnsavedChanges;
    }
}

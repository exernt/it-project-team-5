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

    public static boolean canIssue(
            @NonNull String studyStatus,
            @NonNull String studyId,
            @NonNull String questionnaireId,
            @NonNull String questionnaireStatus
    ) {
        return "active".equals(studyStatus)
                && !studyId.isEmpty()
                && !questionnaireId.isEmpty()
                && "published".equals(questionnaireStatus);
    }
}

package com.example.walking_meditation.models;

import androidx.annotation.NonNull;

import java.time.Instant;

public final class StudyEnrollment {
    private final Study study;
    private final Instant registeredAt;

    public StudyEnrollment(@NonNull Study study, @NonNull Instant registeredAt) {
        this.study = study;
        this.registeredAt = registeredAt;
    }

    @NonNull
    public Study getStudy() {
        return study;
    }

    @NonNull
    public Instant getRegisteredAt() {
        return registeredAt;
    }
}

package com.example.walking_meditation.models;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.Instant;

public final class StudyEnrollment {
    private final Study study;
    private final Instant registeredAt;
    private final @Nullable Instant activatedAt;

    public StudyEnrollment(@NonNull Study study, @NonNull Instant registeredAt) {
        this(study, registeredAt, null);
    }

    public StudyEnrollment(
            @NonNull Study study,
            @NonNull Instant registeredAt,
            @Nullable Instant activatedAt
    ) {
        this.study = study;
        this.registeredAt = registeredAt;
        this.activatedAt = activatedAt;
    }

    @NonNull
    public Study getStudy() {
        return study;
    }

    @NonNull
    public Instant getRegisteredAt() {
        return registeredAt;
    }

    @Nullable
    public Instant getActivatedAt() {
        return activatedAt;
    }
}

package com.example.walking_meditation.utils;

import androidx.annotation.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class StudyTime {
    private static final long MILLIS_PER_DAY = Duration.ofDays(1).toMillis();

    private StudyTime() {}

    public static long remainingDays(
            @NonNull Instant registeredAt,
            int studyDurationDays,
            @NonNull Instant now
    ) {
        Instant studyEnd = registeredAt.plus(studyDurationDays, ChronoUnit.DAYS);
        long remainingMillis = Duration.between(now, studyEnd).toMillis();
        if (remainingMillis <= 0) {
            return 0;
        }
        return (remainingMillis + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY;
    }
}

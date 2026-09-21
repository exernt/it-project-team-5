package com.example.walking_meditation;

import static org.junit.Assert.assertEquals;

import com.example.walking_meditation.utils.StudyTime;

import org.junit.Test;

import java.time.Instant;

public class StudyTimeTest {
    @Test
    public void roundsPartialRemainingDaysUp() {
        Instant registeredAt = Instant.parse("2026-09-01T00:00:00Z");
        Instant now = Instant.parse("2026-09-02T12:00:00Z");

        assertEquals(6, StudyTime.remainingDays(registeredAt, 7, now));
    }

    @Test
    public void completedStudyHasNoRemainingDays() {
        Instant registeredAt = Instant.parse("2026-09-01T00:00:00Z");
        Instant now = Instant.parse("2026-09-09T00:00:00Z");

        assertEquals(0, StudyTime.remainingDays(registeredAt, 7, now));
    }
}

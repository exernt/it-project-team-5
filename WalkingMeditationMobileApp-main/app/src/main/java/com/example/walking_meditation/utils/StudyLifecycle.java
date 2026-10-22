package com.example.walking_meditation.utils;

import androidx.annotation.Nullable;

public final class StudyLifecycle {
    private StudyLifecycle() {}

    @Nullable
    public static String nextAction(String status) {
        switch (status) {
            case "draft": return "publish";
            case "recruiting": return "activate";
            case "active": return "close";
            default: return null;
        }
    }
}
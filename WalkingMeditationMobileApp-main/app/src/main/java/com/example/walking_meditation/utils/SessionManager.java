package com.example.walking_meditation.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class SessionManager {
    private static final String PREFS = "walking_meditation_session";
    private static final String ACCESS_TOKEN = "access_token";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String STUDY_ACTIVE = "study_active";
    private static final String STUDY_ID = "study_id";
    private static final String STUDY_PARAMETERS = "study_script_parameters";

    private SessionManager() {}

    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveTokens(
            @NonNull Context context,
            @NonNull String accessToken,
            @NonNull String refreshToken
    ) {
        preferences(context).edit()
                .putString(ACCESS_TOKEN, accessToken)
                .putString(REFRESH_TOKEN, refreshToken)
                .apply();
    }

    @Nullable
    public static String getAccessToken(@NonNull Context context) {
        return preferences(context).getString(ACCESS_TOKEN, null);
    }

    @Nullable
    public static String getRefreshToken(@NonNull Context context) {
        return preferences(context).getString(REFRESH_TOKEN, null);
    }

    public static void cacheStudyConfiguration(
            @NonNull Context context,
            @NonNull String studyId,
            @NonNull String parameters
    ) {
        preferences(context).edit()
                .putBoolean(STUDY_ACTIVE, true)
                .putString(STUDY_ID, studyId)
                .putString(STUDY_PARAMETERS, parameters)
                .apply();
    }

    public static void clearStudyConfiguration(@NonNull Context context) {
        preferences(context).edit()
                .remove(STUDY_ACTIVE)
                .remove(STUDY_ID)
                .remove(STUDY_PARAMETERS)
                .apply();
    }

    public static boolean hasActiveStudy(@NonNull Context context) {
        return preferences(context).getBoolean(STUDY_ACTIVE, false);
    }

    @NonNull
    public static String getStudyId(@NonNull Context context) {
        return preferences(context).getString(STUDY_ID, "");
    }

    @NonNull
    public static String getStudyParameters(@NonNull Context context) {
        return preferences(context).getString(STUDY_PARAMETERS, "");
    }
}

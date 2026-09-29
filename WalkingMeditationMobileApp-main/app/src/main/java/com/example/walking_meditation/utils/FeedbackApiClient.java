package com.example.walking_meditation.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.walking_meditation.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Submits participant feedback to the research server. Feedback can be
 * general (no study) or tied to a specific study the user is enrolled in.
 */
public final class FeedbackApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String FEEDBACK_URL = BuildConfig.SERVER_BASE_URL + "/feedback";
    private static final String STUDIES_URL = BuildConfig.SERVER_BASE_URL + "/studies";
    private static final String REFRESH_URL = BuildConfig.SERVER_BASE_URL + "/auth/refresh";

    public interface SubmitListener {
        void onSubmitted();
        void onFailure(@NonNull String message);
    }

    public static final class FeedbackItem {
        public final String id;
        public final String userId;
        public final @Nullable String studyId;
        public final String category;
        public final @Nullable Integer rating;
        public final @Nullable String comment;
        public final Instant createdAt;

        public FeedbackItem(
                @NonNull String id,
                @NonNull String userId,
                @Nullable String studyId,
                @NonNull String category,
                @Nullable Integer rating,
                @Nullable String comment,
                @NonNull Instant createdAt
        ) {
            this.id = id;
            this.userId = userId;
            this.studyId = studyId;
            this.category = category;
            this.rating = rating;
            this.comment = comment;
            this.createdAt = createdAt;
        }
    }

    public interface FeedbackListListener {
        void onLoaded(@NonNull List<FeedbackItem> feedback);
        void onFailure(@NonNull String message);
    }

    private FeedbackApiClient() {}

    public static void submitFeedback(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @Nullable String studyId,
            @NonNull String category,
            @Nullable Integer rating,
            @Nullable String comment,
            @NonNull SubmitListener listener
    ) {
        try {
            JSONObject payload = new JSONObject();
            if (studyId != null && !studyId.isEmpty()) {
                payload.put("study_id", studyId);
            }
            payload.put("category", category);
            payload.put("rating", rating == null ? JSONObject.NULL : rating);
            payload.put("comment", comment == null || comment.isEmpty() ? JSONObject.NULL : comment);

            executeAuthenticatedRequest(
                    context.getApplicationContext(),
                    client,
                    payload.toString(),
                    true,
                    listener
            );
        } catch (Exception e) {
            listener.onFailure("Unable to prepare the feedback request");
        }
    }

    public static void fetchStudyFeedback(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull FeedbackListListener listener
    ) {
        executeAuthenticatedGet(
                context.getApplicationContext(),
                client,
                STUDIES_URL + "/" + studyId + "/feedback",
                true,
                listener
        );
    }

    public static void fetchGeneralFeedback(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull FeedbackListListener listener
    ) {
        executeAuthenticatedGet(
                context.getApplicationContext(),
                client,
                FEEDBACK_URL,
                true,
                listener
        );
    }

    private static void executeAuthenticatedGet(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String url,
            boolean allowRefresh,
            @NonNull FeedbackListListener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Your session has expired. Please sign in again.");
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                listener.onFailure(e.getMessage() == null ? "Unable to contact the server" : e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closedResponse = response) {
                    if (closedResponse.code() == 401 && allowRefresh) {
                        refreshAndRetryGet(context, client, url, listener);
                        return;
                    }
                    String body = closedResponse.body() == null ? "" : closedResponse.body().string();
                    if (!closedResponse.isSuccessful()) {
                        listener.onFailure(errorMessage(body, closedResponse.code()));
                        return;
                    }
                    listener.onLoaded(parseFeedbackList(body));
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid feedback response" : e.getMessage());
                }
            }
        });
    }

    private static void refreshAndRetryGet(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String url,
            @NonNull FeedbackListListener listener
    ) {
        String refreshToken = SessionManager.getRefreshToken(context);
        if (refreshToken == null || refreshToken.isEmpty()) {
            listener.onFailure("Your session has expired. Please sign in again.");
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("refresh_token", refreshToken);
            Request request = new Request.Builder()
                    .url(REFRESH_URL)
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    listener.onFailure(e.getMessage() == null ? "Unable to refresh session" : e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closedResponse = response) {
                        if (!closedResponse.isSuccessful() || closedResponse.body() == null) {
                            listener.onFailure("Your session has expired. Please sign in again.");
                            return;
                        }
                        JSONObject body = new JSONObject(closedResponse.body().string());
                        String newAccessToken = body.getString("access_token");
                        String newRefreshToken = body.getString("refresh_token");
                        SessionManager.saveTokens(context, newAccessToken, newRefreshToken);
                        executeAuthenticatedGet(context, client, url, false, listener);
                    } catch (Exception e) {
                        listener.onFailure(e.getMessage() == null ? "Invalid refresh response" : e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            listener.onFailure(e.getMessage() == null ? "Unable to refresh session" : e.getMessage());
        }
    }

    @NonNull
    private static List<FeedbackItem> parseFeedbackList(@NonNull String responseBody) throws Exception {
        JSONArray items = new JSONObject(responseBody).getJSONArray("feedback");
        List<FeedbackItem> feedback = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            feedback.add(new FeedbackItem(
                    item.getString("id"),
                    item.getString("user_id"),
                    item.isNull("study_id") ? null : item.getString("study_id"),
                    item.getString("category"),
                    item.isNull("rating") ? null : item.getInt("rating"),
                    item.isNull("comment") ? null : item.getString("comment"),
                    OffsetDateTime.parse(item.getString("created_at")).toInstant()
            ));
        }
        return feedback;
    }

    private static void executeAuthenticatedRequest(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String requestBody,
            boolean allowRefresh,
            @NonNull SubmitListener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Your session has expired. Please sign in again.");
            return;
        }

        Request request = new Request.Builder()
                .url(FEEDBACK_URL)
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(requestBody, JSON))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                listener.onFailure(e.getMessage() == null ? "Unable to contact the server" : e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closedResponse = response) {
                    if (closedResponse.code() == 401 && allowRefresh) {
                        refreshAndRetry(context, client, requestBody, listener);
                        return;
                    }
                    if (closedResponse.isSuccessful()) {
                        listener.onSubmitted();
                        return;
                    }
                    String body = closedResponse.body() == null ? "" : closedResponse.body().string();
                    listener.onFailure(errorMessage(body, closedResponse.code()));
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid feedback response" : e.getMessage());
                }
            }
        });
    }

    @NonNull
    private static String errorMessage(@NonNull String responseBody, int statusCode) {
        try {
            String detail = new JSONObject(responseBody).optString("detail", "");
            if (!detail.isEmpty()) {
                return detail;
            }
        } catch (Exception ignored) {
            // Fall through to the status-based message.
        }
        return "Feedback submission failed (" + statusCode + ")";
    }

    private static void refreshAndRetry(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String requestBody,
            @NonNull SubmitListener listener
    ) {
        String refreshToken = SessionManager.getRefreshToken(context);
        if (refreshToken == null || refreshToken.isEmpty()) {
            listener.onFailure("Your session has expired. Please sign in again.");
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("refresh_token", refreshToken);
            Request request = new Request.Builder()
                    .url(REFRESH_URL)
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    listener.onFailure(e.getMessage() == null ? "Unable to refresh session" : e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closedResponse = response) {
                        if (!closedResponse.isSuccessful() || closedResponse.body() == null) {
                            listener.onFailure("Your session has expired. Please sign in again.");
                            return;
                        }
                        JSONObject body = new JSONObject(closedResponse.body().string());
                        String newAccessToken = body.getString("access_token");
                        String newRefreshToken = body.getString("refresh_token");
                        SessionManager.saveTokens(context, newAccessToken, newRefreshToken);
                        executeAuthenticatedRequest(context, client, requestBody, false, listener);
                    } catch (Exception e) {
                        listener.onFailure(e.getMessage() == null ? "Invalid refresh response" : e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            listener.onFailure(e.getMessage() == null ? "Unable to refresh session" : e.getMessage());
        }
    }
}
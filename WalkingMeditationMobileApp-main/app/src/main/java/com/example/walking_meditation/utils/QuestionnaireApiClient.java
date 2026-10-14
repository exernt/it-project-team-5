package com.example.walking_meditation.utils;

import android.content.Context;
import androidx.annotation.NonNull;

import com.example.walking_meditation.BuildConfig;
import com.example.walking_meditation.models.QuestionnaireItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class QuestionnaireApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String QUESTIONNAIRES_URL = BuildConfig.SERVER_BASE_URL + "/questionnaires";

    public interface Listener {
        void onSuccess(@NonNull String questionnaireId);
        void onFailure(@NonNull String error);
    }

    public interface QuestionnaireListListener {
        void onLoaded(@NonNull List<QuestionnaireItem> questionnaires);
        void onFailure(@NonNull String error);
    }

    private QuestionnaireApiClient() {}

    public static void createQuestionnaire(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull JSONObject definition,
            @NonNull Listener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Not authenticated");
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("definition", definition);

            Request request = new Request.Builder()
                    .url(QUESTIONNAIRES_URL)
                    .header("Authorization", "Bearer " + accessToken)
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    listener.onFailure(e.getMessage() == null ? "Network error" : e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closed = response) {
                        String body = closed.body() != null ? closed.body().string() : "";
                        if (!closed.isSuccessful()) {
                            listener.onFailure("Failed to create questionnaire (" + closed.code() + ")");
                            return;
                        }
                        JSONObject json = new JSONObject(body);
                        String id = json.getString("id");
                        listener.onSuccess(id);
                    } catch (Exception e) {
                        listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            listener.onFailure(e.getMessage() == null ? "Unable to prepare request" : e.getMessage());
        }
    }

    public static void listStudyQuestionnaires(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull QuestionnaireListListener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Not authenticated");
            return;
        }

        Request request = new Request.Builder()
                .url(BuildConfig.SERVER_BASE_URL + "/studies/" + studyId + "/questionnaires")
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                listener.onFailure(e.getMessage() == null ? "Network error" : e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closed = response) {
                    String body = closed.body() != null ? closed.body().string() : "";
                    if (!closed.isSuccessful()) {
                        listener.onFailure("Failed to load study questionnaires (" + closed.code() + ")");
                        return;
                    }
                    JSONObject json = new JSONObject(body);
                    JSONArray array = json.getJSONArray("issued_questionnaires");
                    List<QuestionnaireItem> list = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        String id = obj.optString("questionnaire_id", obj.optString("id", ""));
                        String title = obj.optString("title", "Questionnaire");
                        list.add(new QuestionnaireItem(
                                id,
                                title,
                                "published",
                                new JSONObject()
                        ));
                    }
                    listener.onLoaded(list);
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                }
            }
        });
    }

    public static void createAndIssueStudyQuestionnaire(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull JSONObject definition,
            @NonNull Listener listener
    ) {
        createQuestionnaire(context, client, definition, new Listener() {
            @Override
            public void onSuccess(@NonNull String questionnaireId) {
                publishQuestionnaire(context, client, questionnaireId, new Listener() {
                    @Override
                    public void onSuccess(@NonNull String pubId) {
                        issueQuestionnaireToStudy(context, client, studyId, pubId, listener);
                    }

                    @Override
                    public void onFailure(@NonNull String error) {
                        listener.onFailure("Failed to publish questionnaire: " + error);
                    }
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                listener.onFailure(error);
            }
        });
    }

    public static void issueQuestionnaireToStudy(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull String questionnaireId,
            @NonNull Listener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Not authenticated");
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("questionnaire_id", questionnaireId);

            Request request = new Request.Builder()
                    .url(BuildConfig.SERVER_BASE_URL + "/studies/" + studyId + "/questionnaires")
                    .header("Authorization", "Bearer " + accessToken)
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    listener.onFailure(e.getMessage() == null ? "Network error" : e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closed = response) {
                        if (!closed.isSuccessful()) {
                            listener.onFailure("Failed to attach questionnaire to study (" + closed.code() + ")");
                            return;
                        }
                        listener.onSuccess(questionnaireId);
                    } catch (Exception e) {
                        listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            listener.onFailure(e.getMessage() == null ? "Unable to prepare request" : e.getMessage());
        }
    }

    public static void listQuestionnaires(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull QuestionnaireListListener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Not authenticated");
            return;
        }

        Request request = new Request.Builder()
                .url(QUESTIONNAIRES_URL)
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                listener.onFailure(e.getMessage() == null ? "Network error" : e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closed = response) {
                    String body = closed.body() != null ? closed.body().string() : "";
                    if (!closed.isSuccessful()) {
                        listener.onFailure("Failed to load questionnaires (" + closed.code() + ")");
                        return;
                    }
                    JSONObject json = new JSONObject(body);
                    JSONArray array = json.getJSONArray("questionnaires");
                    List<QuestionnaireItem> list = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        list.add(new QuestionnaireItem(
                                obj.getString("id"),
                                obj.getString("title"),
                                obj.getString("status"),
                                obj.getJSONObject("definition")
                        ));
                    }
                    listener.onLoaded(list);
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                }
            }
        });
    }

    public static void editQuestionnaire(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String questionnaireId,
            @NonNull JSONObject definition,
            @NonNull Listener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Not authenticated");
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("definition", definition);

            Request request = new Request.Builder()
                    .url(QUESTIONNAIRES_URL + "/" + questionnaireId)
                    .header("Authorization", "Bearer " + accessToken)
                    .put(RequestBody.create(payload.toString(), JSON))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    listener.onFailure(e.getMessage() == null ? "Network error" : e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closed = response) {
                        if (!closed.isSuccessful()) {
                            listener.onFailure("Failed to update questionnaire (" + closed.code() + ")");
                            return;
                        }
                        listener.onSuccess(questionnaireId);
                    } catch (Exception e) {
                        listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            listener.onFailure(e.getMessage() == null ? "Unable to prepare request" : e.getMessage());
        }
    }

    public static void publishQuestionnaire(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String questionnaireId,
            @NonNull Listener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Not authenticated");
            return;
        }

        Request request = new Request.Builder()
                .url(QUESTIONNAIRES_URL + "/" + questionnaireId + "/publish")
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(new byte[0], null))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                listener.onFailure(e.getMessage() == null ? "Network error" : e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closed = response) {
                    if (!closed.isSuccessful()) {
                        listener.onFailure("Failed to publish questionnaire (" + closed.code() + ")");
                        return;
                    }
                    listener.onSuccess(questionnaireId);
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                }
            }
        });
    }
}

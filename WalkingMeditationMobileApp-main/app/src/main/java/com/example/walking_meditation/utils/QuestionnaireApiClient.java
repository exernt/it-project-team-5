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

    public interface ParticipantDetailListener {
        void onLoaded(@NonNull String title, @NonNull JSONObject definition);
        void onFailure(@NonNull String error);
    }

    public interface QuestionnaireResponsesListener {
        void onLoaded(@NonNull JSONArray responses);
        void onFailure(@NonNull String error);
    }

    private QuestionnaireApiClient() {}

    private static String parseError(@NonNull Response response) {
        try {
            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(body);
            if (json.has("detail")) {
                return json.getString("detail");
            }
        } catch (Exception ignored) {}
        return "Request failed (" + response.code() + ")";
    }

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
                        if (!closed.isSuccessful()) {
                            listener.onFailure(parseError(closed));
                            return;
                        }
                        String body = closed.body() != null ? closed.body().string() : "";
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

    public static void listParticipantQuestionnaires(
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
                    if (!closed.isSuccessful()) {
                        listener.onFailure(parseError(closed));
                        return;
                    }
                    String body = closed.body() != null ? closed.body().string() : "";
                    JSONObject json = new JSONObject(body);
                    JSONArray array = json.getJSONArray("issued_questionnaires");
                    List<QuestionnaireItem> list = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        String id = obj.getString("id");
                        String title = obj.getString("title");
                        boolean hasResponded = obj.optBoolean("has_responded", false);
                        String status = hasResponded ? "completed" : "pending";
                        list.add(new QuestionnaireItem(id, title, status, new JSONObject()));
                    }
                    listener.onLoaded(list);
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                }
            }
        });
    }

    public static void getParticipantQuestionnaireDetail(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull String studyQuestionnaireId,
            @NonNull ParticipantDetailListener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Not authenticated");
            return;
        }

        Request request = new Request.Builder()
                .url(BuildConfig.SERVER_BASE_URL + "/studies/" + studyId + "/questionnaires/" + studyQuestionnaireId)
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
                    if (!closed.isSuccessful()) {
                        listener.onFailure(parseError(closed));
                        return;
                    }
                    String body = closed.body() != null ? closed.body().string() : "";
                    JSONObject json = new JSONObject(body);
                    String title = json.optString("title", "Questionnaire");
                    JSONObject definition = json.optJSONObject("definition");
                    if (definition == null) definition = new JSONObject();
                    listener.onLoaded(title, definition);
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                }
            }
        });
    }

    public static void submitAnswers(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull String studyQuestionnaireId,
            @NonNull JSONObject answers,
            @NonNull Listener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Not authenticated");
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("answers", answers);

            Request request = new Request.Builder()
                    .url(BuildConfig.SERVER_BASE_URL + "/studies/" + studyId + "/questionnaires/" + studyQuestionnaireId + "/responses")
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
                            listener.onFailure(parseError(closed));
                            return;
                        }
                        listener.onSuccess(studyQuestionnaireId);
                    } catch (Exception e) {
                        listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            listener.onFailure(e.getMessage() == null ? "Unable to prepare request" : e.getMessage());
        }
    }

    public static void getQuestionnaireResponses(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull String studyQuestionnaireId,
            @NonNull QuestionnaireResponsesListener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Not authenticated");
            return;
        }

        Request request = new Request.Builder()
                .url(BuildConfig.SERVER_BASE_URL + "/studies/" + studyId + "/questionnaires/" + studyQuestionnaireId + "/responses")
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
                    if (!closed.isSuccessful()) {
                        listener.onFailure(parseError(closed));
                        return;
                    }
                    String body = closed.body() != null ? closed.body().string() : "";
                    JSONObject json = new JSONObject(body);
                    JSONArray array = json.getJSONArray("responses");
                    listener.onLoaded(array);
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                }
            }
        });
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
                    if (!closed.isSuccessful()) {
                        listener.onFailure(parseError(closed));
                        return;
                    }
                    String body = closed.body() != null ? closed.body().string() : "";
                    JSONObject json = new JSONObject(body);
                    JSONArray array = json.getJSONArray("issued_questionnaires");
                    List<QuestionnaireItem> list = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        String id = obj.getString("id");
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

    public static void publishAndIssueStudyQuestionnaire(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull String questionnaireId,
            @NonNull Listener listener
    ) {
        publishQuestionnaire(context, client, questionnaireId, new Listener() {
            @Override
            public void onSuccess(@NonNull String publishedQuestionnaireId) {
                issueQuestionnaireToStudy(
                        context,
                        client,
                        studyId,
                        publishedQuestionnaireId,
                        listener
                );
            }

            @Override
            public void onFailure(@NonNull String error) {
                listener.onFailure("Failed to publish questionnaire: " + error);
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
                            listener.onFailure(parseError(closed));
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
                    if (!closed.isSuccessful()) {
                        listener.onFailure(parseError(closed));
                        return;
                    }
                    String body = closed.body() != null ? closed.body().string() : "";
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
                            listener.onFailure(parseError(closed));
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
                        listener.onFailure(parseError(closed));
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

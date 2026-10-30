package com.example.walking_meditation;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.walking_meditation.models.Study;
import com.example.walking_meditation.utils.QuestionnaireApiClient;
import com.example.walking_meditation.utils.QuestionnairePublishing;
import com.example.walking_meditation.utils.StudyApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

import okhttp3.OkHttpClient;

public class ResearcherCreateQuestionnaireActivity extends AppCompatActivity {
    private static final String STATE_QUESTIONNAIRE_ID = "questionnaire_id";
    private static final String STATE_QUESTIONNAIRE_STATUS = "questionnaire_status";
    private static final String STATE_QUESTIONS = "questions";
    private static final String STATE_PUBLISHED = "questionnaire_published";
    private static final String STATE_DIRTY = "questionnaire_dirty";
    private static final String STATE_PENDING_CHOICES = "pending_choices";
    private static final String STATE_SELECTED_TYPE = "selected_question_type";
    private static final String STATE_DEFINITION = "questionnaire_definition";

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ActivityResultLauncher<Intent> copyQuestionnaireLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        Intent data = result.getData();
                        if (result.getResultCode() != RESULT_OK || data == null) return;
                        String definition = data.getStringExtra(
                                ResearcherQuestionnaireCopyActivity.EXTRA_QUESTIONNAIRE_DEFINITION
                        );
                        if (definition != null) applyCopiedDefinition(definition);
                    }
            );
    private EditText titleField, promptField;
    private RadioGroup questionTypeGroup;
    private LinearLayout choiceOptionsSection, choiceOptionsContainer, questionsListContainer;
    private TextView pageTitle, questionsEmptyText, publishHint;
    private Button saveButton, publishButton, issueButton, copyButton, addQuestionButton,
            addChoiceButton;

    private JSONArray questionsArray = new JSONArray();
    private JSONObject currentDefinition = new JSONObject();
    private String studyId = "";
    private String studyStatus = "";
    private String questionnaireId = "";
    private String questionnaireStatus = "draft";
    private boolean questionnairePublished;
    private boolean hasUnsavedChanges = true;
    private boolean requestInProgress;
    private boolean initializing = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_researcher_create_questionnaire);

        studyId = getIntent().getStringExtra("study_id");
        if (studyId == null) studyId = "";

        titleField = findViewById(R.id.createQuestionnaireTitle);
        pageTitle = findViewById(R.id.textViewTitle);
        promptField = findViewById(R.id.createQuestionnairePrompt);
        questionTypeGroup = findViewById(R.id.questionTypeGroup);
        choiceOptionsSection = findViewById(R.id.choiceOptionsSection);
        choiceOptionsContainer = findViewById(R.id.choiceOptionsContainer);
        questionsListContainer = findViewById(R.id.questionsListContainer);
        questionsEmptyText = findViewById(R.id.questionsEmptyText);
        publishHint = findViewById(R.id.createQuestionnairePublishHint);
        saveButton = findViewById(R.id.btnCreateQuestionnaireSubmit);
        publishButton = findViewById(R.id.btnCreateQuestionnairePublish);
        issueButton = findViewById(R.id.btnIssueQuestionnaire);
        copyButton = findViewById(R.id.btnCopyQuestionnaire);
        addQuestionButton = findViewById(R.id.btnAddQuestion);
        addChoiceButton = findViewById(R.id.btnAddChoice);

        if (savedInstanceState != null) {
            questionnaireId = savedInstanceState.getString(STATE_QUESTIONNAIRE_ID, "");
            questionnaireStatus = savedInstanceState.getString(
                    STATE_QUESTIONNAIRE_STATUS,
                    "draft"
            );
            questionnairePublished = savedInstanceState.getBoolean(STATE_PUBLISHED, false);
            hasUnsavedChanges = savedInstanceState.getBoolean(STATE_DIRTY, true);
            try {
                currentDefinition = new JSONObject(savedInstanceState.getString(
                        STATE_DEFINITION,
                        "{}"
                ));
                questionsArray = new JSONArray(savedInstanceState.getString(STATE_QUESTIONS, "[]"));
                currentDefinition.put("questions", questionsArray);
            } catch (Exception ignored) {
                currentDefinition = new JSONObject();
                questionsArray = new JSONArray();
            }
            questionTypeGroup.check(savedInstanceState.getInt(
                    STATE_SELECTED_TYPE,
                    R.id.radioText
            ));
            try {
                JSONArray pendingChoices = new JSONArray(
                        savedInstanceState.getString(STATE_PENDING_CHOICES, "[]")
                );
                for (int index = 0; index < pendingChoices.length(); index++) {
                    addChoiceRow(pendingChoices.optString(index), false);
                }
            } catch (Exception ignored) {
                choiceOptionsContainer.removeAllViews();
            }
        } else {
            questionnaireId = getIntent().getStringExtra("questionnaire_id");
            if (questionnaireId == null) questionnaireId = "";
            questionnaireStatus = getIntent().getStringExtra("questionnaire_status");
            if (questionnaireStatus == null || questionnaireStatus.isEmpty()) {
                questionnaireStatus = "draft";
            } else {
                questionnaireStatus = questionnaireStatus.toLowerCase(java.util.Locale.ROOT);
            }
            questionnairePublished = "published".equalsIgnoreCase(questionnaireStatus)
                    || "archived".equalsIgnoreCase(questionnaireStatus);
            hasUnsavedChanges = questionnaireId.isEmpty();
            String definitionString = getIntent().getStringExtra("questionnaire_definition");
            if (definitionString != null && !definitionString.isEmpty()) {
                try {
                    currentDefinition = new JSONObject(definitionString);
                    questionsArray = currentDefinition.optJSONArray("questions");
                    if (questionsArray == null) questionsArray = new JSONArray();
                    titleField.setText(currentDefinition.optString("title", ""));
                } catch (Exception ignored) {
                    currentDefinition = new JSONObject();
                    questionsArray = new JSONArray();
                }
            }
        }

        if (questionnaireId.isEmpty()) {
            pageTitle.setText("Create Questionnaire");
        } else if ("published".equals(questionnaireStatus)) {
            pageTitle.setText("Review Published Questionnaire");
        } else if (questionnairePublished) {
            pageTitle.setText(R.string.review_questionnaire);
        } else {
            pageTitle.setText("Edit Questionnaire Draft");
        }
        renderQuestionsList();
        choiceOptionsSection.setVisibility(isChoiceTypeSelected() ? View.VISIBLE : View.GONE);
        saveButton.setText(questionnaireId.isEmpty() ? "Create Questionnaire" : "Save Questionnaire");

        findViewById(R.id.btnCreateQuestionnaireCancel).setOnClickListener(view -> finish());
        addQuestionButton.setOnClickListener(view -> addQuestionToList());
        addChoiceButton.setOnClickListener(view -> addChoiceRow());
        saveButton.setOnClickListener(view -> saveQuestionnaire());
        publishButton.setOnClickListener(view -> publishQuestionnaire());
        issueButton.setOnClickListener(view -> issueQuestionnaire());
        copyButton.setOnClickListener(view -> copyQuestionnaireLauncher.launch(
                new Intent(this, ResearcherQuestionnaireCopyActivity.class)
        ));
        questionTypeGroup.setOnCheckedChangeListener((group, checkedId) ->
                {
                    choiceOptionsSection.setVisibility(
                            isChoiceTypeSelected() ? View.VISIBLE : View.GONE
                    );
                    if (!initializing) {
                        hasUnsavedChanges = true;
                        updatePublishUi();
                    }
                }
        );

        titleField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!initializing) {
                    hasUnsavedChanges = true;
                    updatePublishUi();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        promptField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!initializing && s.length() > 0) {
                    hasUnsavedChanges = true;
                    updatePublishUi();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        initializing = false;
        configureEditorMode();
        updatePublishUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStudyStatus();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_QUESTIONNAIRE_ID, questionnaireId);
        outState.putString(STATE_QUESTIONNAIRE_STATUS, questionnaireStatus);
        outState.putString(STATE_QUESTIONS, questionsArray.toString());
        outState.putString(STATE_DEFINITION, currentDefinition.toString());
        outState.putBoolean(STATE_PUBLISHED, questionnairePublished);
        outState.putBoolean(STATE_DIRTY, hasUnsavedChanges);
        outState.putInt(STATE_SELECTED_TYPE, questionTypeGroup.getCheckedRadioButtonId());
        outState.putString(STATE_PENDING_CHOICES, pendingChoiceOptions().toString());
    }

    private void refreshStudyStatus() {
        if (studyId.isEmpty()) {
            studyStatus = "";
            updatePublishUi();
            return;
        }
        StudyApiClient.fetchStudy(this, httpClient, studyId, new StudyApiClient.StudyListener() {
            @Override
            public void onLoaded(@NonNull Study study) {
                runOnUiThread(() -> {
                    studyStatus = study.getStatus();
                    updatePublishUi();
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    studyStatus = "";
                    updatePublishUi();
                    Toast.makeText(
                            ResearcherCreateQuestionnaireActivity.this,
                            error,
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void configureEditorMode() {
        if (!questionnairePublished) {
            issueButton.setVisibility(View.GONE);
            return;
        }

        titleField.setEnabled(false);
        findViewById(R.id.labelPrompt).setVisibility(View.GONE);
        promptField.setVisibility(View.GONE);
        findViewById(R.id.labelType).setVisibility(View.GONE);
        questionTypeGroup.setVisibility(View.GONE);
        choiceOptionsSection.setVisibility(View.GONE);
        addQuestionButton.setVisibility(View.GONE);
        saveButton.setVisibility(View.GONE);
        publishButton.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
        boolean canOfferIssuance = "published".equals(questionnaireStatus) && !studyId.isEmpty();
        issueButton.setVisibility(canOfferIssuance ? View.VISIBLE : View.GONE);
        publishHint.setVisibility(canOfferIssuance ? View.VISIBLE : View.GONE);
    }

    private void applyCopiedDefinition(@NonNull String definitionString) {
        try {
            currentDefinition = new JSONObject(definitionString);
            questionsArray = currentDefinition.optJSONArray("questions");
            if (questionsArray == null) questionsArray = new JSONArray();
            currentDefinition.put("questions", questionsArray);

            titleField.setText(currentDefinition.optString("title", ""));
            promptField.setText("");
            choiceOptionsContainer.removeAllViews();
            questionTypeGroup.check(R.id.radioText);
            choiceOptionsSection.setVisibility(View.GONE);
            hasUnsavedChanges = true;
            renderQuestionsList();
            updateEditorUi();
        } catch (Exception error) {
            Toast.makeText(this, R.string.copy_questionnaire_error, Toast.LENGTH_LONG).show();
        }
    }

    private void renderQuestionsList() {
        questionsListContainer.removeAllViews();
        questionsEmptyText.setVisibility(
                questionsArray.length() == 0 ? View.VISIBLE : View.GONE
        );
        for (int index = 0; index < questionsArray.length(); index++) {
            JSONObject question = questionsArray.optJSONObject(index);
            if (question != null) {
                addQuestionEntry(index, question);
            }
        }
    }

    private void addQuestionEntry(int index, @NonNull JSONObject question) {
        LinearLayout entry = new LinearLayout(this);
        entry.setOrientation(LinearLayout.VERTICAL);
        entry.setPadding(0, 0, 0, getResources().getDimensionPixelSize(R.dimen.space_12));

        Button header = new Button(this);
        header.setAllCaps(false);
        header.setTextSize(16);
        header.setText(questionEntryTitle(index, question));
        entry.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(
                getResources().getDimensionPixelSize(R.dimen.space_12),
                getResources().getDimensionPixelSize(R.dimen.space_12),
                getResources().getDimensionPixelSize(R.dimen.space_12),
                getResources().getDimensionPixelSize(R.dimen.space_12)
        );
        details.setVisibility(View.GONE);

        TextView type = new TextView(this);
        type.setText(questionTypeDescription(question.optString("type", "text")));
        type.setTypeface(type.getTypeface(), android.graphics.Typeface.BOLD);
        details.addView(type);

        if (!questionnairePublished) {
            TextView promptLabel = new TextView(this);
            promptLabel.setText("Question");
            promptLabel.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_12), 0, 0);
            details.addView(promptLabel);

            EditText promptEditor = new EditText(this);
            promptEditor.setText(question.optString("prompt", ""));
            promptEditor.setSingleLine(false);
            promptEditor.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    try {
                        question.put("prompt", s.toString());
                        header.setText(questionEntryTitle(index, question));
                        markDefinitionChanged();
                    } catch (Exception ignored) {
                        // JSONObject accepts strings; retained for defensive compatibility.
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            details.addView(promptEditor);
        }

        String questionType = question.optString("type", "text");
        if ("single_choice".equals(questionType) || "multiple_choice".equals(questionType)) {
            TextView choicesLabel = new TextView(this);
            choicesLabel.setText("Choices");
            choicesLabel.setTypeface(
                    choicesLabel.getTypeface(),
                    android.graphics.Typeface.BOLD
            );
            choicesLabel.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_12), 0, 0);
            details.addView(choicesLabel);
            LinearLayout existingChoices = new LinearLayout(this);
            existingChoices.setOrientation(LinearLayout.VERTICAL);
            if (!questionnairePublished) {
                Button addExistingChoice = new Button(this);
                addExistingChoice.setText("+");
                addExistingChoice.setTag("question_editor_control");
                addExistingChoice.setContentDescription("Add choice");
                addExistingChoice.setOnClickListener(view -> {
                    JSONArray currentOptions = question.optJSONArray("options");
                    if (currentOptions == null) {
                        currentOptions = new JSONArray();
                        try {
                            question.put("options", currentOptions);
                        } catch (Exception ignored) {
                            return;
                        }
                    }
                    currentOptions.put("");
                    addExistingChoiceEditor(
                            existingChoices,
                            currentOptions,
                            currentOptions.length() - 1
                    );
                    markDefinitionChanged();
                });
                details.addView(addExistingChoice);
            }
            details.addView(existingChoices);
            addExistingChoiceEditors(existingChoices, question);
        }

        header.setOnClickListener(view -> details.setVisibility(
                details.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
        ));
        entry.addView(details, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        questionsListContainer.addView(entry);
    }

    private void addExistingChoiceEditors(
            @NonNull LinearLayout container,
            @NonNull JSONObject question
    ) {
        JSONArray options = question.optJSONArray("options");
        if (options == null) return;
        for (int optionIndex = 0; optionIndex < options.length(); optionIndex++) {
            Object option = options.opt(optionIndex);
            String label = choiceLabel(option);
            if (questionnairePublished) {
                TextView choice = new TextView(this);
                choice.setText("• " + label);
                choice.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_12), 0, 0);
                container.addView(choice);
                continue;
            }
            addExistingChoiceEditor(container, options, optionIndex);
        }
    }

    private void addExistingChoiceEditor(
            @NonNull LinearLayout container,
            @NonNull JSONArray options,
            int optionIndex
    ) {
        EditText choiceEditor = new EditText(this);
        choiceEditor.setText(choiceLabel(options.opt(optionIndex)));
        choiceEditor.setSingleLine(true);
        choiceEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                try {
                    Object existing = options.opt(optionIndex);
                    if (existing instanceof JSONObject) {
                        ((JSONObject) existing).put("label", s.toString());
                    } else {
                        options.put(optionIndex, s.toString());
                    }
                    markDefinitionChanged();
                } catch (Exception ignored) {
                    // The source definition remains intact if an unexpected option is encountered.
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        container.addView(choiceEditor);
    }

    @NonNull
    private String questionEntryTitle(int index, @NonNull JSONObject question) {
        return (index + 1) + ". " + question.optString("prompt", "");
    }

    @NonNull
    private String questionTypeDescription(@NonNull String type) {
        switch (type) {
            case "rating":
                return "Type: Scalar (1-5)";
            case "single_choice":
                return "Type: Single Choice";
            case "multiple_choice":
                return "Type: Multiple Choice";
            default:
                return "Type: Text Response";
        }
    }

    @NonNull
    private String choiceLabel(Object option) {
        if (option instanceof JSONObject) {
            return ((JSONObject) option).optString("label", "");
        }
        return option == null ? "" : String.valueOf(option);
    }

    private void markDefinitionChanged() {
        if (initializing || questionnairePublished) return;
        hasUnsavedChanges = true;
        updatePublishUi();
    }

    private boolean addQuestionToList() {
        String prompt = promptField.getText().toString().trim();
        if (prompt.isEmpty()) {
            Toast.makeText(this, "Please enter a question prompt", Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            JSONObject question = new JSONObject();
            int questionIndex = questionsArray.length() + 1;
            question.put("id", "q" + questionIndex);
            question.put("prompt", prompt);
            question.put("required", true);
            int selectedType = questionTypeGroup.getCheckedRadioButtonId();
            if (selectedType == R.id.radioRating) {
                question.put("type", "rating");
                question.put("scale_ref", "scale_1_5");
            } else if (selectedType == R.id.radioSingleChoice
                    || selectedType == R.id.radioMultipleChoice) {
                JSONArray options = collectChoiceOptions();
                if (options.length() == 0) {
                    Toast.makeText(this, "Please add at least one choice", Toast.LENGTH_SHORT).show();
                    return false;
                }
                question.put(
                        "type",
                        selectedType == R.id.radioSingleChoice
                                ? "single_choice"
                                : "multiple_choice"
                );
                question.put("options", options);
            } else {
                question.put("type", "text");
            }

            questionsArray.put(question);
            hasUnsavedChanges = true;
            questionsEmptyText.setVisibility(View.GONE);
            addQuestionEntry(questionsArray.length() - 1, question);
            promptField.setText("");
            choiceOptionsContainer.removeAllViews();
            updatePublishUi();
            Toast.makeText(this, "Question added to list!", Toast.LENGTH_SHORT).show();
            return true;
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        } catch (Exception error) {
            Toast.makeText(this, "Failed to add question", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private boolean isChoiceTypeSelected() {
        int selectedType = questionTypeGroup.getCheckedRadioButtonId();
        return selectedType == R.id.radioSingleChoice
                || selectedType == R.id.radioMultipleChoice;
    }

    private void addChoiceRow() {
        addChoiceRow("", true);
    }

    private void addChoiceRow(@NonNull String value, boolean markDirty) {
        EditText choiceField = new EditText(this);
        choiceField.setHint("Choice " + (choiceOptionsContainer.getChildCount() + 1));
        choiceField.setSingleLine(true);
        choiceField.setMinHeight(getResources().getDimensionPixelSize(R.dimen.field_height));
        choiceField.setText(value);
        choiceField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                hasUnsavedChanges = true;
                updatePublishUi();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        choiceOptionsContainer.addView(
                choiceField,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );
        if (markDirty) {
            hasUnsavedChanges = true;
            updatePublishUi();
        }
    }

    @NonNull
    private JSONArray pendingChoiceOptions() {
        JSONArray options = new JSONArray();
        for (int index = 0; index < choiceOptionsContainer.getChildCount(); index++) {
            View child = choiceOptionsContainer.getChildAt(index);
            if (child instanceof EditText) {
                options.put(((EditText) child).getText().toString());
            }
        }
        return options;
    }

    @NonNull
    private JSONArray collectChoiceOptions() {
        JSONArray options = new JSONArray();
        Set<String> uniqueOptions = new HashSet<>();
        for (int index = 0; index < choiceOptionsContainer.getChildCount(); index++) {
            View child = choiceOptionsContainer.getChildAt(index);
            if (!(child instanceof EditText)) continue;
            String option = ((EditText) child).getText().toString().trim();
            if (option.isEmpty()) {
                throw new IllegalArgumentException("Please enter text for every choice");
            }
            if (!uniqueOptions.add(option)) {
                throw new IllegalArgumentException("Each choice must be unique");
            }
            options.put(option);
        }
        return options;
    }

    private JSONObject buildDefinition() throws Exception {
        String title = titleField.getText().toString().trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("Please enter a questionnaire title");
        }
        boolean hasPendingQuestion = !promptField.getText().toString().trim().isEmpty()
                || choiceOptionsContainer.getChildCount() > 0;
        if (hasPendingQuestion && !addQuestionToList()) {
            throw new IllegalArgumentException("Please finish the current question");
        }
        if (questionsArray.length() == 0) {
            throw new IllegalArgumentException("Please add at least one question");
        }
        validateQuestionEdits();

        currentDefinition.put("title", title);
        JSONObject scales = currentDefinition.optJSONObject("scales");
        if (scales == null) scales = new JSONObject();
        JSONObject scale15 = new JSONObject();
        scale15.put("min", 1);
        scale15.put("max", 5);
        scales.put("scale_1_5", scale15);
        currentDefinition.put("scales", scales);
        currentDefinition.put("questions", questionsArray);
        return currentDefinition;
    }

    private void validateQuestionEdits() {
        for (int index = 0; index < questionsArray.length(); index++) {
            JSONObject question = questionsArray.optJSONObject(index);
            if (question == null || question.optString("prompt", "").trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Question " + (index + 1) + " must have question text"
                );
            }
            String type = question.optString("type", "text");
            if (!"single_choice".equals(type) && !"multiple_choice".equals(type)) {
                continue;
            }
            JSONArray options = question.optJSONArray("options");
            if (options == null || options.length() == 0) {
                throw new IllegalArgumentException(
                        "Question " + (index + 1) + " must have at least one choice"
                );
            }
            Set<String> uniqueOptions = new HashSet<>();
            for (int optionIndex = 0; optionIndex < options.length(); optionIndex++) {
                String label = choiceLabel(options.opt(optionIndex)).trim();
                if (label.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Question " + (index + 1) + " has an empty choice"
                    );
                }
                if (!uniqueOptions.add(label)) {
                    throw new IllegalArgumentException(
                            "Question " + (index + 1) + " has duplicate choices"
                    );
                }
            }
        }
    }

    private void saveQuestionnaire() {
        final JSONObject definition;
        try {
            definition = buildDefinition();
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        } catch (Exception error) {
            Toast.makeText(this, "Failed to build questionnaire definition", Toast.LENGTH_SHORT).show();
            return;
        }

        requestInProgress = true;
        updateEditorUi();
        QuestionnaireApiClient.Listener listener = new QuestionnaireApiClient.Listener() {
            @Override
            public void onSuccess(@NonNull String savedQuestionnaireId) {
                runOnUiThread(() -> {
                    boolean wasNew = questionnaireId.isEmpty();
                    questionnaireId = savedQuestionnaireId;
                    hasUnsavedChanges = false;
                    requestInProgress = false;
                    saveButton.setText("Save Questionnaire");
                    updateEditorUi();
                    Toast.makeText(
                            ResearcherCreateQuestionnaireActivity.this,
                            wasNew ? "Questionnaire created as a draft." : "Questionnaire saved.",
                            Toast.LENGTH_SHORT
                    ).show();
                });
            }

            @Override
            public void onFailure(@NonNull String error) {
                runOnUiThread(() -> {
                    requestInProgress = false;
                    updateEditorUi();
                    Toast.makeText(ResearcherCreateQuestionnaireActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        };

        if (questionnaireId.isEmpty()) {
            QuestionnaireApiClient.createQuestionnaire(this, httpClient, definition, listener);
        } else {
            QuestionnaireApiClient.editQuestionnaire(
                    this,
                    httpClient,
                    questionnaireId,
                    definition,
                    listener
            );
        }
    }

    private void publishQuestionnaire() {
        if (!QuestionnairePublishing.canPublish(
                studyStatus,
                questionnaireId,
                questionnairePublished,
                hasUnsavedChanges
        )) {
            return;
        }

        requestInProgress = true;
        updateEditorUi();
        QuestionnaireApiClient.publishAndIssueStudyQuestionnaire(
                this,
                httpClient,
                studyId,
                questionnaireId,
                new QuestionnaireApiClient.Listener() {
                    @Override
                    public void onSuccess(@NonNull String id) {
                        runOnUiThread(() -> {
                            questionnairePublished = true;
                            questionnaireStatus = "published";
                            requestInProgress = false;
                            updateEditorUi();
                            Toast.makeText(
                                    ResearcherCreateQuestionnaireActivity.this,
                                    "Questionnaire published and issued to the study.",
                                    Toast.LENGTH_SHORT
                            ).show();
                            finish();
                        });
                    }

                    @Override
                    public void onFailure(@NonNull String error) {
                        runOnUiThread(() -> {
                            requestInProgress = false;
                            updateEditorUi();
                            Toast.makeText(
                                    ResearcherCreateQuestionnaireActivity.this,
                                    error,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                }
        );
    }

    private void issueQuestionnaire() {
        if (!QuestionnairePublishing.canIssue(
                studyStatus,
                studyId,
                questionnaireId,
                questionnaireStatus
        )) {
            return;
        }

        requestInProgress = true;
        updateEditorUi();
        QuestionnaireApiClient.issueQuestionnaireToStudy(
                this,
                httpClient,
                studyId,
                questionnaireId,
                new QuestionnaireApiClient.Listener() {
                    @Override
                    public void onSuccess(@NonNull String id) {
                        runOnUiThread(() -> {
                            requestInProgress = false;
                            Toast.makeText(
                                    ResearcherCreateQuestionnaireActivity.this,
                                    R.string.questionnaire_issued,
                                    Toast.LENGTH_SHORT
                            ).show();
                            finish();
                        });
                    }

                    @Override
                    public void onFailure(@NonNull String error) {
                        runOnUiThread(() -> {
                            requestInProgress = false;
                            updateEditorUi();
                            Toast.makeText(
                                    ResearcherCreateQuestionnaireActivity.this,
                                    error,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                }
        );
    }

    private void updateEditorUi() {
        saveButton.setEnabled(!requestInProgress && !questionnairePublished);
        addQuestionButton.setEnabled(!requestInProgress && !questionnairePublished);
        addChoiceButton.setEnabled(!requestInProgress && !questionnairePublished);
        copyButton.setEnabled(!requestInProgress && !questionnairePublished);
        titleField.setEnabled(!requestInProgress && !questionnairePublished);
        promptField.setEnabled(!requestInProgress && !questionnairePublished);
        questionTypeGroup.setEnabled(!requestInProgress && !questionnairePublished);
        for (int index = 0; index < choiceOptionsContainer.getChildCount(); index++) {
            choiceOptionsContainer.getChildAt(index).setEnabled(
                    !requestInProgress && !questionnairePublished
            );
        }
        setQuestionEditorsEnabled(
                questionsListContainer,
                !requestInProgress && !questionnairePublished
        );
        updatePublishUi();
    }

    private void setQuestionEditorsEnabled(@NonNull ViewGroup parent, boolean enabled) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (child instanceof EditText) {
                child.setEnabled(enabled);
            } else if (child instanceof Button
                    && "question_editor_control".equals(child.getTag())) {
                child.setEnabled(enabled);
            } else if (child instanceof ViewGroup) {
                setQuestionEditorsEnabled((ViewGroup) child, enabled);
            }
        }
    }

    private void updatePublishUi() {
        if (questionnairePublished) {
            updateIssueUi();
            return;
        }

        boolean canPublish = !requestInProgress && QuestionnairePublishing.canPublish(
                studyStatus,
                questionnaireId,
                questionnairePublished,
                hasUnsavedChanges
        );
        publishButton.setEnabled(canPublish);
        publishButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this,
                canPublish ? R.color.sky_blue_dark : R.color.disabled_grey
        )));

        if (!"active".equals(studyStatus)) {
            publishHint.setText("Publishing is available once the study is active.");
        } else if (questionnaireId.isEmpty()) {
            publishHint.setText("Create the questionnaire before publishing it.");
        } else if (hasUnsavedChanges) {
            publishHint.setText("Save your changes before publishing.");
        } else {
            publishHint.setText("Publishing will issue this questionnaire to the active study.");
        }
    }

    private void updateIssueUi() {
        boolean visible = "published".equals(questionnaireStatus) && !studyId.isEmpty();
        issueButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        publishHint.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;

        boolean canIssue = !requestInProgress && QuestionnairePublishing.canIssue(
                studyStatus,
                studyId,
                questionnaireId,
                questionnaireStatus
        );
        issueButton.setEnabled(canIssue);
        issueButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this,
                canIssue ? R.color.sky_blue_dark : R.color.disabled_grey
        )));
        publishHint.setText(
                "active".equals(studyStatus)
                        ? R.string.questionnaire_issue_ready_hint
                        : R.string.questionnaire_issue_active_hint
        );
    }
}

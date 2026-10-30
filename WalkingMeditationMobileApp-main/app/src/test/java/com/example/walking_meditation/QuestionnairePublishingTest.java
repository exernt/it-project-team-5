package com.example.walking_meditation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.walking_meditation.utils.QuestionnairePublishing;

import org.junit.Test;

public class QuestionnairePublishingTest {
    @Test
    public void activeStudyCanPublishSavedDraft() {
        assertTrue(QuestionnairePublishing.canPublish("active", "questionnaire-id", false, false));
    }

    @Test
    public void recruitingStudyCannotPublish() {
        assertFalse(QuestionnairePublishing.canPublish("recruiting", "questionnaire-id", false, false));
    }

    @Test
    public void unsavedOrMissingDraftCannotPublish() {
        assertFalse(QuestionnairePublishing.canPublish("active", "", false, false));
        assertFalse(QuestionnairePublishing.canPublish("active", "questionnaire-id", false, true));
        assertFalse(QuestionnairePublishing.canPublish("active", "questionnaire-id", true, false));
    }

    @Test
    public void activeStudyCanIssuePublishedQuestionnaire() {
        assertTrue(QuestionnairePublishing.canIssue(
                "active",
                "study-id",
                "questionnaire-id",
                "published"
        ));
    }

    @Test
    public void questionnaireCannotBeIssuedWithoutActiveStudyContext() {
        assertFalse(QuestionnairePublishing.canIssue(
                "recruiting",
                "study-id",
                "questionnaire-id",
                "published"
        ));
        assertFalse(QuestionnairePublishing.canIssue(
                "active",
                "",
                "questionnaire-id",
                "published"
        ));
    }

    @Test
    public void draftOrArchivedQuestionnaireCannotBeIssued() {
        assertFalse(QuestionnairePublishing.canIssue(
                "active",
                "study-id",
                "questionnaire-id",
                "draft"
        ));
        assertFalse(QuestionnairePublishing.canIssue(
                "active",
                "study-id",
                "questionnaire-id",
                "archived"
        ));
    }
}

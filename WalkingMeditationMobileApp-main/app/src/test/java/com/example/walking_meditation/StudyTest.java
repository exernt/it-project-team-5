package com.example.walking_meditation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.walking_meditation.models.Study;

import org.junit.Test;

public class StudyTest {
    @Test
    public void recruitingAndActiveStudiesAreOpen() {
        assertTrue(new Study("1", "Recruiting", 7, "Description", "recruiting").isOpen());
        assertTrue(new Study("2", "Active", 14, "Description", "active").isOpen());
    }

    @Test
    public void draftAndClosedStudiesAreNotOpen() {
        assertFalse(new Study("3", "Draft", 7, "Description", "draft").isOpen());
        assertFalse(new Study("4", "Closed", 14, "Description", "closed").isOpen());
    }

    @Test
    public void exposesGroupScriptGenerationParameters() {
        Study study = new Study(
                "5",
                "Parameterized",
                14,
                "Description",
                "draft",
                "Use short narration segments.",
                "Use a gratitude practice.",
                "male",
                "female"
        );

        assertEquals(
                "Use short narration segments.",
                study.getControlScriptGenerationParameters()
        );
        assertEquals(
                "Use a gratitude practice.",
                study.getExperimentalScriptGenerationParameters()
        );
        assertEquals("male", study.getControlTtsVoice());
        assertEquals("female", study.getExperimentalTtsVoice());
    }
}

package com.example.walking_meditation;

import static org.junit.Assert.assertFalse;
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
}

package com.example.walking_meditation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.example.walking_meditation.utils.StudyLifecycle;

import org.junit.Test;

public class StudyLifecycleTest {
    @Test
    public void draftAdvancesToPublish() {
        assertEquals("publish", StudyLifecycle.nextAction("draft"));
    }

    @Test
    public void recruitingAdvancesToActivate() {
        assertEquals("activate", StudyLifecycle.nextAction("recruiting"));
    }

    @Test
    public void activeAdvancesToClose() {
        assertEquals("close", StudyLifecycle.nextAction("active"));
    }

    @Test
    public void closedHasNoNextAction() {
        assertNull(StudyLifecycle.nextAction("closed"));
    }
}
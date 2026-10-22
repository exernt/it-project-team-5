package com.example.walking_meditation.models;

import java.util.ArrayList;
import java.util.List;

public class ScheduledNarration {
    public String id;
    public String text;
    public String audioFilePath;

    // Optional: null for narration without a route step
    public String routeStepId;

    public enum Anchor { ROUTE_START, STEP_START, LEG_START, LEG_END, ROUTE_END }

    // Anchors survive persistence; distances are resolved after GPS geometry alignment.
    public Anchor anchor;
    public String routeLegId;
    public boolean allowTimeFallback;
    public double routeStartM;
    public long plannedStartMs;

    public final List<SpeechSegment> chunks = new ArrayList<>();
}
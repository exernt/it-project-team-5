package com.example.walking_meditation.models;

import java.util.ArrayList;
import java.util.List;

public class RouteLeg {
    public String id;
    public String polyline;
    public int durationSeconds;
    public int distanceMeters;
    public double routeStartDistanceM;
    public double routeEndDistanceM;
    public List<RouteStep> steps = new ArrayList<>();
}

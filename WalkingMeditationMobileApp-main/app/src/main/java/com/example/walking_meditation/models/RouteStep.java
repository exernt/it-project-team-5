package com.example.walking_meditation.models;

public class RouteStep {
    public int index;
    public String instruction;
    public LatLon start;
    public LatLon end;
    public int duration_s;
    public int distance_m;
    public String polyline;

    // Calculated once when the selected route plan is created.
    public String id;
    public double routeStartDistanceM;
    public double routeEndDistanceM;
}

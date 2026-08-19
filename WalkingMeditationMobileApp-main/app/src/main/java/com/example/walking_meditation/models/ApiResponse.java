package com.example.walking_meditation.models;

import java.util.List;

public class ApiResponse {
    public LatLon origin;
    public LatLon destination;
    public Integer total_travel_time;
    public Baseline baseline;
    public List<ParkItem> parks;
}

package com.example.walking_meditation.models;

public class RouteOption {
    public enum Type { BASELINE, PARK }

    public Type type;
    public String title;

    public int durationSeconds;   // baseline.duration_s or park.detour_s
    public int addedSeconds;      // 0 for baseline, park.added_s for parks

    public String polyline;
    public String polylineFromPark;
    public String parkPlaceId;

    public static RouteOption fromBaseline(ApiResponse resp) {
        RouteOption opt = new RouteOption();
        opt.type = Type.BASELINE;
        opt.title = "Baseline route";
        opt.durationSeconds = resp.baseline.duration_s;
        opt.addedSeconds = 0;
        opt.polyline = resp.baseline.polyline;
        opt.parkPlaceId = null;
        return opt;
    }

    public static RouteOption fromPark(ParkItem p) {
        RouteOption opt = new RouteOption();
        opt.type = Type.PARK;
        opt.title = (p.park != null && p.park.name != null) ? p.park.name : "Park detour";
        opt.durationSeconds = p.detour_s;
        opt.addedSeconds = p.added_s;
        opt.polyline = p.polyline_to_park;
        opt.polylineFromPark = p.polyline_from_park;
        opt.parkPlaceId = (p.park != null) ? p.park.place_id : null;
        return opt;
    }
}

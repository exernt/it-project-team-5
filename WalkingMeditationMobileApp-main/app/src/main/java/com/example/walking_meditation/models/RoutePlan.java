package com.example.walking_meditation.models;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RoutePlan {
    public int schemaVersion = 1;
    public String routeId = UUID.randomUUID().toString();
    public long createdAtEpochMs = System.currentTimeMillis();
    public LatLon origin;
    public LatLon destination;
    @Nullable public LatLon park;
    public int plannedDurationSeconds;
    public int plannedDistanceMeters;
    public List<RouteLeg> legs = new ArrayList<>();

    @NonNull
    public static RoutePlan forParkRoute(
            @NonNull ApiResponse response,
            @NonNull ParkItem parkItem
    ) {
        RoutePlan plan = new RoutePlan();
        plan.origin = response.origin;
        plan.destination = response.destination;
        plan.park = new LatLon(parkItem.park.lat, parkItem.park.lon);
        plan.plannedDurationSeconds = parkItem.detour_s;

        RouteLeg toPark = leg(
                "source_to_park",
                parkItem.polyline_to_park,
                parkItem.to_park_s,
                parkItem.steps_to_park
        );
        RouteLeg fromPark = leg(
                "park_to_destination",
                parkItem.polyline_from_park,
                parkItem.park_to_destination_s,
                parkItem.steps_from_park
        );
        plan.legs.add(toPark);
        plan.legs.add(fromPark);
        plan.calculateDistances();
        return plan;
    }

    @NonNull
    public static RoutePlan forBaseline(@NonNull ApiResponse response) {
        RoutePlan plan = new RoutePlan();
        plan.origin = response.origin;
        plan.destination = response.destination;
        plan.plannedDurationSeconds = response.baseline.duration_s;
        plan.legs.add(leg(
                "source_to_destination",
                response.baseline.polyline,
                response.baseline.duration_s,
                response.baseline.steps
        ));
        plan.calculateDistances();
        return plan;
    }

    private static RouteLeg leg(
            String id,
            String polyline,
            int durationSeconds,
            @Nullable List<RouteStep> steps
    ) {
        RouteLeg leg = new RouteLeg();
        leg.id = id;
        leg.polyline = polyline;
        leg.durationSeconds = durationSeconds;
        if (steps != null) leg.steps.addAll(steps);
        return leg;
    }

    private void calculateDistances() {
        double routeDistance = 0;
        int fallbackIndex = 1;
        for (RouteLeg leg : legs) {
            leg.routeStartDistanceM = routeDistance;
            double legDistance = 0;
            for (RouteStep step : leg.steps) {
                step.id = leg.id + "_" + (step.index > 0 ? step.index : fallbackIndex);
                step.routeStartDistanceM = routeDistance + legDistance;
                legDistance += Math.max(0, step.distance_m);
                step.routeEndDistanceM = routeDistance + legDistance;
                fallbackIndex++;
            }
            leg.distanceMeters = (int) Math.round(legDistance);
            routeDistance += legDistance;
            leg.routeEndDistanceM = routeDistance;
        }
        plannedDistanceMeters = (int) Math.round(routeDistance);
    }

    public List<RouteStep> allSteps() {
        List<RouteStep> result = new ArrayList<>();
        for (RouteLeg leg : legs) result.addAll(leg.steps);
        return result;
    }
}

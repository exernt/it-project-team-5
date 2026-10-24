package com.example.walking_meditation.navigation;

import android.location.Location;

import androidx.annotation.NonNull;

import com.example.walking_meditation.models.LatLon;
import com.example.walking_meditation.models.RouteLeg;
import com.example.walking_meditation.models.RoutePlan;
import com.example.walking_meditation.models.RouteStep;
import com.example.walking_meditation.utils.PolylineDecoder;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Matches noisy device locations to one immutable selected walking route. */
public final class RouteProgressTracker {
    private static final double EARTH_METRES_PER_DEGREE = 111_320.0;
    private static final double BACKTRACK_ALLOWANCE_M = 35.0;
    private static final int FIXES_TO_LEAVE_ROUTE = 3;
    private static final int FIXES_TO_REJOIN = 2;

    public static final class Match {
        public final boolean offRoute;
        public final double distanceFromRouteM;
        public final double progressM;
        public final int segmentIndex;
        public final LatLng projectedPoint;
        public final LatLng rejoinPoint;

        private Match(
                boolean offRoute,
                double distanceFromRouteM,
                double progressM,
                int segmentIndex,
                LatLng projectedPoint,
                LatLng rejoinPoint
        ) {
            this.offRoute = offRoute;
            this.distanceFromRouteM = distanceFromRouteM;
            this.progressM = progressM;
            this.segmentIndex = segmentIndex;
            this.projectedPoint = projectedPoint;
            this.rejoinPoint = rejoinPoint;
        }
    }

    private static final class Projection {
        double distanceM;
        double alongM;
        int segmentIndex;
        LatLng point;
    }

    private final List<LatLng> routePoints = new ArrayList<>();
    private final double[] cumulativeDistanceM;
    private double confirmedProgressM;
    private boolean offRoute;
    private int outsideFixes;
    private int insideFixes;

    public RouteProgressTracker(@NonNull RoutePlan plan) {
        for (RouteLeg leg : plan.legs) {
            List<LatLon> decoded = PolylineDecoder.decode(leg.polyline);
            for (LatLon point : decoded) {
                LatLng next = new LatLng(point.lat, point.lon);
                if (routePoints.isEmpty() || !samePoint(routePoints.get(routePoints.size() - 1), next)) {
                    routePoints.add(next);
                }
            }
        }
        if (routePoints.size() < 2) {
            throw new IllegalArgumentException("Selected route has insufficient geometry");
        }

        cumulativeDistanceM = new double[routePoints.size()];
        for (int index = 1; index < routePoints.size(); index++) {
            cumulativeDistanceM[index] = cumulativeDistanceM[index - 1]
                    + distance(routePoints.get(index - 1), routePoints.get(index));
        }
        alignStepsToGeometry(plan);
        plan.plannedDistanceMeters = (int) Math.round(getTotalDistanceM());
    }

    @NonNull
    public Match update(@NonNull Location location) {
        Projection candidate = project(
                new LatLng(location.getLatitude(), location.getLongitude()),
                confirmedProgressM > 0 ? confirmedProgressM - BACKTRACK_ALLOWANCE_M : 0,
                true
        );
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : 20f;
        double enterCorridorM = Math.max(30.0, Math.min(60.0, accuracy * 1.5));
        double leaveCorridorM = Math.max(50.0, Math.min(90.0, accuracy * 2.0));

        if (offRoute) {
            if (candidate.distanceM <= enterCorridorM) {
                insideFixes++;
                if (insideFixes >= FIXES_TO_REJOIN) {
                    offRoute = false;
                    outsideFixes = 0;
                }
            } else {
                insideFixes = 0;
            }
        } else {
            if (candidate.distanceM > leaveCorridorM) {
                outsideFixes++;
                if (outsideFixes >= FIXES_TO_LEAVE_ROUTE) {
                    offRoute = true;
                    insideFixes = 0;
                }
            } else {
                outsideFixes = 0;
            }
        }

        boolean acceptedForProgress = !offRoute && candidate.distanceM <= leaveCorridorM;
        if (acceptedForProgress) {
            confirmedProgressM = Math.max(confirmedProgressM, candidate.alongM);
        }
        double rejoinDistance = Math.max(confirmedProgressM, candidate.alongM);
        LatLng rejoin = pointAtDistance(Math.min(getTotalDistanceM(), rejoinDistance));
        LatLng displayPoint = acceptedForProgress
                ? candidate.point : pointAtDistance(confirmedProgressM);
        int displaySegmentIndex = acceptedForProgress
                ? candidate.segmentIndex : segmentIndexAtDistance(confirmedProgressM);
        return new Match(
                offRoute,
                candidate.distanceM,
                confirmedProgressM,
                displaySegmentIndex,
                displayPoint,
                rejoin
        );
    }

    public double getTotalDistanceM() {
        return cumulativeDistanceM[cumulativeDistanceM.length - 1];
    }

    @NonNull
    public List<LatLng> getRoutePoints() {
        return Collections.unmodifiableList(routePoints);
    }

    @NonNull
    public List<LatLng> completedPoints(@NonNull Match match) {
        List<LatLng> points = new ArrayList<>();
        for (int index = 0; index <= match.segmentIndex; index++) {
            points.add(routePoints.get(index));
        }
        points.add(match.projectedPoint);
        return points;
    }

    @NonNull
    public List<LatLng> remainingPoints(@NonNull Match match) {
        List<LatLng> points = new ArrayList<>();
        points.add(match.projectedPoint);
        for (int index = match.segmentIndex + 1; index < routePoints.size(); index++) {
            points.add(routePoints.get(index));
        }
        return points;
    }

    private void alignStepsToGeometry(RoutePlan plan) {
        double previousEndM = 0;
        for (RouteStep step : plan.allSteps()) {
            step.routeStartDistanceM = previousEndM;
            if (step.end != null) {
                Projection end = project(
                        new LatLng(step.end.lat, step.end.lon),
                        Math.max(0, previousEndM - BACKTRACK_ALLOWANCE_M),
                        false
                );
                step.routeEndDistanceM = Math.max(previousEndM, end.alongM);
            } else {
                step.routeEndDistanceM = previousEndM + Math.max(0, step.distance_m);
            }
            previousEndM = step.routeEndDistanceM;
        }
    }

    private Projection project(
            LatLng location,
            double minimumAlongM,
            boolean penalizeForwardJumps
    ) {
        Projection best = null;
        for (int index = 0; index < routePoints.size() - 1; index++) {
            LatLng start = routePoints.get(index);
            LatLng end = routePoints.get(index + 1);
            double averageLatRadians = Math.toRadians((start.latitude + end.latitude) / 2.0);
            double lonScale = EARTH_METRES_PER_DEGREE * Math.cos(averageLatRadians);
            double vx = (end.longitude - start.longitude) * lonScale;
            double vy = (end.latitude - start.latitude) * EARTH_METRES_PER_DEGREE;
            double wx = (location.longitude - start.longitude) * lonScale;
            double wy = (location.latitude - start.latitude) * EARTH_METRES_PER_DEGREE;
            double lengthSquared = vx * vx + vy * vy;
            double fraction = lengthSquared == 0 ? 0 : (wx * vx + wy * vy) / lengthSquared;
            fraction = Math.max(0, Math.min(1, fraction));
            double px = fraction * vx;
            double py = fraction * vy;
            double distanceM = Math.hypot(wx - px, wy - py);
            double alongM = cumulativeDistanceM[index] + Math.sqrt(lengthSquared) * fraction;
            if (alongM + BACKTRACK_ALLOWANCE_M < minimumAlongM) continue;

            // A mild forward-jump penalty prevents route crossings from winning on GPS noise.
            double jumpPenalty = penalizeForwardJumps
                    ? Math.max(0, alongM - confirmedProgressM - 250.0) * 0.03
                    : 0;
            double score = distanceM + jumpPenalty;
            if (best == null || score < best.distanceM) {
                Projection candidate = new Projection();
                candidate.distanceM = score;
                candidate.alongM = alongM;
                candidate.segmentIndex = index;
                candidate.point = new LatLng(
                        start.latitude + (end.latitude - start.latitude) * fraction,
                        start.longitude + (end.longitude - start.longitude) * fraction
                );
                best = candidate;
            }
        }
        if (best == null) {
            Projection fallback = new Projection();
            fallback.distanceM = distance(location, routePoints.get(0));
            fallback.alongM = 0;
            fallback.segmentIndex = 0;
            fallback.point = routePoints.get(0);
            return fallback;
        }

        // Return the physical cross-track distance rather than the scoring penalty.
        best.distanceM = distance(location, best.point);
        return best;
    }

    private LatLng pointAtDistance(double targetM) {
        for (int index = 0; index < cumulativeDistanceM.length - 1; index++) {
            if (cumulativeDistanceM[index + 1] < targetM) continue;
            double length = cumulativeDistanceM[index + 1] - cumulativeDistanceM[index];
            double fraction = length <= 0 ? 0 : (targetM - cumulativeDistanceM[index]) / length;
            LatLng start = routePoints.get(index);
            LatLng end = routePoints.get(index + 1);
            return new LatLng(
                    start.latitude + (end.latitude - start.latitude) * fraction,
                    start.longitude + (end.longitude - start.longitude) * fraction
            );
        }
        return routePoints.get(routePoints.size() - 1);
    }

    private int segmentIndexAtDistance(double targetM) {
        for (int index = 0; index < cumulativeDistanceM.length - 1; index++) {
            if (cumulativeDistanceM[index + 1] >= targetM) return index;
        }
        return routePoints.size() - 2;
    }

    private static double distance(LatLng first, LatLng second) {
        float[] result = new float[1];
        Location.distanceBetween(
                first.latitude,
                first.longitude,
                second.latitude,
                second.longitude,
                result
        );
        return result[0];
    }

    private static boolean samePoint(LatLng first, LatLng second) {
        return Math.abs(first.latitude - second.latitude) < 1e-7
                && Math.abs(first.longitude - second.longitude) < 1e-7;
    }
}

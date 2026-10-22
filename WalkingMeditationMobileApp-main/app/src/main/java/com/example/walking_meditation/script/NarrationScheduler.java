package com.example.walking_meditation.script;

import com.example.walking_meditation.models.*;
import java.io.IOException;
import java.util.*;

/** Resolves persistent anchors against the current, geometry-aligned route. */
public final class NarrationScheduler {
    private static final double STEP_LEAD_M = 25;
    private static final long FALLBACK_DELAY_MS = 60_000;
    private NarrationScheduler() {}

    public static void resolve(RoutePlan route, List<ScheduledNarration> narrations)
            throws IOException {
        Map<String, Double> distances = new HashMap<>();
        Map<String, Long> times = new HashMap<>();
        long elapsed = 0;
        double end = 0;
        if (route == null || route.legs == null || route.legs.isEmpty()) {
            throw new IOException("Cannot schedule an empty route");
        }
        for (RouteLeg leg : route.legs) {
            if (leg.steps == null || leg.steps.isEmpty()) {
                throw new IOException("Cannot anchor narration to an empty route leg");
            }
            distances.put("start:" + leg.id, leg.steps.get(0).routeStartDistanceM);
            times.put("start:" + leg.id, elapsed);
            for (RouteStep step : leg.steps) {
                distances.put("step:" + step.id, step.routeStartDistanceM);
                times.put("step:" + step.id, elapsed);
                elapsed += Math.max(0, step.duration_s) * 1000L;
                end = step.routeEndDistanceM;
            }
            distances.put("end:" + leg.id, end);
            times.put("end:" + leg.id, elapsed);
        }
        for (ScheduledNarration narration : narrations) {
            if (narration.anchor == null) throw new IOException("Missing narration anchor: " + narration.id);
            String key;
            switch (narration.anchor) {
                case ROUTE_START:
                    narration.routeStartM = 0;
                    narration.plannedStartMs = 0;
                    narration.allowTimeFallback = false;
                    continue;
                case ROUTE_END:
                    narration.routeStartM = end;
                    narration.plannedStartMs = elapsed;
                    narration.allowTimeFallback = false;
                    continue;
                case STEP_START: key = "step:" + narration.routeStepId; break;
                case LEG_START: key = "start:" + narration.routeLegId; break;
                case LEG_END: key = "end:" + narration.routeLegId; break;
                default: throw new IOException("Unsupported narration anchor");
            }
            if (!distances.containsKey(key)) throw new IOException("Unknown narration anchor: " + key);
            narration.routeStartM = distances.get(key);
            narration.plannedStartMs = times.get(key);
            narration.allowTimeFallback = narration.anchor == ScheduledNarration.Anchor.STEP_START;
        }
    }

    /** Caller considers only the next block and waits for previous audio completion. */
    public static boolean isEligible(ScheduledNarration narration, double progressM,
                                     long activeWalkingMs, boolean offRoute) {
        if (offRoute || narration.anchor == null) return false;
        double lead = narration.anchor == ScheduledNarration.Anchor.STEP_START ? STEP_LEAD_M : 0;
        return progressM + lead >= narration.routeStartM
                || (narration.allowTimeFallback
                    && activeWalkingMs >= narration.plannedStartMs + FALLBACK_DELAY_MS);
    }
}
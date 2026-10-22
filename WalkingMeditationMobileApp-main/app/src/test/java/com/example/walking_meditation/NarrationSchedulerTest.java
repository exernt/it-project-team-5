package com.example.walking_meditation;

import com.example.walking_meditation.models.*;
import com.example.walking_meditation.script.NarrationScheduler;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class NarrationSchedulerTest {
    private RoutePlan route() {
        RoutePlan plan = new RoutePlan();
        for (int legIndex = 0; legIndex < 2; legIndex++) {
            RouteLeg leg = new RouteLeg(); leg.id = "leg" + legIndex;
            for (int i = 0; i < 2; i++) {
                RouteStep step = new RouteStep(); step.id = leg.id + "_" + i;
                step.duration_s = 10;
                step.routeStartDistanceM = (legIndex * 2 + i) * 100;
                step.routeEndDistanceM = step.routeStartDistanceM + 100;
                leg.steps.add(step);
            }
            // Deliberately stale leg totals: use the geometry-aligned steps.
            leg.routeEndDistanceM = 999;
            plan.legs.add(leg);
        }
        return plan;
    }

    private ScheduledNarration block(ScheduledNarration.Anchor anchor, String leg, String step) {
        ScheduledNarration n = new ScheduledNarration();
        n.id = UUID.randomUUID().toString(); n.anchor = anchor;
        n.routeLegId = leg; n.routeStepId = step;
        return n;
    }

    @Test public void resolvesAllAnchorsAndCountsUnnarratedSteps() throws Exception {
        ScheduledNarration opening = block(ScheduledNarration.Anchor.ROUTE_START, null, null);
        ScheduledNarration step = block(ScheduledNarration.Anchor.STEP_START, null, "leg1_1");
        ScheduledNarration park = block(ScheduledNarration.Anchor.LEG_END, "leg0", null);
        ScheduledNarration leaving = block(ScheduledNarration.Anchor.LEG_START, "leg1", null);
        ScheduledNarration closing = block(ScheduledNarration.Anchor.ROUTE_END, null, null);
        NarrationScheduler.resolve(route(), Arrays.asList(opening, park, leaving, step, closing));
        assertEquals(0, opening.routeStartM, 0);
        assertEquals(200, park.routeStartM, 0);
        assertEquals(20_000, park.plannedStartMs);
        assertEquals(200, leaving.routeStartM, 0);
        assertEquals(300, step.routeStartM, 0);
        assertEquals(30_000, step.plannedStartMs);
        assertEquals(400, closing.routeStartM, 0);
        assertEquals(40_000, closing.plannedStartMs);
        assertFalse(park.allowTimeFallback);
        assertTrue(step.allowTimeFallback);
    }

    @Test public void stepLeadAndDelayedFallbackRespectOffRoute() throws Exception {
        ScheduledNarration step = block(ScheduledNarration.Anchor.STEP_START, null, "leg1_1");
        NarrationScheduler.resolve(route(), Collections.singletonList(step));
        assertFalse(NarrationScheduler.isEligible(step, 274, 0, false));
        assertTrue(NarrationScheduler.isEligible(step, 275, 0, false));
        assertFalse(NarrationScheduler.isEligible(step, 0, 89_999, false));
        assertTrue(NarrationScheduler.isEligible(step, 0, 90_000, false));
        assertFalse(NarrationScheduler.isEligible(step, 400, 100_000, true));
    }

    @Test public void arrivalBlocksNeverStartThroughStepLeadOrTimer() throws Exception {
        ScheduledNarration park = block(ScheduledNarration.Anchor.LEG_END, "leg0", null);
        NarrationScheduler.resolve(route(), Collections.singletonList(park));
        assertFalse(NarrationScheduler.isEligible(park, 199, Long.MAX_VALUE, false));
        assertTrue(NarrationScheduler.isEligible(park, 200, 0, false));
    }

    @Test public void reResolvingUsesUpdatedGeometryAndIndependentStepReference() throws Exception {
        RoutePlan route = route();
        ScheduledNarration n = block(ScheduledNarration.Anchor.STEP_START, null, "leg1_1");
        NarrationScheduler.resolve(route, Collections.singletonList(n));
        route.legs.get(1).steps.get(1).routeStartDistanceM = 320;
        NarrationScheduler.resolve(route, Collections.singletonList(n));
        assertEquals(320, n.routeStartM, 0);
        assertEquals(30_000, n.plannedStartMs);
    }

    @Test public void rejectsUnknownOrMissingAnchors() {
        for (ScheduledNarration n : Arrays.asList(block(null, null, null),
                block(ScheduledNarration.Anchor.STEP_START, null, "missing"),
                block(ScheduledNarration.Anchor.LEG_END, "missing", null))) {
            assertThrows(IOException.class,
                    () -> NarrationScheduler.resolve(route(), Collections.singletonList(n)));
        }
    }
}
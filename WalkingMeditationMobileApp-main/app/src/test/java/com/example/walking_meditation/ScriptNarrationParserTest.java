package com.example.walking_meditation;

import com.example.walking_meditation.models.*;
import com.example.walking_meditation.script.ScriptNarrationParser;
import com.google.gson.Gson;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class ScriptNarrationParserTest {
    @Test public void preservesPauseMarkersForPayloadImplementations() throws Exception {
        JSONObject response = sample();
        RoutePlan plan = route(response);
        String original = "Notice & breathe. (pause) Keep walking. (PAUSE)";
        response.getJSONObject("script").getJSONArray("sections").getJSONObject(0)
                .getJSONObject("opening_narration").put("text", original);
        ScheduledNarration narration = ScriptNarrationParser.parse(response.toString(), plan).get(0);
        assertEquals(original, narration.text);
        assertEquals(ScheduledNarration.Anchor.ROUTE_START, narration.anchor);
    }

    private JSONObject sample() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/example_script_n1.json")) {
            assertNotNull(stream);
            return new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // The repository has no paired saved RoutePlan. Build matching physical
    // steps from sample metadata; separate tests deliberately corrupt mapping.
    private RoutePlan route(JSONObject response) throws Exception {
        RoutePlan plan = new RoutePlan();
        Map<String, RouteLeg> legs = new LinkedHashMap<>();
        JSONArray sections = response.getJSONObject("script").getJSONArray("sections");
        for (int s = 0; s < sections.length(); s++) {
            JSONArray segments = sections.getJSONObject(s).optJSONArray("route_segments");
            if (segments == null) continue;
            for (int i = 0; i < segments.length(); i++) {
                JSONObject segment = segments.getJSONObject(i);
                String phase = segment.getString("route_phase");
                RouteLeg leg = legs.computeIfAbsent(phase, key -> {
                    RouteLeg value = new RouteLeg(); value.id = key; return value;
                });
                RouteStep step = new Gson().fromJson(segment.toString(), RouteStep.class);
                step.routeStartDistanceM = leg.steps.size() * 100;
                step.routeEndDistanceM = step.routeStartDistanceM + 100;
                leg.steps.add(step);
            }
        }
        plan.legs.addAll(legs.values());
        return plan;
    }

    private JSONObject firstSegment(JSONObject response) throws Exception {
        return response.getJSONObject("script").getJSONArray("sections")
                .getJSONObject(0).getJSONArray("route_segments").getJSONObject(0);
    }

    @Test public void samplePreservesOrderReferencesAndRoute() throws Exception {
        JSONObject response = sample();
        RoutePlan plan = route(response);
        String before = new Gson().toJson(plan);
        List<ScheduledNarration> narrations = ScriptNarrationParser.parse(response.toString(), plan);
        assertEquals(Arrays.asList("focused_opening", "source_to_park_1", "source_to_park_2",
                "source_to_park_3", "source_to_park_4", "focused_closing", "compassion",
                "closing_opening", "park_to_destination_1", "park_to_destination_2",
                "park_to_destination_3", "park_to_destination_4", "closing_closing"),
                narrations.stream().map(n -> n.id).collect(java.util.stream.Collectors.toList()));
        assertNull(narrations.get(0).routeStepId);
        assertEquals("source_to_park_1", narrations.get(1).routeStepId);
        assertNull(narrations.get(6).routeStepId);
        assertEquals(ScheduledNarration.Anchor.ROUTE_START, narrations.get(0).anchor);
        assertEquals(ScheduledNarration.Anchor.STEP_START, narrations.get(1).anchor);
        assertEquals(ScheduledNarration.Anchor.LEG_END, narrations.get(5).anchor);
        assertEquals(ScheduledNarration.Anchor.LEG_END, narrations.get(6).anchor);
        assertEquals("source_to_park", narrations.get(6).routeLegId);
        assertEquals(ScheduledNarration.Anchor.LEG_START, narrations.get(7).anchor);
        assertEquals("park_to_destination", narrations.get(7).routeLegId);
        assertEquals(ScheduledNarration.Anchor.ROUTE_END, narrations.get(12).anchor);
        assertNull(narrations.get(1).audioFilePath);
        assertEquals(before, new Gson().toJson(plan));
    }

    @Test public void rejectsUnknownStepWithoutChangingRoute() throws Exception {
        JSONObject response = sample(); RoutePlan plan = route(response);
        String before = new Gson().toJson(plan);
        firstSegment(response).put("id", "segment_01");
        IOException error = assertThrows(IOException.class,
                () -> ScriptNarrationParser.parse(response.toString(), plan));
        assertTrue(error.getMessage().contains("Unknown route step"));
        assertEquals(before, new Gson().toJson(plan));
    }

    @Test public void rejectsWrongPhaseIndexAndGeometry() throws Exception {
        for (String field : Arrays.asList("route_phase", "index", "start")) {
            JSONObject response = sample(); RoutePlan plan = route(response);
            Object bad = field.equals("index") ? 99 : field.equals("start")
                    ? new JSONObject().put("lat", 0).put("lon", 0) : "park_to_destination";
            firstSegment(response).put(field, bad);
            assertThrows(IOException.class, () -> ScriptNarrationParser.parse(response.toString(), plan));
        }
    }

    @Test public void rejectsDuplicateNarrationIds() throws Exception {
        JSONObject response = sample(); RoutePlan plan = route(response);
        response.getJSONObject("script").getJSONArray("sections").getJSONObject(0)
                .getJSONObject("opening_narration").put("id", "source_to_park_1");
        assertThrows(IOException.class, () -> ScriptNarrationParser.parse(response.toString(), plan));
    }

    @Test public void skipsBlankTextAndAllowsAbsentOptionalFields() throws Exception {
        JSONObject response = sample(); RoutePlan plan = route(response);
        JSONObject section = response.getJSONObject("script").getJSONArray("sections").getJSONObject(0);
        section.remove("opening_narration");
        firstSegment(response).put("narration", "   ");
        List<ScheduledNarration> result = ScriptNarrationParser.parse(response.toString(), plan);
        assertEquals(11, result.size());
        assertEquals("source_to_park_2", result.get(0).id);
    }

    @Test public void rejectsLegacyMalformedEmptyAndWrongTypedScripts() throws Exception {
        RoutePlan plan = route(sample());
        for (String text : Arrays.asList("{\"script\":\"<meditation/>\"}", "not json", "{}",
                "{\"script\":{\"sections\":[]}}",
                "{\"script\":{\"sections\":[{\"narration\":\"wrong type\"}]}}")) {
            assertThrows(IOException.class, () -> ScriptNarrationParser.parse(text, plan));
        }
    }

    @Test public void rejectsMissingNarrationIdAndDuplicateRouteIds() throws Exception {
        JSONObject response = sample(); RoutePlan plan = route(response);
        firstSegment(response).remove("id");
        assertThrows(IOException.class, () -> ScriptNarrationParser.parse(response.toString(), plan));
        JSONObject valid = sample(); RoutePlan duplicate = route(valid);
        duplicate.legs.get(0).steps.add(duplicate.legs.get(0).steps.get(0));
        assertThrows(IOException.class, () -> ScriptNarrationParser.parse(valid.toString(), duplicate));
    }
}

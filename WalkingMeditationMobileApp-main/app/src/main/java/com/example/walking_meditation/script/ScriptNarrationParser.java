package com.example.walking_meditation.script;

import com.example.walking_meditation.models.LatLon;
import com.example.walking_meditation.models.RouteLeg;
import com.example.walking_meditation.models.RoutePlan;
import com.example.walking_meditation.models.RouteStep;
import com.example.walking_meditation.models.ScheduledNarration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Extracts ordered narration without modifying the selected physical route. */
public final class ScriptNarrationParser {
    private ScriptNarrationParser() {}

    public static List<ScheduledNarration> parse(String responseJson, RoutePlan route)
            throws IOException {
        if (route == null || route.legs == null || route.legs.isEmpty()) {
            throw new IOException("Selected route has no legs");
        }
        Map<String, RouteStep> steps = new HashMap<>();
        Map<String, String> phases = new HashMap<>();
        for (RouteLeg leg : route.legs) {
            if (leg == null || leg.id == null || leg.steps == null) {
                throw new IOException("Selected route has an invalid leg");
            }
            for (RouteStep step : leg.steps) {
                if (step == null || step.id == null || step.id.trim().isEmpty()
                        || steps.put(step.id, step) != null) {
                    throw new IOException("Selected route has missing or duplicate step IDs");
                }
                phases.put(step.id, leg.id);
            }
        }
        try {
            JSONObject response = new JSONObject(responseJson);
            Object scriptValue = response.opt("script");
            if (scriptValue instanceof String) {
                throw new IOException("Legacy text/XML scripts are unsupported; generate a new script");
            }
            if (!(scriptValue instanceof JSONObject)) {
                throw new IOException("Script response has no script object");
            }
            JSONArray sections = ((JSONObject) scriptValue).getJSONArray("sections");
            List<ScheduledNarration> result = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int s = 0; s < sections.length(); s++) {
                JSONObject section = sections.getJSONObject(s);
                addStandalone(section, "opening_narration", route, result, ids);
                if (section.has("route_segments")) {
                    JSONArray segments = section.getJSONArray("route_segments");
                    for (int i = 0; i < segments.length(); i++) {
                        JSONObject segment = segments.getJSONObject(i);
                        String id = requiredString(segment, "id");
                        RouteStep step = steps.get(id);
                        if (step == null) throw new IOException("Unknown route step: " + id);
                        if (!phases.get(id).equals(requiredString(segment, "route_phase"))) {
                            throw new IOException("Route phase mismatch for " + id);
                        }
                        if (segment.has("index") && step.index > 0
                                && segment.getInt("index") != step.index) {
                            throw new IOException("Route index mismatch for " + id);
                        }
                        checkCoordinate(segment, "start", step.start, id);
                        checkCoordinate(segment, "end", step.end, id);
                        String text = string(segment, "narration");
                        add(id, text, id, result, ids);
                    }
                }
                addStandalone(section, "closing_narration", route, result, ids);
                addStandalone(section, "narration", route, result, ids);
            }
            if (result.isEmpty()) throw new IOException("Script contains no narration");
            return result;
        } catch (JSONException | NullPointerException exception) {
            throw new IOException("Invalid structured script response", exception);
        }
    }

    private static void checkCoordinate(JSONObject segment, String field, LatLon expected,
                                        String id) throws JSONException, IOException {
        if (!segment.has(field) || expected == null) return;
        JSONObject point = segment.getJSONObject(field);
        double lat = point.getDouble("lat");
        double lon = point.getDouble("lon");
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || Math.abs(lat - expected.lat) > 0.00001
                || Math.abs(lon - expected.lon) > 0.00001) {
            throw new IOException("Route geometry mismatch for " + id + " (" + field + ")");
        }
    }

    private static String string(JSONObject value, String field) throws JSONException {
        Object text = value.get(field);
        if (!(text instanceof String)) throw new JSONException(field + " must be text");
        return ((String) text).trim();
    }

    private static String requiredString(JSONObject value, String field)
            throws JSONException, IOException {
        String text = string(value, field);
        if (text.isEmpty()) throw new IOException("Missing " + field + " in narration");
        return text;
    }

    private static void addStandalone(JSONObject section, String field,
                                      RoutePlan route, List<ScheduledNarration> result, Set<String> ids)
            throws JSONException, IOException {
        if (!section.has(field)) return;
        JSONObject narration = section.getJSONObject(field);
        String text = string(narration, "text");
        if (text.isEmpty()) return;
        add(requiredString(narration, "id"), text, null, result, ids);
        ScheduledNarration added = result.get(result.size() - 1);
        String type = section.optString("type");
        if ("focused_attention".equals(type)) {
            added.routeLegId = route.legs.get(0).id;
            added.anchor = "opening_narration".equals(field)
                    ? ScheduledNarration.Anchor.ROUTE_START : ScheduledNarration.Anchor.LEG_END;
        } else if ("closing_meditation".equals(type)) {
            added.routeLegId = route.legs.get(route.legs.size() - 1).id;
            added.anchor = "opening_narration".equals(field)
                    ? ScheduledNarration.Anchor.LEG_START : ScheduledNarration.Anchor.ROUTE_END;
        } else if ("compassion_meditation".equals(type)) {
            added.routeLegId = route.legs.get(0).id;
            added.anchor = ScheduledNarration.Anchor.LEG_END;
        } else {
            throw new IOException("Unsupported narration section: " + type);
        }
    }

    private static void add(String id, String text, String stepId,
                            List<ScheduledNarration> result, Set<String> ids) throws IOException {
        if (text.isEmpty()) return;
        if (!ids.add(id)) throw new IOException("Duplicate narration ID: " + id);
        ScheduledNarration narration = new ScheduledNarration();
        narration.id = id;
        narration.text = text;
        narration.routeStepId = stepId;
        if (stepId != null) narration.anchor = ScheduledNarration.Anchor.STEP_START;
        result.add(narration);
    }
}
import unittest

from route.services.script_fallback import build_fallback_narrations

FOCUSED = [
    ("source_to_park_1", "Swanston St", "high elevation (uphill)"),
    ("source_to_park_2", "", "low elevation (mostly flat)"),
]
CLOSING = [("park_to_destination_1", "Bourke St", "high elevation (downhill)")]


class FallbackNarrationTests(unittest.TestCase):
    def test_has_one_narration_per_segment_id(self):
        result = build_fallback_narrations(FOCUSED, CLOSING, has_park=True)

        self.assertEqual(
            {"source_to_park_1", "source_to_park_2"},
            set(result["focused_attention"]["segment_narrations"]),
        )
        self.assertEqual(
            {"park_to_destination_1"},
            set(result["closing_meditation"]["segment_narrations"]),
        )

    def test_all_text_blocks_are_non_empty(self):
        for has_park, closing in [(True, CLOSING), (False, [])]:
            result = build_fallback_narrations(FOCUSED, closing, has_park=has_park)
            focused = result["focused_attention"]
            closing_section = result["closing_meditation"]
            texts = [
                focused["opening_narration"],
                focused["closing_narration"],
                result["compassion_meditation"]["narration"],
                closing_section["opening_narration"],
                closing_section["closing_narration"],
                *focused["segment_narrations"].values(),
                *closing_section["segment_narrations"].values(),
            ]
            self.assertTrue(all(text.strip() for text in texts))

    def test_terrain_wording_matches_label_without_numbers(self):
        result = build_fallback_narrations(FOCUSED, CLOSING, has_park=True)
        segments = result["focused_attention"]["segment_narrations"]

        self.assertIn("rises", segments["source_to_park_1"])
        self.assertIn("flat", segments["source_to_park_2"])
        self.assertIn(
            "slopes down",
            result["closing_meditation"]["segment_narrations"]["park_to_destination_1"],
        )
        self.assertFalse(any(ch.isdigit() for ch in segments["source_to_park_1"]))

    def test_missing_route_label_falls_back_to_generic_wording(self):
        result = build_fallback_narrations(FOCUSED, [], has_park=False)
        text = result["focused_attention"]["segment_narrations"]["source_to_park_2"]
        self.assertIn("along the path", text)

    def test_park_and_no_park_compassion_differ(self):
        with_park = build_fallback_narrations(FOCUSED, CLOSING, has_park=True)
        without_park = build_fallback_narrations(FOCUSED, [], has_park=False)
        self.assertIn("park", with_park["compassion_meditation"]["narration"])
        self.assertNotIn("park", without_park["compassion_meditation"]["narration"])

    def test_never_asks_listener_to_use_a_phone(self):
        result = build_fallback_narrations(FOCUSED, CLOSING, has_park=True)
        combined = " ".join(
            [
                result["focused_attention"]["opening_narration"],
                *result["focused_attention"]["segment_narrations"].values(),
                result["compassion_meditation"]["narration"],
            ]
        ).casefold()
        self.assertNotIn("phone", combined)
        self.assertNotIn("screen", combined)


if __name__ == "__main__":
    unittest.main()
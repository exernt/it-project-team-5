import unittest

from route.utils.route_hint import extract_street_hint


class ExtractStreetHintTests(unittest.TestCase):
    def test_ignores_destination_advisory_on_second_line(self):
        instruction = (
            "Turn left onto Nicholson St\n"
            "Destination will be on the right"
        )

        self.assertEqual("Nicholson St", extract_street_hint(instruction))

    def test_preserves_valid_on_route_metadata(self):
        instruction = (
            "Head east on Victoria St/State Route 32 "
            "toward Rathdowne St/State Route 46"
        )

        self.assertEqual(
            "Victoria St/State Route 32 toward Rathdowne St/State Route 46",
            extract_street_hint(instruction),
        )

    def test_preserves_valid_onto_route_metadata(self):
        self.assertEqual(
            "Swanston St",
            extract_street_hint("Turn left onto Swanston St"),
        )

    def test_preserves_valid_toward_route_metadata(self):
        instruction = "Turn right toward Swanston St\nTake the stairs"

        self.assertEqual("Swanston St", extract_street_hint(instruction))

    def test_keeps_primary_instruction_when_no_street_is_present(self):
        self.assertEqual("Turn right", extract_street_hint("Turn right"))

    def test_uses_first_non_empty_line(self):
        self.assertEqual(
            "Nicholson St",
            extract_street_hint("\n  Turn left onto Nicholson St  \nDestination ahead"),
        )

    def test_empty_instruction_returns_empty_hint(self):
        self.assertEqual("", extract_street_hint(" \n "))


if __name__ == "__main__":
    unittest.main()

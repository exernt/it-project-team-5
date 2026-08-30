import unittest

from route.utils.script_xml import normalize_script_xml


class NormalizeScriptXmlTests(unittest.TestCase):
    def test_accepts_valid_meditation(self):
        xml = "<meditation><section><narration id='1'>Hello</narration></section></meditation>"
        self.assertEqual(xml, normalize_script_xml(xml))

    def test_removes_markdown_fence(self):
        xml = "<meditation><narration id='1'>Hello</narration></meditation>"
        self.assertEqual(xml, normalize_script_xml(f"```xml\n{xml}\n```"))

    def test_rejects_wrong_root_or_missing_narration(self):
        for xml in ("<root><narration>Hi</narration></root>", "<meditation />", "not xml"):
            with self.subTest(xml=xml):
                with self.assertRaises(ValueError):
                    normalize_script_xml(xml)


if __name__ == "__main__":
    unittest.main()

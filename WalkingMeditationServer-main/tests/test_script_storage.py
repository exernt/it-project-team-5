import tempfile
import unittest
from pathlib import Path

from route.persistence.script_storage import save_script_text


class ScriptStorageTests(unittest.TestCase):
    def test_concurrent_timestamp_names_do_not_collide(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir)
            first = save_script_text("first", output)
            second = save_script_text("second", output)

            self.assertNotEqual(first, second)
            self.assertEqual("first", first.read_text(encoding="utf-8"))
            self.assertEqual("second", second.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()

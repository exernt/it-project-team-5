import json
import unittest
from unittest.mock import patch

from fastapi import HTTPException

from main import android_asset_links, password_reset_landing_page


class AndroidAssetLinksTests(unittest.IsolatedAsyncioTestCase):
    async def test_asset_links_uses_configured_package_and_fingerprints(self):
        with patch.dict(
            "os.environ",
            {
                "ANDROID_APP_PACKAGE_NAME": "com.example.walking_meditation",
                "ANDROID_APP_SHA256_CERT_FINGERPRINTS": "AA:BB,CC:DD",
            },
        ):
            response = await android_asset_links()

        payload = json.loads(response.body)
        self.assertEqual(
            "com.example.walking_meditation",
            payload[0]["target"]["package_name"],
        )
        self.assertEqual(
            ["AA:BB", "CC:DD"],
            payload[0]["target"]["sha256_cert_fingerprints"],
        )

    async def test_asset_links_is_absent_until_fingerprint_is_configured(self):
        with patch.dict(
            "os.environ",
            {"ANDROID_APP_SHA256_CERT_FINGERPRINTS": ""},
        ):
            with self.assertRaises(HTTPException) as raised:
                await android_asset_links()

        self.assertEqual(404, raised.exception.status_code)


class PasswordResetLandingPageTests(unittest.IsolatedAsyncioTestCase):
    async def test_landing_page_is_not_cached_and_forwards_fragment(self):
        response = await password_reset_landing_page()
        body = response.body.decode("utf-8")

        self.assertEqual("no-store", response.headers["cache-control"])
        self.assertEqual("no-referrer", response.headers["referrer-policy"])
        self.assertIn("window.location.hash", body)
        self.assertIn("walkingmeditation://password-reset", body)


if __name__ == "__main__":
    unittest.main()

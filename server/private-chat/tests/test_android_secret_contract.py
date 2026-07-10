from __future__ import annotations

import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]


class AndroidSecretContractTests(unittest.TestCase):
    def test_keystore_ciphertext_preferences_are_excluded_from_backup_and_transfer(self) -> None:
        manifest = (REPO_ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        legacy = (REPO_ROOT / "android/app/src/main/res/xml/backup_rules.xml").read_text(encoding="utf-8")
        extraction = (REPO_ROOT / "android/app/src/main/res/xml/data_extraction_rules.xml").read_text(encoding="utf-8")
        self.assertIn("@xml/backup_rules", manifest)
        self.assertIn("@xml/data_extraction_rules", manifest)
        self.assertIn('path="zerovpn_secure_secrets.xml"', legacy)
        self.assertEqual(2, extraction.count('path="zerovpn_secure_secrets.xml"'))

    def test_owner_credentials_are_written_only_through_secure_secret_store(self) -> None:
        view_model = (
            REPO_ROOT
            / "android/app/src/main/java/com/zerovpn/app/ui/provisioning/ProvisioningViewModel.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("secretStore.putSecret(credentialSecretKey", view_model)
        self.assertNotIn('.put("password"', view_model)


if __name__ == "__main__":
    unittest.main()


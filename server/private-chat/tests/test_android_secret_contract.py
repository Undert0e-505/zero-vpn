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

    def test_private_chat_diagnostics_show_required_non_secret_fields(self) -> None:
        diagnostics = (
            REPO_ROOT
            / "android/app/src/main/java/com/zerovpn/app/ui/screens/DiagnosticsScreen.kt"
        ).read_text(encoding="utf-8")
        for required in (
            "Private Chat installed",
            "PostgreSQL",
            "Synapse",
            "TLS endpoint",
            "Matrix /versions",
            "Owner account",
            "Installation stages",
            "Chat-only peer rules active",
            "Last self-test",
            "server_name",
            "Private Matrix URL",
            "TLS fingerprint",
            "Installed versions",
        ):
            with self.subTest(required=required):
                self.assertIn(required, diagnostics)
        for forbidden in ("ownerCredentialsSecretKey", 'getString("password")', "accessToken"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, diagnostics)

    def test_existing_provisioning_log_carries_chat_duration_status_and_retry_guidance(self) -> None:
        view_model = (
            REPO_ROOT
            / "android/app/src/main/java/com/zerovpn/app/ui/provisioning/ProvisioningViewModel.kt"
        ).read_text(encoding="utf-8")
        screen = (
            REPO_ROOT
            / "android/app/src/main/java/com/zerovpn/app/ui/screens/ProvisioningScreen.kt"
        ).read_text(encoding="utf-8")
        for required in (
            "duration=",
            "Retry Private Chat to resume from the saved VM stage",
            "Private Chat health checks:",
            "server_name=",
            "tls_fingerprint=",
        ):
            self.assertIn(required, view_model)
        self.assertIn("PRIVATE CHAT PROVISIONING LOG", screen)
        self.assertIn("event.technicalDetail", screen)

    def test_apk_assets_stage_only_runtime_files_and_real_self_test(self) -> None:
        build = (REPO_ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('tasks.register<Sync>("generatePrivateChatAssets")', build)
        self.assertIn('"tests/**"', build)
        self.assertIn('tests/encrypted_self_test.py', build)
        self.assertIn('srcDir(generatedPrivateChatAssets)', build)
        self.assertNotIn('srcDir(rootProject.file("../server"))', build)


if __name__ == "__main__":
    unittest.main()


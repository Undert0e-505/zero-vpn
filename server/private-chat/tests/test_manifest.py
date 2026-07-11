from __future__ import annotations

import base64
import unittest
import uuid

from installer_test_imports import installer_model


validate_node_manifest = installer_model.validate_node_manifest
Stage = installer_model.Stage


def valid_manifest() -> dict[str, object]:
    node_id = uuid.UUID("12345678-1234-4234-8234-1234567890ab")
    server_name = f"node-{node_id.hex[:12]}.zerovpn"
    pin = "sha256/" + base64.b64encode(bytes(range(32))).decode("ascii")
    return {
        "schemaVersion": 1,
        "installerVersion": "0.1.0",
        "nodeId": str(node_id),
        "serverName": server_name,
        "matrixPrivateUrl": "https://10.66.66.1",
        "tlsSpkiSha256": pin,
        "components": {"postgresql": "16", "synapse": "1.156.0"},
        "installedAt": "2026-07-10T00:00:00Z",
        "health": {"status": "healthy", "checks": {}},
        "ownerMatrixUserId": f"@owner:{server_name}",
        "network": {
            "wireguardInterface": "wg0",
            "wireguardAddress": "10.66.66.1",
            "matrixPort": 443,
            "synapseLoopbackPort": 8008,
            "federationEnabled": False,
        },
        "installation": {
            "currentStage": Stage.COMPLETE.value,
            "stages": {
                stage.value: {"status": "complete"}
                for stage in Stage
            },
            "lastSelfTest": {"status": "pass", "checkedAt": "2026-07-10T00:00:00Z"},
        },
    }


class ManifestValidationTests(unittest.TestCase):
    def test_valid_non_secret_manifest_passes(self) -> None:
        validate_node_manifest(valid_manifest())

    def test_public_or_cleartext_matrix_url_is_rejected(self) -> None:
        for url in (
            "http://10.66.66.1",
            "https://8.8.8.8",
            "https://user:pass@10.66.66.1",
            "https://10.66.66.1:8448",
            "https://10.66.66.1/other",
        ):
            manifest = valid_manifest()
            manifest["matrixPrivateUrl"] = url
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_node_manifest(manifest)

    def test_secret_shaped_fields_are_rejected_at_any_depth(self) -> None:
        manifest = valid_manifest()
        for secret_key in ("password", "clientSecret", "accessToken", "private_key"):
            candidate = valid_manifest()
            candidate["owner"] = {secret_key: "must-never-be-in-node-json"}
            with self.subTest(secret_key=secret_key), self.assertRaises(ValueError) as failure:
                validate_node_manifest(candidate)
            self.assertIn("prohibited", str(failure.exception))

    def test_owner_and_stable_server_name_must_match(self) -> None:
        manifest = valid_manifest()
        manifest["ownerMatrixUserId"] = "@owner:other.example"
        with self.assertRaises(ValueError):
            validate_node_manifest(manifest)

    def test_private_url_must_match_declared_wireguard_network(self) -> None:
        manifest = valid_manifest()
        manifest["network"]["wireguardAddress"] = "10.77.0.1"  # type: ignore[index]
        with self.assertRaises(ValueError) as failure:
            validate_node_manifest(manifest)
        self.assertIn("wireguardAddress", str(failure.exception))

    def test_every_stage_and_self_test_status_must_be_valid(self) -> None:
        manifest = valid_manifest()
        manifest["installation"]["stages"][Stage.TLS.value]["status"] = "mystery"  # type: ignore[index]
        with self.assertRaises(ValueError):
            validate_node_manifest(manifest)


if __name__ == "__main__":
    unittest.main()

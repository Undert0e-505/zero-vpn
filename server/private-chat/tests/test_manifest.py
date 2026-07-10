from __future__ import annotations

import base64
import unittest
import uuid

from installer_test_imports import installer_model


validate_node_manifest = installer_model.validate_node_manifest


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
        manifest["owner"] = {"password": "must-never-be-in-node-json"}
        with self.assertRaises(ValueError) as failure:
            validate_node_manifest(manifest)
        self.assertIn("prohibited", str(failure.exception))

    def test_owner_and_stable_server_name_must_match(self) -> None:
        manifest = valid_manifest()
        manifest["ownerMatrixUserId"] = "@owner:other.example"
        with self.assertRaises(ValueError):
            validate_node_manifest(manifest)


if __name__ == "__main__":
    unittest.main()

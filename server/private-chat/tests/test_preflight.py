from __future__ import annotations

import unittest

from installer_test_imports import installer_model


PreflightError = installer_model.PreflightError
PreflightSnapshot = installer_model.PreflightSnapshot
evaluate_preflight = installer_model.evaluate_preflight


def supported_snapshot(**changes: object) -> PreflightSnapshot:
    values: dict[str, object] = {
        "os_id": "ubuntu",
        "os_version": "24.04",
        "architecture": "aarch64",
        "total_ram_bytes": 6 * 1024**3,
        "available_ram_bytes": 4 * 1024**3,
        "free_disk_bytes": 30 * 1024**3,
        "package_manager_healthy": True,
        "package_manager_detail": "ok",
        "wireguard_service_active": True,
        "wireguard_addresses": frozenset({"10.66.66.1"}),
    }
    values.update(changes)
    return PreflightSnapshot(**values)  # type: ignore[arg-type]


class PreflightTests(unittest.TestCase):
    def test_supported_a1_snapshot_passes(self) -> None:
        report = evaluate_preflight(supported_snapshot(), "10.66.66.1")
        self.assertEqual((), report.warnings)
        self.assertEqual("aarch64", report.facts["architecture"])

    def test_resource_shortage_fails_without_resize_action(self) -> None:
        with self.assertRaises(PreflightError) as failure:
            evaluate_preflight(
                supported_snapshot(total_ram_bytes=1024**3, free_disk_bytes=5 * 1024**3),
                "10.66.66.1",
            )
        message = str(failure.exception)
        self.assertIn("2 GiB", message)
        self.assertIn("10 GiB", message)
        self.assertNotIn("resize", message.lower())

    def test_recommended_resource_warning_is_explicitly_non_mutating(self) -> None:
        report = evaluate_preflight(
            supported_snapshot(total_ram_bytes=4 * 1024**3, free_disk_bytes=15 * 1024**3),
            "10.66.66.1",
        )
        warning = " ".join(report.warnings).lower()
        self.assertIn("will not resize", warning)

    def test_wireguard_and_package_manager_are_hard_gates(self) -> None:
        with self.assertRaises(PreflightError) as failure:
            evaluate_preflight(
                supported_snapshot(
                    package_manager_healthy=False,
                    package_manager_detail="dpkg interrupted",
                    wireguard_service_active=False,
                    wireguard_addresses=frozenset(),
                ),
                "10.66.66.1",
            )
        message = str(failure.exception)
        self.assertIn("package manager", message)
        self.assertIn("wg-quick", message)
        self.assertIn("required private address", message)

    def test_unmanaged_synapse_and_port_conflict_are_refused(self) -> None:
        with self.assertRaises(PreflightError) as failure:
            evaluate_preflight(
                supported_snapshot(
                    synapse_installed=True,
                    port_owners={443: 'users:(("caddy",pid=42))'},
                ),
                "10.66.66.1",
            )
        self.assertIn("unmanaged Synapse", str(failure.exception))
        self.assertIn("TCP port 443", str(failure.exception))

    def test_unmanaged_public_postgres_listener_is_not_silently_changed(self) -> None:
        with self.assertRaises(PreflightError) as failure:
            evaluate_preflight(
                supported_snapshot(
                    postgres_installed=True,
                    postgres_public_listener=True,
                    port_owners={5432: "postgres"},
                ),
                "10.66.66.1",
            )
        self.assertIn("beyond loopback", str(failure.exception))

    def test_managed_partial_install_is_resumable(self) -> None:
        report = evaluate_preflight(
            supported_snapshot(
                managed_install=True,
                postgres_installed=True,
                synapse_installed=True,
                port_owners={443: "nginx", 5432: "postgres", 8008: "synapse"},
                partial_paths=("/etc/zerovpn/private-chat",),
            ),
            "10.66.66.1",
        )
        self.assertTrue(report.facts["partialInstallDetected"])
        self.assertIn("resumed", " ".join(report.warnings))


if __name__ == "__main__":
    unittest.main()

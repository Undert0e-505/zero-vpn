from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class VpnSurvivalContractTests(unittest.TestCase):
    def test_server_workload_does_not_mutate_wireguard_configuration_or_peers(self) -> None:
        source = (ROOT / "installer/install.py").read_text(encoding="utf-8")
        firewall = (ROOT / "installer/model.py").read_text(encoding="utf-8")
        for forbidden in (
            "/etc/wireguard",
            "wg set",
            "wg-quick down",
            "systemctl stop wg-quick",
            "systemctl restart wg-quick",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, source)
                self.assertNotIn(forbidden, firewall)

    def test_removal_targets_only_named_private_chat_paths(self) -> None:
        source = (ROOT / "installer/install.py").read_text(encoding="utf-8")
        removal = source[source.index("def remove_installation") : source.index("def build_parser")]
        self.assertIn("POSTGRES_DATABASE", removal)
        self.assertIn("POSTGRES_ROLE", removal)
        self.assertIn("ETC_ROOT", removal)
        self.assertIn("INSTALL_ROOT", removal)
        self.assertNotIn("Oci", removal)
        self.assertNotIn("/etc/wireguard", removal)
        self.assertNotIn("wg-quick", removal)
        self.assertNotIn("wg set", removal)


if __name__ == "__main__":
    unittest.main()

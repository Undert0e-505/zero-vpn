from __future__ import annotations

import unittest

from installer_test_imports import installer_model


render_firewall_remove_script = installer_model.render_firewall_remove_script
render_firewall_script = installer_model.render_firewall_script


class FirewallGenerationTests(unittest.TestCase):
    def test_rules_bind_tls_to_wireguard_without_flushing_base_chains(self) -> None:
        script = render_firewall_script("wg0", "10.66.66.1")
        self.assertIn('-i "$WG_INTERFACE" -d "$WG_ADDRESS"', script)
        self.assertIn("--dport 443 -j ACCEPT", script)
        self.assertIn("! -i", script)
        self.assertNotIn("-F INPUT", script)
        self.assertNotIn("-F FORWARD", script)
        self.assertNotIn("wg-quick", script)
        self.assertNotIn("/etc/wireguard", script)

    def test_future_chat_peer_policy_denies_metadata_lateral_and_internet(self) -> None:
        script = render_firewall_script("wg0", "10.66.66.1")
        metadata = script.index("169.254.169.254/32")
        lateral = script.index('$WG_NETWORK')
        final_reject = script.rindex("ZEROVPN_CHAT_PEER_FORWARD -j REJECT")
        self.assertLess(metadata, final_reject)
        self.assertLess(lateral, final_reject)
        self.assertIn("ZEROVPN_CHAT_PEER_INPUT", script)
        self.assertIn("ZEROVPN_CHAT_PEER_FORWARD", script)

    def test_remove_script_only_deletes_private_chat_chains(self) -> None:
        script = render_firewall_remove_script()
        self.assertIn("ZEROVPN_PRIVATE_CHAT_INPUT", script)
        self.assertNotIn("-F INPUT", script)
        self.assertNotIn("-F FORWARD", script)
        self.assertNotIn("-t nat", script)

    def test_invalid_interface_or_public_address_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            render_firewall_script("wg0; reboot", "10.66.66.1")
        with self.assertRaises(ValueError):
            render_firewall_script("wg0", "8.8.8.8")


if __name__ == "__main__":
    unittest.main()

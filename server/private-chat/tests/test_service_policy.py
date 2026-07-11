from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ServicePolicyContractTests(unittest.TestCase):
    def test_postgresql_is_configured_for_loopback_only(self) -> None:
        config = (ROOT / "postgres/90-zerovpn-private-chat.conf.template").read_text(encoding="utf-8")
        self.assertIn("listen_addresses = '127.0.0.1,::1'", config)
        self.assertIn("password_encryption = 'scram-sha-256'", config)

    def test_synapse_is_private_closed_and_non_federated(self) -> None:
        homeserver = (ROOT / "synapse/homeserver.yaml.template").read_text(encoding="utf-8")
        for required in (
            "bind_addresses:\n      - 127.0.0.1",
            "- client",
            "enable_registration: false",
            "enable_registration_without_verification: false",
            "allow_guest_access: false",
            "allow_public_rooms_without_auth: false",
            "allow_public_rooms_over_federation: false",
            "federation_domain_whitelist: []",
            "send_federation: false",
            "enable_media_repo: false",
            "report_stats: false",
        ):
            with self.subTest(required=required):
                self.assertIn(required, homeserver)
        self.assertNotIn("- federation", homeserver)

        unit = (ROOT / "synapse/zerovpn-private-chat-synapse.service.template").read_text(encoding="utf-8")
        self.assertIn("IPAddressDeny=any", unit)
        self.assertIn("IPAddressAllow=localhost", unit)

    def test_nginx_binds_only_wireguard_and_blocks_admin_and_federation(self) -> None:
        nginx = (ROOT / "synapse/nginx-private-chat.conf.template").read_text(encoding="utf-8")
        self.assertIn("listen __WIREGUARD_ADDRESS__:443 ssl;", nginx)
        self.assertNotIn("listen 443", nginx)
        self.assertNotIn("0.0.0.0:443", nginx)
        generic_proxy = nginx.index("location /_matrix/")
        for blocked in (
            "location ^~ /_synapse/admin/",
            "location ^~ /_matrix/federation/",
            "location ^~ /_matrix/key/",
        ):
            self.assertLess(nginx.index(blocked), generic_proxy)

    def test_tls_identity_has_private_sans_pin_and_restricted_key_mode(self) -> None:
        installer = (ROOT / "installer/install.py").read_text(encoding="utf-8")
        for required in (
            "ec_paramgen_curve:P-256",
            "subjectAltName=DNS:{server_name},IP:{self.address}",
            "extendedKeyUsage=serverAuth",
            "key.chmod(0o600)",
            "tls_spki_pin()",
        ):
            with self.subTest(required=required):
                self.assertIn(required, installer)


if __name__ == "__main__":
    unittest.main()

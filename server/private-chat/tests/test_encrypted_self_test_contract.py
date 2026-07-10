from __future__ import annotations

import ast
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("encrypted_self_test.py")


class EncryptedSelfTestContractTests(unittest.TestCase):
    def test_script_is_valid_python_and_contains_required_real_matrix_operations(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        ast.parse(source)
        for required in (
            "AsyncClient",
            "m.room.encryption",
            "m.room.encrypted",
            "ciphertext",
            "RoomMessageText",
            "room_send",
            "deactivate",
            "purge",
            "ZEROVPN_E2EE_RESULT",
        ):
            with self.subTest(required=required):
                self.assertIn(required, source)
        self.assertNotIn("unittest.mock", source)
        self.assertNotIn("D:\\dev\\zero-chat", source)

    def test_result_contract_has_both_directions_raw_ciphertext_and_cleanup(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        for evidence in (
            "aToBDecrypted",
            "aToBRawEncrypted",
            "aToBPlaintextAbsent",
            "bToADecrypted",
            "bToARawEncrypted",
            "bToAPlaintextAbsent",
            "temporaryAccountsDeactivated",
            "roomPurged",
        ):
            self.assertIn(evidence, source)


if __name__ == "__main__":
    unittest.main()


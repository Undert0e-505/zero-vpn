from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from installer_test_imports import installer_model


Stage = installer_model.Stage
StageDefinition = installer_model.StageDefinition
StageFailure = installer_model.StageFailure
StageLedger = installer_model.StageLedger
StageRunner = installer_model.StageRunner


class StageStateTests(unittest.TestCase):
    def test_probe_satisfied_stage_is_recorded_without_apply(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            applied: list[str] = []
            events: list[tuple[str, str]] = []
            ledger = StageLedger(Path(directory) / "state.json", now=lambda: "2026-07-10T00:00:00Z")
            runner = StageRunner(ledger, lambda stage, status, _: events.append((stage.value, status)))

            runner.run(
                [
                    StageDefinition(
                        Stage.PACKAGES,
                        probe=lambda: True,
                        apply=lambda: applied.append("unexpected"),
                    )
                ]
            )

            self.assertEqual([], applied)
            self.assertEqual("complete", ledger.stage_status(Stage.PACKAGES))
            record = ledger.data["stages"][Stage.PACKAGES.value]
            self.assertTrue(record["probeSatisfied"])
            self.assertEqual(0, record["attempts"])
            self.assertEqual([(Stage.PACKAGES.value, "success")], events)

    def test_failed_stage_retries_without_replaying_satisfied_stage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state = {"packages": True, "postgres": False, "postgres_attempts": 0}
            ledger = StageLedger(Path(directory) / "state.json", now=lambda: "2026-07-10T00:00:00Z")
            runner = StageRunner(ledger, lambda *_: None)

            def postgres_apply() -> None:
                state["postgres_attempts"] += 1
                if state["postgres_attempts"] == 1:
                    raise RuntimeError("simulated safe failure")
                state["postgres"] = True

            definitions = [
                StageDefinition(Stage.PACKAGES, lambda: state["packages"], lambda: self.fail("packages replayed")),
                StageDefinition(Stage.POSTGRES, lambda: state["postgres"], postgres_apply),
            ]

            with self.assertRaises(StageFailure) as failure:
                runner.run(definitions)
            self.assertEqual(Stage.POSTGRES, failure.exception.stage)
            self.assertEqual("failed", ledger.stage_status(Stage.POSTGRES))
            self.assertEqual(1, ledger.data["stages"][Stage.POSTGRES.value]["attempts"])

            runner.run(definitions)
            self.assertEqual("complete", ledger.stage_status(Stage.POSTGRES))
            self.assertEqual(2, ledger.data["stages"][Stage.POSTGRES.value]["attempts"])
            self.assertEqual(2, state["postgres_attempts"])

    def test_ledger_redacts_secret_shaped_failure_text(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            ledger = StageLedger(Path(directory) / "state.json", now=lambda: "2026-07-10T00:00:00Z")
            ledger.begin(Stage.OWNER_ACCOUNT)
            ledger.fail(
                Stage.OWNER_ACCOUNT,
                "password=do-not-store Bearer abc.def.ghi https://build-user:index-pass@example.invalid/simple",
            )
            serialized = (Path(directory) / "state.json").read_text(encoding="utf-8")
            self.assertNotIn("do-not-store", serialized)
            self.assertNotIn("abc.def.ghi", serialized)
            self.assertNotIn("index-pass", serialized)
            self.assertIn("[REDACTED]", serialized)


if __name__ == "__main__":
    unittest.main()

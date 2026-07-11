"""Opt-in pytest entry point for the real encrypted Matrix exchange.

This file deliberately does not match the repository's normal ``test_*.py``
discovery pattern.  The dedicated interoperability command names it explicitly.
Optional packages are checked before the real client module is imported so an
ordinary unit-test collection never requires matrix-nio/libolm.
"""

from __future__ import annotations

import asyncio
import importlib.util
import os
import sys
from pathlib import Path

import pytest


SCRIPT = Path(__file__).with_name("encrypted_self_test.py")
REQUIRED_ENVIRONMENT = (
    "ZEROVPN_MATRIX_OWNER_CREDENTIALS",
    "ZEROVPN_MATRIX_REGISTRATION_SECRET",
)
REQUIRED_EVIDENCE = (
    "roomEncrypted",
    "aToBDecrypted",
    "aToBRawEncrypted",
    "aToBPlaintextAbsent",
    "bToADecrypted",
    "bToARawEncrypted",
    "bToAPlaintextAbsent",
    "temporaryAccountsDeactivated",
    "roomPurged",
)


def _missing_modules() -> list[str]:
    return [name for name in ("aiohttp", "nio", "olm") if importlib.util.find_spec(name) is None]


@pytest.mark.interop
def test_real_encrypted_matrix_exchange() -> None:
    if sys.platform != "linux":
        pytest.skip(
            "the real encrypted exchange runs only on the disposable Ubuntu/Synapse test node; "
            "Windows intentionally omits matrix-nio/libolm"
        )
    missing_modules = _missing_modules()
    if missing_modules:
        pytest.skip(
            "optional Matrix interoperability dependencies are absent "
            f"({', '.join(missing_modules)}); create .venv-interop and install requirements-interop.txt"
        )
    if os.geteuid() != 0:
        pytest.skip("the real encrypted exchange must run as root to read its root-owned test inputs")

    missing_environment = [name for name in REQUIRED_ENVIRONMENT if not os.environ.get(name, "").strip()]
    if missing_environment:
        pytest.skip(
            "disposable Synapse test inputs are not configured: " + ", ".join(missing_environment)
        )
    missing_files = [
        name
        for name in REQUIRED_ENVIRONMENT
        if not Path(os.environ[name]).is_file()
    ]
    if missing_files:
        pytest.skip("root-owned Synapse test input files are missing: " + ", ".join(missing_files))

    spec = importlib.util.spec_from_file_location("zerovpn_encrypted_self_test", SCRIPT)
    if spec is None or spec.loader is None:
        pytest.fail("could not load the encrypted Matrix self-test implementation")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    evidence = asyncio.run(module.run_test())
    assert evidence.get("status") == "success"
    assert all(evidence.get(name) is True for name in REQUIRED_EVIDENCE)

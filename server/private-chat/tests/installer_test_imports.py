"""Import shim for tests of the repository's hyphenated private-chat tree."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


MODEL_PATH = Path(__file__).resolve().parents[1] / "installer" / "model.py"
SPEC = importlib.util.spec_from_file_location("zerovpn_private_chat_installer_model", MODEL_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load the private-chat installer model.")
installer_model = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = installer_model
SPEC.loader.exec_module(installer_model)


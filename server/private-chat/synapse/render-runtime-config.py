#!/usr/bin/env python3
"""Render Synapse's ephemeral secret-bearing runtime configuration."""

from __future__ import annotations

import os
from pathlib import Path


TEMPLATE = Path("/etc/zerovpn/private-chat/synapse/homeserver.yaml.template")
OUTPUT = Path("/run/zerovpn-private-chat-synapse/homeserver.yaml")


def main() -> None:
    credentials = Path(os.environ["CREDENTIALS_DIRECTORY"])
    password = (credentials / "postgres_password").read_text(encoding="utf-8").strip()
    if not password or any(character in password for character in "\r\n\"\\"):
        raise SystemExit("The PostgreSQL runtime credential is invalid.")
    rendered = TEMPLATE.read_text(encoding="utf-8").replace("__POSTGRES_PASSWORD__", password)
    if "__POSTGRES_PASSWORD__" in rendered:
        raise SystemExit("The PostgreSQL password placeholder was not rendered.")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(".tmp")
    temporary.write_text(rendered, encoding="utf-8", newline="\n")
    os.chmod(temporary, 0o600)
    os.replace(temporary, OUTPUT)
    os.chmod(OUTPUT, 0o600)


if __name__ == "__main__":
    main()


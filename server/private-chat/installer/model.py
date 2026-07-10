#!/usr/bin/env python3
"""Pure installer state, preflight, firewall, and manifest helpers.

This module deliberately contains no privileged side effects.  The production
installer imports it, and the repository test suite exercises it directly on
Windows without requiring an Ubuntu VM.
"""

from __future__ import annotations

import base64
import ipaddress
import json
import os
import re
import tempfile
import uuid
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, MutableMapping, Sequence
from urllib.parse import urlparse


INSTALLER_VERSION = "0.1.0"
STATE_SCHEMA_VERSION = 1
MANIFEST_SCHEMA_VERSION = 1
SUPPORTED_UBUNTU_VERSIONS = frozenset({"22.04", "24.04"})
SUPPORTED_ARCHITECTURES = frozenset({"aarch64", "x86_64"})
MIN_TOTAL_RAM_BYTES = 2 * 1024**3
RECOMMENDED_TOTAL_RAM_BYTES = 6 * 1024**3
MIN_AVAILABLE_RAM_BYTES = 768 * 1024**2
MIN_FREE_DISK_BYTES = 10 * 1024**3
RECOMMENDED_FREE_DISK_BYTES = 20 * 1024**3


class Stage(str, Enum):
    PRECHECK = "PRIVATE_CHAT_PRECHECK"
    PACKAGES = "PRIVATE_CHAT_PACKAGES"
    POSTGRES = "PRIVATE_CHAT_POSTGRES"
    SYNAPSE = "PRIVATE_CHAT_SYNAPSE"
    TLS = "PRIVATE_CHAT_TLS"
    FIREWALL = "PRIVATE_CHAT_FIREWALL"
    OWNER_ACCOUNT = "PRIVATE_CHAT_OWNER_ACCOUNT"
    HEALTH = "PRIVATE_CHAT_HEALTH"
    ENCRYPTION_SELF_TEST = "PRIVATE_CHAT_ENCRYPTION_SELF_TEST"
    COMPLETE = "PRIVATE_CHAT_COMPLETE"


STAGE_ORDER: tuple[Stage, ...] = tuple(Stage)


class InstallerError(RuntimeError):
    """A user-safe installer failure."""


class PreflightError(InstallerError):
    def __init__(self, errors: Sequence[str], warnings: Sequence[str] = ()) -> None:
        self.errors = tuple(errors)
        self.warnings = tuple(warnings)
        super().__init__("; ".join(self.errors))


class StageFailure(InstallerError):
    def __init__(self, stage: Stage, reason: str) -> None:
        self.stage = stage
        self.reason = sanitize_error(reason)
        super().__init__(f"{stage.value}: {self.reason}")


_SENSITIVE_ASSIGNMENT = re.compile(
    r"(?i)\b(password|passwd|secret|token|authorization|private[_ -]?key)\b"
    r"(\s*[:=]\s*)([^\s,;]+)"
)
_BEARER = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+")
_URL_USERINFO = re.compile(r"(?i)(https?://)([^/@\s:]+):([^/@\s]+)@")


def sanitize_error(value: object, limit: int = 600) -> str:
    """Return one bounded, single-line, secret-redacted diagnostic string."""

    text = " ".join(str(value).replace("\x00", " ").split())
    text = _BEARER.sub("Bearer [REDACTED]", text)
    text = _URL_USERINFO.sub(r"\1[REDACTED]@", text)
    text = _SENSITIVE_ASSIGNMENT.sub(lambda match: f"{match.group(1)}{match.group(2)}[REDACTED]", text)
    return (text or "The stage failed without a diagnostic.")[:limit]


def utc_timestamp() -> str:
    from datetime import datetime, timezone

    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class StageLedger:
    """Atomic, non-secret, root-owned installation journal."""

    def __init__(self, path: Path, now: Callable[[], str] = utc_timestamp) -> None:
        self.path = Path(path)
        self.now = now
        self.data: dict[str, Any] = self._load()

    def _empty(self) -> dict[str, Any]:
        return {
            "schemaVersion": STATE_SCHEMA_VERSION,
            "installerVersion": INSTALLER_VERSION,
            "createdAt": self.now(),
            "updatedAt": self.now(),
            "currentStage": None,
            "lastError": None,
            "stages": {},
            "selfTest": None,
        }

    def _load(self) -> dict[str, Any]:
        if not self.path.exists():
            return self._empty()
        try:
            loaded = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise InstallerError(f"Installation state is unreadable: {sanitize_error(error)}") from error
        if loaded.get("schemaVersion") != STATE_SCHEMA_VERSION or not isinstance(loaded.get("stages"), dict):
            raise InstallerError("Installation state has an unsupported schema.")
        return loaded

    def _record(self, stage: Stage) -> MutableMapping[str, Any]:
        stages = self.data.setdefault("stages", {})
        record = stages.setdefault(
            stage.value,
            {
                "status": "pending",
                "attempts": 0,
                "startedAt": None,
                "completedAt": None,
                "lastError": None,
                "probeSatisfied": False,
            },
        )
        return record

    def begin(self, stage: Stage) -> None:
        record = self._record(stage)
        record.update(
            status="running",
            attempts=int(record.get("attempts", 0)) + 1,
            startedAt=self.now(),
            completedAt=None,
            lastError=None,
            probeSatisfied=False,
        )
        self.data["currentStage"] = stage.value
        self.data["lastError"] = None
        self.save()

    def complete(self, stage: Stage, probe_satisfied: bool = False) -> None:
        record = self._record(stage)
        record.update(
            status="complete",
            completedAt=self.now(),
            lastError=None,
            probeSatisfied=bool(probe_satisfied),
        )
        self.data["currentStage"] = stage.value
        self.data["lastError"] = None
        self.save()

    def fail(self, stage: Stage, reason: object) -> None:
        safe_reason = sanitize_error(reason)
        record = self._record(stage)
        record.update(status="failed", completedAt=None, lastError=safe_reason, probeSatisfied=False)
        self.data["currentStage"] = stage.value
        self.data["lastError"] = {"stage": stage.value, "reason": safe_reason, "at": self.now()}
        self.save()

    def stage_status(self, stage: Stage) -> str:
        return str(self.data.get("stages", {}).get(stage.value, {}).get("status", "pending"))

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.data["updatedAt"] = self.now()
        payload = json.dumps(self.data, indent=2, sort_keys=True) + "\n"
        descriptor, temporary_name = tempfile.mkstemp(prefix=f".{self.path.name}.", dir=str(self.path.parent))
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.chmod(temporary_name, 0o600)
            os.replace(temporary_name, self.path)
            os.chmod(self.path, 0o600)
        finally:
            if os.path.exists(temporary_name):
                os.unlink(temporary_name)


@dataclass(frozen=True)
class StageDefinition:
    stage: Stage
    probe: Callable[[], bool]
    apply: Callable[[], None]
    always_run: bool = False


class StageRunner:
    """Probe-before-apply stage engine used by the privileged installer."""

    def __init__(
        self,
        ledger: StageLedger,
        emit: Callable[[Stage, str, str], None],
    ) -> None:
        self.ledger = ledger
        self.emit = emit

    def run(self, definitions: Iterable[StageDefinition]) -> None:
        for definition in definitions:
            stage = definition.stage
            try:
                already_satisfied = False if definition.always_run else bool(definition.probe())
                if already_satisfied:
                    self.emit(stage, "success", "Existing state passed the stage probe; no change was needed.")
                    self.ledger.complete(stage, probe_satisfied=True)
                    continue
                self.ledger.begin(stage)
                self.emit(stage, "running", "Stage started.")
                definition.apply()
                if not bool(definition.probe()):
                    raise InstallerError("The post-apply stage probe did not pass.")
                self.ledger.complete(stage)
                self.emit(stage, "success", "Stage completed.")
            except StageFailure:
                raise
            except Exception as error:  # noqa: BLE001 - converted to a safe stage failure
                reason = sanitize_error(error)
                self.ledger.fail(stage, reason)
                self.emit(stage, "error", reason)
                raise StageFailure(stage, reason) from error


@dataclass(frozen=True)
class PreflightSnapshot:
    os_id: str
    os_version: str
    architecture: str
    total_ram_bytes: int
    available_ram_bytes: int
    free_disk_bytes: int
    package_manager_healthy: bool
    package_manager_detail: str
    wireguard_service_active: bool
    wireguard_addresses: frozenset[str]
    port_owners: Mapping[int, str] = field(default_factory=dict)
    postgres_installed: bool = False
    postgres_public_listener: bool = False
    synapse_installed: bool = False
    managed_install: bool = False
    partial_paths: tuple[str, ...] = ()


@dataclass(frozen=True)
class PreflightReport:
    warnings: tuple[str, ...]
    facts: Mapping[str, Any]


def evaluate_preflight(snapshot: PreflightSnapshot, required_wireguard_address: str) -> PreflightReport:
    errors: list[str] = []
    warnings: list[str] = []
    try:
        required_ip = str(ipaddress.ip_address(required_wireguard_address))
    except ValueError as error:
        raise PreflightError(("The required WireGuard service address is invalid.",)) from error

    if snapshot.os_id.lower() != "ubuntu" or snapshot.os_version not in SUPPORTED_UBUNTU_VERSIONS:
        errors.append(
            "Private Chat supports Ubuntu 22.04 and 24.04 only; "
            f"detected {snapshot.os_id or 'unknown'} {snapshot.os_version or 'unknown'}."
        )
    if snapshot.architecture not in SUPPORTED_ARCHITECTURES:
        errors.append(
            "Private Chat supports aarch64 and x86_64 only; "
            f"detected {snapshot.architecture or 'unknown'}."
        )
    if snapshot.total_ram_bytes < MIN_TOTAL_RAM_BYTES:
        errors.append("At least 2 GiB total RAM is required for PostgreSQL and Synapse.")
    elif snapshot.total_ram_bytes < RECOMMENDED_TOTAL_RAM_BYTES:
        warnings.append("The node has less than the recommended 6 GiB RAM; installation will not resize it.")
    if snapshot.available_ram_bytes < MIN_AVAILABLE_RAM_BYTES:
        errors.append("At least 768 MiB RAM must be available before installation.")
    if snapshot.free_disk_bytes < MIN_FREE_DISK_BYTES:
        errors.append("At least 10 GiB free disk space is required before installation.")
    elif snapshot.free_disk_bytes < RECOMMENDED_FREE_DISK_BYTES:
        warnings.append("The node has less than the recommended 20 GiB free disk; installation will not resize it.")
    if not snapshot.package_manager_healthy:
        errors.append(
            "The Ubuntu package manager is not healthy: "
            f"{sanitize_error(snapshot.package_manager_detail)}"
        )
    if not snapshot.wireguard_service_active:
        errors.append("wg-quick@wg0 must be active before Private Chat is installed.")
    if required_ip not in snapshot.wireguard_addresses:
        errors.append(f"WireGuard does not own the required private address {required_ip}.")
    if snapshot.synapse_installed and not snapshot.managed_install:
        errors.append("An unmanaged Synapse installation already exists; automatic adoption is refused.")
    if snapshot.postgres_installed and snapshot.postgres_public_listener and not snapshot.managed_install:
        errors.append(
            "Existing PostgreSQL listens beyond loopback; automatic listener changes are refused."
        )

    for port, owner in sorted(snapshot.port_owners.items()):
        safe_owner = sanitize_error(owner, limit=120)
        if port == 5432 and snapshot.postgres_installed:
            warnings.append("PostgreSQL is already installed; the dedicated ZeroVPN database will be reconciled.")
            continue
        if snapshot.managed_install and port in {443, 8008, 5432}:
            continue
        errors.append(f"Required TCP port {port} is already in use by {safe_owner or 'another process'}.")

    if snapshot.partial_paths:
        warnings.append(
            "A partial prior Private Chat installation was detected and will be resumed: "
            + ", ".join(sorted(snapshot.partial_paths))
        )
    if snapshot.postgres_installed and not any("PostgreSQL is already" in item for item in warnings):
        warnings.append("PostgreSQL is already installed; its existing clusters will not be removed or recreated.")
    if snapshot.synapse_installed and snapshot.managed_install:
        warnings.append("The managed Synapse installation already exists and will be reconciled in place.")

    if errors:
        raise PreflightError(errors, warnings)
    return PreflightReport(
        warnings=tuple(dict.fromkeys(warnings)),
        facts={
            "os": f"{snapshot.os_id} {snapshot.os_version}",
            "architecture": snapshot.architecture,
            "totalRamBytes": snapshot.total_ram_bytes,
            "availableRamBytes": snapshot.available_ram_bytes,
            "freeDiskBytes": snapshot.free_disk_bytes,
            "postgresAlreadyInstalled": snapshot.postgres_installed,
            "synapseAlreadyInstalled": snapshot.synapse_installed,
            "partialInstallDetected": bool(snapshot.partial_paths),
            "wireguardAddress": required_ip,
        },
    )


_INTERFACE_NAME = re.compile(r"^[A-Za-z0-9_.-]{1,15}$")


def render_firewall_script(wireguard_interface: str, wireguard_address: str) -> str:
    """Render idempotent iptables rules without flushing ZeroVPN's base rules."""

    if not _INTERFACE_NAME.fullmatch(wireguard_interface):
        raise ValueError("Invalid WireGuard interface name.")
    address = ipaddress.ip_address(wireguard_address)
    if address.version != 4 or not address.is_private:
        raise ValueError("The Matrix service address must be a private IPv4 address.")
    network = ipaddress.ip_network(f"{address}/24", strict=False)
    return f"""#!/bin/sh
set -eu

IPTABLES=${{IPTABLES:-iptables}}
WG_INTERFACE='{wireguard_interface}'
WG_ADDRESS='{address}'
WG_NETWORK='{network}'

# Never flush a built-in chain: WireGuard owns its existing INPUT/FORWARD/NAT rules.
$IPTABLES -w -N ZEROVPN_PRIVATE_CHAT_INPUT 2>/dev/null || true
$IPTABLES -w -F ZEROVPN_PRIVATE_CHAT_INPUT
$IPTABLES -w -C INPUT -j ZEROVPN_PRIVATE_CHAT_INPUT 2>/dev/null || \
  $IPTABLES -w -I INPUT 1 -j ZEROVPN_PRIVATE_CHAT_INPUT
$IPTABLES -w -A ZEROVPN_PRIVATE_CHAT_INPUT -i "$WG_INTERFACE" -d "$WG_ADDRESS" \
  -p tcp --dport 443 -j ACCEPT
$IPTABLES -w -A ZEROVPN_PRIVATE_CHAT_INPUT ! -i "$WG_INTERFACE" -d "$WG_ADDRESS" \
  -p tcp --dport 443 -j DROP
$IPTABLES -w -A ZEROVPN_PRIVATE_CHAT_INPUT -i "$WG_INTERFACE" -p tcp \
  -m multiport --dports 5432,8008 -j REJECT
$IPTABLES -w -A ZEROVPN_PRIVATE_CHAT_INPUT -j RETURN

# These unattached policy chains are the Phase 1 boundary for future chat-only /32 peers.
# Enrollment must add a source-specific jump before ZeroVPN's broad owner forwarding rule.
$IPTABLES -w -N ZEROVPN_CHAT_PEER_INPUT 2>/dev/null || true
$IPTABLES -w -F ZEROVPN_CHAT_PEER_INPUT
$IPTABLES -w -A ZEROVPN_CHAT_PEER_INPUT -d "$WG_ADDRESS" -p tcp --dport 443 -j ACCEPT
$IPTABLES -w -A ZEROVPN_CHAT_PEER_INPUT -j REJECT

$IPTABLES -w -N ZEROVPN_CHAT_PEER_FORWARD 2>/dev/null || true
$IPTABLES -w -F ZEROVPN_CHAT_PEER_FORWARD
$IPTABLES -w -A ZEROVPN_CHAT_PEER_FORWARD -d 169.254.169.254/32 -j REJECT
$IPTABLES -w -A ZEROVPN_CHAT_PEER_FORWARD -d "$WG_NETWORK" -j REJECT
$IPTABLES -w -A ZEROVPN_CHAT_PEER_FORWARD -j REJECT
"""


def render_firewall_remove_script() -> str:
    return """#!/bin/sh
set -eu
IPTABLES=${IPTABLES:-iptables}
while $IPTABLES -w -C INPUT -j ZEROVPN_PRIVATE_CHAT_INPUT 2>/dev/null; do
  $IPTABLES -w -D INPUT -j ZEROVPN_PRIVATE_CHAT_INPUT
done
for chain in ZEROVPN_PRIVATE_CHAT_INPUT ZEROVPN_CHAT_PEER_INPUT ZEROVPN_CHAT_PEER_FORWARD; do
  $IPTABLES -w -F "$chain" 2>/dev/null || true
  $IPTABLES -w -X "$chain" 2>/dev/null || true
done
"""


_SERVER_NAME = re.compile(r"^node-[0-9a-f]{12}\.zerovpn$")
_TLS_PIN = re.compile(r"^sha256/[A-Za-z0-9+/]{43}=$")
_PROHIBITED_MANIFEST_KEYS = re.compile(
    r"(?i)(password|credential|private.?key|registration.?secret|access.?token|refresh.?token)"
)


def _walk_keys(value: Any) -> Iterable[str]:
    if isinstance(value, Mapping):
        for key, child in value.items():
            yield str(key)
            yield from _walk_keys(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_keys(child)


def validate_node_manifest(manifest: Mapping[str, Any]) -> None:
    """Validate the non-secret node manifest before writing or importing it."""

    errors: list[str] = []
    required = {
        "schemaVersion",
        "installerVersion",
        "nodeId",
        "serverName",
        "matrixPrivateUrl",
        "tlsSpkiSha256",
        "components",
        "installedAt",
        "health",
        "ownerMatrixUserId",
    }
    missing = sorted(required.difference(manifest.keys()))
    if missing:
        errors.append("missing fields: " + ", ".join(missing))
    if manifest.get("schemaVersion") != MANIFEST_SCHEMA_VERSION:
        errors.append("unsupported schemaVersion")
    try:
        uuid.UUID(str(manifest.get("nodeId", "")))
    except ValueError:
        errors.append("nodeId must be a UUID")
    server_name = str(manifest.get("serverName", ""))
    if not _SERVER_NAME.fullmatch(server_name):
        errors.append("serverName has an invalid stable-node format")
    parsed_url = urlparse(str(manifest.get("matrixPrivateUrl", "")))
    if (
        parsed_url.scheme != "https"
        or not parsed_url.hostname
        or parsed_url.username
        or parsed_url.password
        or parsed_url.port not in (None, 443)
        or parsed_url.path not in ("", "/")
        or parsed_url.query
        or parsed_url.fragment
    ):
        errors.append("matrixPrivateUrl must be an HTTPS URL without user information")
    else:
        try:
            matrix_ip = ipaddress.ip_address(parsed_url.hostname)
            if matrix_ip.version != 4 or not matrix_ip.is_private:
                errors.append("matrixPrivateUrl must use a private IPv4 address")
        except ValueError:
            errors.append("matrixPrivateUrl must use the WireGuard service IP")
    if not _TLS_PIN.fullmatch(str(manifest.get("tlsSpkiSha256", ""))):
        errors.append("tlsSpkiSha256 must be a base64 SHA-256 SPKI pin")
    owner = str(manifest.get("ownerMatrixUserId", ""))
    if owner != f"@owner:{server_name}":
        errors.append("ownerMatrixUserId does not match serverName")
    if not isinstance(manifest.get("components"), Mapping) or not manifest.get("components"):
        errors.append("components must be a non-empty object")
    health = manifest.get("health")
    if not isinstance(health, Mapping) or health.get("status") not in {"healthy", "degraded", "unhealthy"}:
        errors.append("health.status is invalid")
    prohibited = sorted(key for key in _walk_keys(manifest) if _PROHIBITED_MANIFEST_KEYS.search(key))
    if prohibited:
        errors.append("manifest contains prohibited secret-shaped fields: " + ", ".join(prohibited))
    if errors:
        raise ValueError("Invalid private-chat node manifest: " + "; ".join(errors))


def spki_pin_from_der(spki_der: bytes) -> str:
    import hashlib

    return "sha256/" + base64.b64encode(hashlib.sha256(spki_der).digest()).decode("ascii")

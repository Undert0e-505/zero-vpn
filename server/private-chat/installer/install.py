#!/usr/bin/env python3
"""Idempotent Ubuntu installer for ZeroVPN's optional private Matrix workload.

The installer owns only files, services, database objects, and firewall chains
under the ``zerovpn-private-chat`` name.  It never edits WireGuard peer state or
OCI resources, so a failed or removed chat workload leaves the VPN available.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import shutil
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

try:
    from .model import (
        INSTALLER_VERSION,
        MANIFEST_SCHEMA_VERSION,
        InstallerError,
        PreflightError,
        PreflightSnapshot,
        Stage,
        StageDefinition,
        StageFailure,
        StageLedger,
        StageRunner,
        evaluate_preflight,
        render_firewall_remove_script,
        render_firewall_script,
        sanitize_error,
        utc_timestamp,
        validate_node_manifest,
    )
except ImportError:  # Executed directly on the provisioned VM.
    from model import (  # type: ignore[no-redef]
        INSTALLER_VERSION,
        MANIFEST_SCHEMA_VERSION,
        InstallerError,
        PreflightError,
        PreflightSnapshot,
        Stage,
        StageDefinition,
        StageFailure,
        StageLedger,
        StageRunner,
        evaluate_preflight,
        render_firewall_remove_script,
        render_firewall_script,
        sanitize_error,
        utc_timestamp,
        validate_node_manifest,
    )


SOURCE_ROOT = Path(__file__).resolve().parent.parent
INSTALL_ROOT = Path("/opt/zerovpn/private-chat")
ETC_ROOT = Path("/etc/zerovpn/private-chat")
SECRETS_ROOT = ETC_ROOT / "secrets"
TLS_ROOT = ETC_ROOT / "tls"
SYNAPSE_ETC = ETC_ROOT / "synapse"
STATE_ROOT = Path("/var/lib/zerovpn/private-chat")
SYNAPSE_DATA = STATE_ROOT / "synapse"
STATE_FILE = STATE_ROOT / "install-state.json"
HEALTH_FILE = STATE_ROOT / "health.json"
OPTIONS_FILE = ETC_ROOT / "install-options.json"
MANIFEST_FILE = ETC_ROOT / "node.json"
OWNER_CREDENTIALS_FILE = SECRETS_ROOT / "owner-matrix.json"
POSTGRES_PASSWORD_FILE = SECRETS_ROOT / "postgres_password"
REGISTRATION_SECRET_FILE = SECRETS_ROOT / "registration_shared_secret"
NODE_ID_FILE = ETC_ROOT / "node-id"
SERVER_NAME_FILE = ETC_ROOT / "server-name"
MANAGED_MARKER = ETC_ROOT / "managed-by-installer"
LOG_ROOT = Path("/var/log/zerovpn/private-chat")
SYNAPSE_VENV = INSTALL_ROOT / "venv/synapse"
SELF_TEST_VENV = INSTALL_ROOT / "venv/self-test"
SYNAPSE_SERVICE = "zerovpn-private-chat-synapse.service"
FIREWALL_SERVICE = "zerovpn-private-chat-firewall.service"
SYNAPSE_UNIT_PATH = Path("/etc/systemd/system") / SYNAPSE_SERVICE
FIREWALL_UNIT_PATH = Path("/etc/systemd/system") / FIREWALL_SERVICE
NGINX_SITE_AVAILABLE = Path("/etc/nginx/sites-available/zerovpn-private-chat")
NGINX_SITE_ENABLED = Path("/etc/nginx/sites-enabled/zerovpn-private-chat")
POSTGRES_ROLE = "zerovpn_synapse"
POSTGRES_DATABASE = "zerovpn_synapse"
SYNAPSE_USER = "zerovpn-synapse"
LOCAL_MATRIX_URL = "http://127.0.0.1:8008"
REQUIRED_PORTS = (443, 5432, 8008)


@dataclass
class CommandResult:
    returncode: int
    stdout: str
    stderr: str


class StageLog:
    def __init__(self, stage: Stage) -> None:
        LOG_ROOT.mkdir(parents=True, exist_ok=True)
        os.chmod(LOG_ROOT, 0o700)
        self.path = LOG_ROOT / f"{stage.value.lower()}.log"
        self.handle = self.path.open("a", encoding="utf-8", newline="\n")
        os.chmod(self.path, 0o600)

    def write(self, message: object) -> None:
        self.handle.write(f"{utc_timestamp()} {sanitize_error(message, limit=4000)}\n")
        self.handle.flush()

    def close(self) -> None:
        self.handle.close()

    def __enter__(self) -> "StageLog":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


def run_command(
    label: str,
    command: Sequence[str],
    *,
    input_text: str | None = None,
    environment: Mapping[str, str] | None = None,
    check: bool = True,
    log: StageLog | None = None,
    timeout: int = 900,
) -> CommandResult:
    """Run a fixed command without ever logging arguments or standard input."""

    if log:
        log.write(f"command started: {label}")
    merged_environment = os.environ.copy()
    merged_environment.update({"LC_ALL": "C", "LANG": "C"})
    if environment:
        merged_environment.update(environment)
    try:
        completed = subprocess.run(
            list(command),
            input=input_text,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=merged_environment,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        if log:
            log.write(f"command could not run: {label}: {error}")
        raise InstallerError(f"{label} could not run.") from error
    result = CommandResult(completed.returncode, completed.stdout, completed.stderr)
    if log:
        log.write(f"command finished: {label}: exit={completed.returncode}")
        for stream_name, stream in (("stdout", completed.stdout), ("stderr", completed.stderr)):
            for line in stream.splitlines()[-80:]:
                if line.strip():
                    log.write(f"{label} {stream_name}: {line}")
    if check and completed.returncode != 0:
        raise InstallerError(f"{label} failed with exit code {completed.returncode}.")
    return result


def write_atomic(path: Path, content: str, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary_name, mode)
        os.replace(temporary_name, path)
        os.chmod(path, mode)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def write_json(path: Path, value: Mapping[str, Any], mode: int = 0o600) -> None:
    write_atomic(path, json.dumps(value, indent=2, sort_keys=True) + "\n", mode)


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise InstallerError(f"{path.name} is unreadable.") from error
    if not isinstance(value, dict):
        raise InstallerError(f"{path.name} does not contain a JSON object.")
    return value


def source_path(relative: str) -> Path:
    installed = INSTALL_ROOT / relative
    return installed if installed.exists() else SOURCE_ROOT / relative


def emit_event(stage: Stage, status: str, message: str) -> None:
    safe_message = sanitize_error(message)
    payload = {
        "stage": stage.value,
        "status": status,
        "message": safe_message,
        "timestamp": utc_timestamp(),
    }
    try:
        LOG_ROOT.mkdir(parents=True, exist_ok=True)
        LOG_ROOT.chmod(0o700)
        event_log = LOG_ROOT / f"{stage.value.lower()}.log"
        with event_log.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(f"{payload['timestamp']} event status={status} message={safe_message}\n")
        event_log.chmod(0o600)
    except OSError:
        # The durable ledger still records the stage. A log write failure must not
        # turn a successful removal into a WireGuard-affecting recovery action.
        pass
    print("ZEROVPN_PRIVATE_CHAT_EVENT " + json.dumps(payload, separators=(",", ":")), flush=True)


def require_root() -> None:
    if os.name != "posix" or os.geteuid() != 0:
        raise SystemExit("This command must run as root on Ubuntu.")


def read_os_release() -> dict[str, str]:
    values: dict[str, str] = {}
    path = Path("/etc/os-release")
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if "=" not in raw_line or raw_line.startswith("#"):
            continue
        key, value = raw_line.split("=", 1)
        values[key] = value.strip().strip('"')
    return values


def read_memory() -> tuple[int, int]:
    values: dict[str, int] = {}
    for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
        if ":" not in line:
            continue
        key, remainder = line.split(":", 1)
        match = re.search(r"(\d+)", remainder)
        if match:
            values[key] = int(match.group(1)) * 1024
    return values.get("MemTotal", 0), values.get("MemAvailable", 0)


def installed_package(package: str) -> bool:
    result = run_command(
        f"inspect {package} package",
        ["dpkg-query", "-W", "-f=${Status}", package],
        check=False,
        timeout=30,
    )
    return result.returncode == 0 and result.stdout.strip() == "install ok installed"


def package_manager_health() -> tuple[bool, str]:
    lock_paths = (
        "/var/lib/dpkg/lock-frontend",
        "/var/lib/dpkg/lock",
        "/var/lib/apt/lists/lock",
        "/var/cache/apt/archives/lock",
    )
    if shutil.which("fuser"):
        for lock_path in lock_paths:
            result = run_command("inspect apt lock", ["fuser", lock_path], check=False, timeout=10)
            if result.returncode == 0 and result.stdout.strip():
                return False, "another apt or dpkg process holds a package-manager lock"
    audit = run_command("audit dpkg", ["dpkg", "--audit"], check=False, timeout=30)
    if audit.returncode != 0 or audit.stdout.strip() or audit.stderr.strip():
        return False, "dpkg reports an incomplete package operation"
    check = run_command(
        "check apt dependencies",
        ["apt-get", "check", "-o", "Debug::NoLocking=true"],
        check=False,
        timeout=120,
    )
    if check.returncode != 0:
        return False, "apt-get check reported broken dependencies"
    return True, "ok"


def wireguard_addresses(interface: str) -> frozenset[str]:
    result = run_command(
        "inspect WireGuard addresses",
        ["ip", "-o", "-4", "address", "show", "dev", interface],
        check=False,
        timeout=30,
    )
    addresses = set()
    for match in re.finditer(r"\binet\s+([0-9.]+)/\d+", result.stdout):
        addresses.add(match.group(1))
    return frozenset(addresses)


def listening_port_owners() -> dict[int, str]:
    result = run_command("inspect listening ports", ["ss", "-H", "-ltnp"], check=False, timeout=30)
    owners: dict[int, str] = {}
    for line in result.stdout.splitlines():
        for port in REQUIRED_PORTS:
            if re.search(rf":{port}\s", line):
                owners[port] = line.split()[-1] if line.split() else "unknown process"
    return owners


def postgres_has_public_listener() -> bool:
    output = run_command("inspect PostgreSQL listener scope", ["ss", "-H", "-ltn"], check=False, timeout=30).stdout
    return any(marker in output for marker in ("0.0.0.0:5432", "[::]:5432", "*:5432"))


def partial_install_paths() -> tuple[str, ...]:
    candidates = (ETC_ROOT, STATE_FILE, SYNAPSE_UNIT_PATH, FIREWALL_UNIT_PATH, NGINX_SITE_AVAILABLE)
    return tuple(str(path) for path in candidates if path.exists())


def collect_preflight(interface: str) -> PreflightSnapshot:
    os_release = read_os_release()
    total_ram, available_ram = read_memory()
    disk = shutil.disk_usage("/")
    package_healthy, package_detail = package_manager_health()
    wg_service = run_command(
        "inspect WireGuard service",
        ["systemctl", "is-active", "--quiet", f"wg-quick@{interface}.service"],
        check=False,
        timeout=30,
    ).returncode == 0
    postgres_installed = installed_package("postgresql") or shutil.which("pg_isready") is not None
    synapse_installed = (
        (SYNAPSE_VENV / "bin/python").exists()
        or installed_package("matrix-synapse-py3")
        or installed_package("matrix-synapse")
    )
    return PreflightSnapshot(
        os_id=os_release.get("ID", ""),
        os_version=os_release.get("VERSION_ID", ""),
        architecture=os.uname().machine,
        total_ram_bytes=total_ram,
        available_ram_bytes=available_ram,
        free_disk_bytes=disk.free,
        package_manager_healthy=package_healthy,
        package_manager_detail=package_detail,
        wireguard_service_active=wg_service,
        wireguard_addresses=wireguard_addresses(interface),
        port_owners=listening_port_owners(),
        postgres_installed=postgres_installed,
        postgres_public_listener=postgres_installed and postgres_has_public_listener(),
        synapse_installed=synapse_installed,
        managed_install=MANAGED_MARKER.exists(),
        partial_paths=partial_install_paths(),
    )


def copy_source_bundle() -> None:
    if SOURCE_ROOT.resolve() == INSTALL_ROOT.resolve():
        return
    INSTALL_ROOT.mkdir(parents=True, exist_ok=True)
    shutil.copytree(
        SOURCE_ROOT,
        INSTALL_ROOT,
        dirs_exist_ok=True,
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", ".pytest_cache", ".runtime"),
    )
    for path in INSTALL_ROOT.rglob("*.py"):
        path.chmod(0o755 if path.name in {"install.py", "render-runtime-config.py"} else 0o644)
    for path in INSTALL_ROOT.rglob("*.sh"):
        path.chmod(0o755)


def ensure_private_directories() -> None:
    for path, mode in (
        (ETC_ROOT, 0o755),
        (SECRETS_ROOT, 0o700),
        (TLS_ROOT, 0o700),
        (SYNAPSE_ETC, 0o755),
        (STATE_ROOT, 0o711),
        (LOG_ROOT, 0o700),
    ):
        path.mkdir(parents=True, exist_ok=True)
        path.chmod(mode)


def ensure_secret(path: Path, byte_count: int = 48) -> str:
    if path.exists():
        value = path.read_text(encoding="utf-8").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,}", value):
            raise InstallerError(f"Existing {path.name} is invalid; automatic rotation is refused.")
        path.chmod(0o600)
        return value
    value = secrets.token_urlsafe(byte_count)
    write_atomic(path, value + "\n", 0o600)
    return value


def ensure_node_identity() -> tuple[str, str]:
    if NODE_ID_FILE.exists() != SERVER_NAME_FILE.exists():
        raise InstallerError("The stable node identity is partial; automatic replacement is refused.")
    if NODE_ID_FILE.exists():
        node_id = NODE_ID_FILE.read_text(encoding="utf-8").strip()
        server_name = SERVER_NAME_FILE.read_text(encoding="utf-8").strip()
        try:
            uuid.UUID(node_id)
        except ValueError as error:
            raise InstallerError("The stable node ID is invalid.") from error
        expected = f"node-{uuid.UUID(node_id).hex[:12]}.zerovpn"
        if server_name != expected:
            raise InstallerError("The stable Matrix server name does not match the node ID.")
        return node_id, server_name
    node_id = str(uuid.uuid4())
    server_name = f"node-{uuid.UUID(node_id).hex[:12]}.zerovpn"
    write_atomic(NODE_ID_FILE, node_id + "\n", 0o600)
    write_atomic(SERVER_NAME_FILE, server_name + "\n", 0o600)
    return node_id, server_name


def postgres_cluster_config_directory() -> Path:
    result = run_command(
        "locate PostgreSQL cluster",
        ["pg_lsclusters", "--no-header"],
        check=True,
        timeout=30,
    )
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 2:
            return Path("/etc/postgresql") / parts[0] / parts[1]
    raise InstallerError("No PostgreSQL cluster was found after package installation.")


def psql_scalar(sql: str, *, database: str = "postgres", log: StageLog | None = None) -> str:
    result = run_command(
        "query PostgreSQL",
        ["runuser", "-u", "postgres", "--", "psql", "--no-psqlrc", "-At", "-d", database],
        input_text=sql + "\n",
        check=True,
        log=log,
        timeout=60,
    )
    return result.stdout.strip()


def postgres_ready() -> bool:
    if not shutil.which("pg_isready"):
        return False
    ready = run_command(
        "probe PostgreSQL",
        ["pg_isready", "-h", "127.0.0.1", "-p", "5432"],
        check=False,
        timeout=15,
    ).returncode == 0
    if not ready:
        return False
    try:
        database = psql_scalar(f"SELECT 1 FROM pg_database WHERE datname='{POSTGRES_DATABASE}';") == "1"
        role = psql_scalar(f"SELECT 1 FROM pg_roles WHERE rolname='{POSTGRES_ROLE}';") == "1"
        listeners = run_command("probe PostgreSQL listeners", ["ss", "-H", "-ltn"], check=False).stdout
        public_listener = any(
            marker in listeners
            for marker in ("0.0.0.0:5432", "[::]:5432", "*:5432")
        )
        return database and role and not public_listener and POSTGRES_PASSWORD_FILE.exists()
    except InstallerError:
        return False


def wait_for_url(url: str, attempts: int = 90, cafile: Path | None = None) -> dict[str, Any]:
    context = ssl.create_default_context(cafile=str(cafile)) if cafile else None
    last_error: Exception | None = None
    for _ in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "ZeroVPN-Private-Chat-Health/1"})
            with urllib.request.urlopen(request, timeout=3, context=context) as response:
                payload = json.loads(response.read().decode("utf-8"))
                if isinstance(payload, dict):
                    return payload
        except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
            last_error = error
            time.sleep(1)
    raise InstallerError(f"The endpoint did not become healthy: {sanitize_error(last_error)}")


def systemd_active(unit: str) -> bool:
    return run_command(
        f"probe {unit}",
        ["systemctl", "is-active", "--quiet", unit],
        check=False,
        timeout=30,
    ).returncode == 0


def synapse_ready() -> bool:
    if not systemd_active(SYNAPSE_SERVICE):
        return False
    try:
        versions = wait_for_url(f"{LOCAL_MATRIX_URL}/_matrix/client/versions", attempts=1)
    except InstallerError:
        return False
    return isinstance(versions.get("versions"), list) and bool(versions["versions"])


def tls_spki_pin() -> str:
    certificate = TLS_ROOT / "node.crt"
    if not certificate.exists():
        raise InstallerError("The private TLS certificate is missing.")
    pipeline = (
        f"openssl x509 -in {certificate} -pubkey -noout | "
        "openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64 -w0"
    )
    result = run_command("calculate TLS SPKI pin", ["sh", "-c", pipeline], check=True, timeout=30)
    encoded = result.stdout.strip()
    if not re.fullmatch(r"[A-Za-z0-9+/]{43}=", encoded):
        raise InstallerError("The private TLS SPKI fingerprint could not be calculated.")
    return "sha256/" + encoded


def tls_ready(address: str) -> bool:
    certificate = TLS_ROOT / "node.crt"
    key = TLS_ROOT / "node.key"
    if not certificate.exists() or not key.exists() or stat.S_IMODE(key.stat().st_mode) != 0o600:
        return False
    try:
        payload = wait_for_url(
            f"https://{address}/_matrix/client/versions",
            attempts=1,
            cafile=certificate,
        )
        return bool(payload.get("versions")) and tls_spki_pin().startswith("sha256/")
    except InstallerError:
        return False


def register_matrix_user(username: str, password: str, admin: bool) -> str:
    shared_secret = REGISTRATION_SECRET_FILE.read_text(encoding="utf-8").strip().encode("utf-8")
    endpoint = f"{LOCAL_MATRIX_URL}/_synapse/admin/v1/register"
    try:
        with urllib.request.urlopen(endpoint, timeout=10) as response:
            nonce = json.loads(response.read().decode("utf-8"))["nonce"]
        mac = hmac.new(shared_secret, digestmod=hashlib.sha1)
        mac.update(str(nonce).encode("utf-8"))
        mac.update(b"\x00")
        mac.update(username.encode("utf-8"))
        mac.update(b"\x00")
        mac.update(password.encode("utf-8"))
        mac.update(b"\x00")
        mac.update(b"admin" if admin else b"notadmin")
        body = json.dumps(
            {
                "nonce": nonce,
                "username": username,
                "displayname": "ZeroVPN Owner" if admin else username,
                "password": password,
                "admin": admin,
                "mac": mac.hexdigest(),
            }
        ).encode("utf-8")
        request = urllib.request.Request(endpoint, data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (KeyError, OSError, urllib.error.URLError, json.JSONDecodeError) as error:
        raise InstallerError("Synapse rejected local shared-secret account creation.") from error
    user_id = str(payload.get("user_id", ""))
    if not user_id.startswith(f"@{username}:"):
        raise InstallerError("Synapse returned an unexpected Matrix user ID.")
    return user_id


def owner_exists(server_name: str) -> bool:
    try:
        query = (
            "SELECT 1 FROM users WHERE name='"
            + f"@owner:{server_name}"
            + "' AND deactivated=0 AND admin=1;"
        )
        return psql_scalar(query, database=POSTGRES_DATABASE) == "1"
    except InstallerError:
        return False


def component_versions() -> dict[str, str]:
    versions: dict[str, str] = {}
    postgres = run_command(
        "read PostgreSQL version",
        ["psql", "--version"],
        check=False,
        timeout=30,
    )
    if postgres.returncode == 0:
        versions["postgresql"] = postgres.stdout.strip()
    synapse_python = SYNAPSE_VENV / "bin/python"
    if synapse_python.exists():
        synapse = run_command(
            "read Synapse version",
            [str(synapse_python), "-c", "import importlib.metadata; print(importlib.metadata.version('matrix-synapse'))"],
            check=False,
            timeout=30,
        )
        if synapse.returncode == 0:
            versions["synapse"] = synapse.stdout.strip()
    nginx = run_command("read nginx version", ["nginx", "-v"], check=False, timeout=30)
    if nginx.returncode == 0:
        versions["nginx"] = (nginx.stderr or nginx.stdout).strip()
    nio_python = SELF_TEST_VENV / "bin/python"
    if nio_python.exists():
        nio = run_command(
            "read Matrix self-test client version",
            [str(nio_python), "-c", "import importlib.metadata; print(importlib.metadata.version('matrix-nio'))"],
            check=False,
            timeout=30,
        )
        if nio.returncode == 0:
            versions["matrixNioSelfTest"] = nio.stdout.strip()
        olm = run_command(
            "read Matrix self-test crypto version",
            [str(nio_python), "-c", "import importlib.metadata; print(importlib.metadata.version('python-olm'))"],
            check=False,
            timeout=30,
        )
        if olm.returncode == 0:
            versions["pythonOlmSelfTest"] = olm.stdout.strip()
    versions["installer"] = INSTALLER_VERSION
    return versions


def owner_account_credentials() -> dict[str, Any]:
    credentials = read_json(OWNER_CREDENTIALS_FILE)
    if not isinstance(credentials.get("password"), str) or len(credentials["password"]) < 32:
        raise InstallerError("The owner Matrix credential file is invalid.")
    return credentials


def collect_health(address: str, ledger: StageLedger | None = None) -> dict[str, Any]:
    checks: dict[str, Any] = {}
    postgres_process = systemd_active("postgresql.service")
    postgres_check = postgres_ready()
    synapse_process = systemd_active(SYNAPSE_SERVICE)
    matrix_versions = synapse_ready()
    tls_endpoint = tls_ready(address)
    disk = shutil.disk_usage("/")
    disk_percent = round((disk.used / disk.total) * 100, 1) if disk.total else 100.0
    server_name = SERVER_NAME_FILE.read_text(encoding="utf-8").strip() if SERVER_NAME_FILE.exists() else ""
    owner_account = bool(server_name) and owner_exists(server_name)
    listener_output = run_command("inspect Matrix listeners", ["ss", "-H", "-ltn"], check=False).stdout
    private_listener = f"{address}:443" in listener_output
    no_public_listener = not any(marker in listener_output for marker in ("0.0.0.0:443", "[::]:443", "*:443"))
    self_test = ledger.data.get("selfTest") if ledger else None
    self_test_ok = isinstance(self_test, dict) and self_test.get("status") == "success"
    checks.update(
        postgresqlProcess={"ok": postgres_process},
        postgresqlConnection={"ok": postgres_check},
        synapseProcess={"ok": synapse_process},
        matrixVersions={"ok": matrix_versions, "url": f"{LOCAL_MATRIX_URL}/_matrix/client/versions"},
        privateTls={"ok": tls_endpoint, "url": f"https://{address}/_matrix/client/versions"},
        privateListener={"ok": private_listener and no_public_listener},
        disk={"ok": disk_percent < 95.0, "usedPercent": disk_percent, "freeBytes": disk.free},
        ownerAccount={"ok": owner_account, "userId": f"@owner:{server_name}" if server_name else None},
        encryptionSelfTest={
            "ok": self_test_ok,
            "checkedAt": self_test.get("checkedAt") if isinstance(self_test, dict) else None,
        },
    )
    critical = (
        postgres_process,
        postgres_check,
        synapse_process,
        matrix_versions,
        tls_endpoint,
        private_listener and no_public_listener,
        owner_account,
    )
    status = "unhealthy" if not all(critical) else ("degraded" if disk_percent >= 85.0 else "healthy")
    return {"status": status, "checkedAt": utc_timestamp(), "checks": checks}


class InstallerContext:
    def __init__(
        self,
        interface: str,
        address: str,
        ledger: StageLedger,
        initial_preflight: PreflightSnapshot | None = None,
    ) -> None:
        self.interface = interface
        self.address = str(ipaddress.ip_address(address))
        self.ledger = ledger
        self.initial_preflight = initial_preflight
        self.last_preflight: dict[str, Any] | None = None
        self.last_health: dict[str, Any] | None = None

    def apply_precheck(self) -> None:
        snapshot = self.initial_preflight or collect_preflight(self.interface)
        report = evaluate_preflight(snapshot, self.address)
        self.last_preflight = {"warnings": list(report.warnings), "facts": dict(report.facts)}
        for warning in report.warnings:
            emit_event(Stage.PRECHECK, "warning", warning)
        ensure_private_directories()
        write_json(
            OPTIONS_FILE,
            {
                "schemaVersion": 1,
                "wireguardInterface": self.interface,
                "wireguardAddress": self.address,
                "matrixPort": 443,
                "updatedAt": utc_timestamp(),
            },
        )

    def probe_precheck(self) -> bool:
        report = evaluate_preflight(collect_preflight(self.interface), self.address)
        self.last_preflight = {"warnings": list(report.warnings), "facts": dict(report.facts)}
        return True

    def apply_packages(self) -> None:
        with StageLog(Stage.PACKAGES) as log:
            run_command(
                "refresh Ubuntu package indexes",
                ["apt-get", "update", "-o", "Acquire::Retries=3"],
                environment={"DEBIAN_FRONTEND": "noninteractive"},
                log=log,
                timeout=900,
            )
            packages = [
                "build-essential",
                "ca-certificates",
                "curl",
                "iproute2",
                "iptables",
                "libffi-dev",
                "libjpeg-dev",
                "libolm-dev",
                "libpq-dev",
                "libssl-dev",
                "libxml2-dev",
                "libxslt1-dev",
                "nginx",
                "openssl",
                "postgresql",
                "postgresql-client",
                "python3-dev",
                "python3-pip",
                "python3-venv",
            ]
            run_command(
                "install private-chat system packages",
                ["apt-get", "install", "-y", "--no-install-recommends", *packages],
                environment={"DEBIAN_FRONTEND": "noninteractive"},
                log=log,
                timeout=1800,
            )
        copy_source_bundle()
        ensure_private_directories()
        write_atomic(MANAGED_MARKER, f"installerVersion={INSTALLER_VERSION}\n", 0o600)

    def probe_packages(self) -> bool:
        commands = ("curl", "iptables", "nginx", "openssl", "pg_isready", "psql", "python3")
        return all(shutil.which(command) for command in commands) and (INSTALL_ROOT / "installer/model.py").exists()

    def apply_postgres(self) -> None:
        with StageLog(Stage.POSTGRES) as log:
            password = ensure_secret(POSTGRES_PASSWORD_FILE)
            config_directory = postgres_cluster_config_directory()
            drop_in = config_directory / "conf.d/90-zerovpn-private-chat.conf"
            write_atomic(
                drop_in,
                source_path("postgres/90-zerovpn-private-chat.conf.template").read_text(encoding="utf-8"),
                0o644,
            )
            run_command("restart PostgreSQL", ["systemctl", "restart", "postgresql.service"], log=log)
            run_command("enable PostgreSQL", ["systemctl", "enable", "postgresql.service"], log=log)
            role_exists = psql_scalar(f"SELECT 1 FROM pg_roles WHERE rolname='{POSTGRES_ROLE}';", log=log) == "1"
            if not role_exists:
                psql_scalar(f"CREATE ROLE {POSTGRES_ROLE} LOGIN;", log=log)
            # token_urlsafe output cannot contain a SQL quote, backslash, or newline.
            psql_scalar(
                f"ALTER ROLE {POSTGRES_ROLE} WITH LOGIN PASSWORD '{password}';",
                log=log,
            )
            database_exists = psql_scalar(
                f"SELECT 1 FROM pg_database WHERE datname='{POSTGRES_DATABASE}';",
                log=log,
            ) == "1"
            if not database_exists:
                run_command(
                    "create Synapse PostgreSQL database",
                    [
                        "runuser",
                        "-u",
                        "postgres",
                        "--",
                        "createdb",
                        "--encoding=UTF8",
                        "--locale=C",
                        "--template=template0",
                        f"--owner={POSTGRES_ROLE}",
                        POSTGRES_DATABASE,
                    ],
                    log=log,
                    timeout=120,
                )
            psql_scalar(
                f"ALTER DATABASE {POSTGRES_DATABASE} OWNER TO {POSTGRES_ROLE}; "
                f"REVOKE ALL ON DATABASE {POSTGRES_DATABASE} FROM PUBLIC; "
                f"GRANT ALL ON DATABASE {POSTGRES_DATABASE} TO {POSTGRES_ROLE};",
                log=log,
            )

    def probe_postgres(self) -> bool:
        return postgres_ready()

    def apply_synapse(self) -> None:
        with StageLog(Stage.SYNAPSE) as log:
            ensure_private_directories()
            _, server_name = ensure_node_identity()
            ensure_secret(REGISTRATION_SECRET_FILE)
            if run_command("inspect Synapse service account", ["id", "-u", SYNAPSE_USER], check=False).returncode != 0:
                run_command(
                    "create Synapse service account",
                    [
                        "useradd",
                        "--system",
                        "--home-dir",
                        str(SYNAPSE_DATA),
                        "--shell",
                        "/usr/sbin/nologin",
                        SYNAPSE_USER,
                    ],
                    log=log,
                )
            SYNAPSE_DATA.mkdir(parents=True, exist_ok=True)
            (SYNAPSE_DATA / "media_store").mkdir(parents=True, exist_ok=True)
            run_command(
                "set Synapse data ownership",
                ["chown", "-R", f"{SYNAPSE_USER}:{SYNAPSE_USER}", str(SYNAPSE_DATA)],
                log=log,
            )
            if not (SYNAPSE_VENV / "bin/python").exists():
                SYNAPSE_VENV.parent.mkdir(parents=True, exist_ok=True)
                run_command("create Synapse virtual environment", ["python3", "-m", "venv", str(SYNAPSE_VENV)], log=log)
            run_command(
                "install pinned Synapse",
                [
                    str(SYNAPSE_VENV / "bin/python"),
                    "-m",
                    "pip",
                    "install",
                    "--disable-pip-version-check",
                    "--no-input",
                    "--no-cache-dir",
                    "--requirement",
                    str(source_path("synapse/requirements.txt")),
                ],
                log=log,
                timeout=1800,
            )
            template = source_path("synapse/homeserver.yaml.template").read_text(encoding="utf-8")
            template = template.replace("__SERVER_NAME__", server_name).replace("__WIREGUARD_ADDRESS__", self.address)
            if "__SERVER_NAME__" in template or "__WIREGUARD_ADDRESS__" in template:
                raise InstallerError("The Synapse configuration template was not fully rendered.")
            write_atomic(SYNAPSE_ETC / "homeserver.yaml.template", template, 0o644)
            shutil.copyfile(source_path("synapse/log.config"), SYNAPSE_ETC / "log.config")
            (SYNAPSE_ETC / "log.config").chmod(0o644)
            renderer = source_path("synapse/render-runtime-config.py")
            installed_renderer = INSTALL_ROOT / "synapse/render-runtime-config.py"
            if renderer.resolve() != installed_renderer.resolve():
                shutil.copyfile(renderer, installed_renderer)
            installed_renderer.chmod(0o755)
            shutil.copyfile(
                source_path("synapse/zerovpn-private-chat-synapse.service.template"),
                SYNAPSE_UNIT_PATH,
            )
            SYNAPSE_UNIT_PATH.chmod(0o644)
            run_command("reload systemd units", ["systemctl", "daemon-reload"], log=log)
            run_command("enable Synapse", ["systemctl", "enable", SYNAPSE_SERVICE], log=log)
            run_command("restart Synapse", ["systemctl", "restart", SYNAPSE_SERVICE], log=log, timeout=240)
            wait_for_url(f"{LOCAL_MATRIX_URL}/_matrix/client/versions", attempts=120)
            signing_key = SYNAPSE_DATA / "signing.key"
            if not signing_key.exists():
                raise InstallerError("Synapse did not generate its stable signing key.")
            signing_key.chmod(0o600)

    def probe_synapse(self) -> bool:
        return (
            synapse_ready()
            and (SYNAPSE_DATA / "signing.key").exists()
            and stat.S_IMODE((SYNAPSE_DATA / "signing.key").stat().st_mode) == 0o600
        )

    def apply_tls(self) -> None:
        with StageLog(Stage.TLS) as log:
            _, server_name = ensure_node_identity()
            key = TLS_ROOT / "node.key"
            certificate = TLS_ROOT / "node.crt"
            TLS_ROOT.mkdir(parents=True, exist_ok=True)
            TLS_ROOT.chmod(0o700)
            if certificate.exists() and not key.exists():
                raise InstallerError("The TLS certificate exists without its private key; explicit recovery is required.")
            if not key.exists():
                run_command(
                    "generate private TLS identity",
                    [
                        "openssl",
                        "req",
                        "-x509",
                        "-newkey",
                        "ec",
                        "-pkeyopt",
                        "ec_paramgen_curve:P-256",
                        "-sha256",
                        "-days",
                        "825",
                        "-nodes",
                        "-subj",
                        f"/CN={server_name}",
                        "-addext",
                        f"subjectAltName=DNS:{server_name},IP:{self.address}",
                        "-addext",
                        "basicConstraints=critical,CA:FALSE",
                        "-addext",
                        "keyUsage=critical,digitalSignature,keyEncipherment",
                        "-addext",
                        "extendedKeyUsage=serverAuth",
                        "-keyout",
                        str(key),
                        "-out",
                        str(certificate),
                    ],
                    log=log,
                    timeout=120,
                )
            elif not certificate.exists():
                run_command(
                    "reissue private TLS certificate with the existing key",
                    [
                        "openssl",
                        "req",
                        "-x509",
                        "-new",
                        "-key",
                        str(key),
                        "-sha256",
                        "-days",
                        "825",
                        "-subj",
                        f"/CN={server_name}",
                        "-addext",
                        f"subjectAltName=DNS:{server_name},IP:{self.address}",
                        "-addext",
                        "basicConstraints=critical,CA:FALSE",
                        "-addext",
                        "keyUsage=critical,digitalSignature,keyEncipherment",
                        "-addext",
                        "extendedKeyUsage=serverAuth",
                        "-out",
                        str(certificate),
                    ],
                    log=log,
                )
            key.chmod(0o600)
            certificate.chmod(0o644)
            nginx = source_path("synapse/nginx-private-chat.conf.template").read_text(encoding="utf-8")
            nginx = nginx.replace("__SERVER_NAME__", server_name).replace("__WIREGUARD_ADDRESS__", self.address)
            write_atomic(NGINX_SITE_AVAILABLE, nginx, 0o644)
            if NGINX_SITE_ENABLED.is_symlink() or NGINX_SITE_ENABLED.exists():
                if NGINX_SITE_ENABLED.resolve() != NGINX_SITE_AVAILABLE.resolve():
                    raise InstallerError("The ZeroVPN nginx site name is already owned by another file.")
            else:
                NGINX_SITE_ENABLED.symlink_to(NGINX_SITE_AVAILABLE)
            default_site = Path("/etc/nginx/sites-enabled/default")
            if default_site.is_symlink() and default_site.resolve() == Path("/etc/nginx/sites-available/default"):
                default_site.unlink()
            run_command("validate nginx configuration", ["nginx", "-t"], log=log)
            run_command("enable nginx", ["systemctl", "enable", "nginx.service"], log=log)
            run_command("restart nginx", ["systemctl", "restart", "nginx.service"], log=log)
            wait_for_url(
                f"https://{self.address}/_matrix/client/versions",
                attempts=60,
                cafile=certificate,
            )

    def probe_tls(self) -> bool:
        return tls_ready(self.address)

    def apply_firewall(self) -> None:
        with StageLog(Stage.FIREWALL) as log:
            firewall_root = INSTALL_ROOT / "firewall"
            firewall_root.mkdir(parents=True, exist_ok=True)
            write_atomic(firewall_root / "apply.sh", render_firewall_script(self.interface, self.address), 0o755)
            write_atomic(firewall_root / "remove.sh", render_firewall_remove_script(), 0o755)
            shutil.copyfile(
                source_path("firewall/zerovpn-private-chat-firewall.service.template"),
                FIREWALL_UNIT_PATH,
            )
            FIREWALL_UNIT_PATH.chmod(0o644)
            run_command("reload firewall systemd unit", ["systemctl", "daemon-reload"], log=log)
            run_command("enable private-chat firewall", ["systemctl", "enable", FIREWALL_SERVICE], log=log)
            run_command("restart private-chat firewall", ["systemctl", "restart", FIREWALL_SERVICE], log=log)

    def probe_firewall(self) -> bool:
        jump = run_command(
            "probe private-chat firewall jump",
            ["iptables", "-w", "-C", "INPUT", "-j", "ZEROVPN_PRIVATE_CHAT_INPUT"],
            check=False,
            timeout=30,
        ).returncode == 0
        allow = run_command(
            "probe private-chat TLS firewall rule",
            [
                "iptables",
                "-w",
                "-C",
                "ZEROVPN_PRIVATE_CHAT_INPUT",
                "-i",
                self.interface,
                "-d",
                self.address,
                "-p",
                "tcp",
                "--dport",
                "443",
                "-j",
                "ACCEPT",
            ],
            check=False,
            timeout=30,
        ).returncode == 0
        return jump and allow and systemd_active(FIREWALL_SERVICE)

    def apply_owner_account(self) -> None:
        with StageLog(Stage.OWNER_ACCOUNT) as log:
            _, server_name = ensure_node_identity()
            expected_user_id = f"@owner:{server_name}"
            if OWNER_CREDENTIALS_FILE.exists():
                credentials = owner_account_credentials()
                if credentials.get("userId") != expected_user_id:
                    raise InstallerError("The stored owner account does not match the stable server name.")
            elif owner_exists(server_name):
                raise InstallerError("The owner account exists but its root-only credential file is missing.")
            else:
                credentials = {
                    "schemaVersion": 1,
                    "userId": expected_user_id,
                    "password": secrets.token_urlsafe(48),
                    "matrixPrivateUrl": f"https://{self.address}",
                    "tlsSpkiSha256": tls_spki_pin(),
                    "createdAt": utc_timestamp(),
                }
                write_json(OWNER_CREDENTIALS_FILE, credentials, 0o600)
            if not owner_exists(server_name):
                registered = register_matrix_user("owner", str(credentials["password"]), admin=True)
                if registered != expected_user_id:
                    raise InstallerError("Synapse created an unexpected owner Matrix ID.")
            log.write("Owner Matrix account exists and is an administrator; credentials were not logged.")

    def probe_owner_account(self) -> bool:
        if not OWNER_CREDENTIALS_FILE.exists() or stat.S_IMODE(OWNER_CREDENTIALS_FILE.stat().st_mode) != 0o600:
            return False
        try:
            _, server_name = ensure_node_identity()
            credentials = owner_account_credentials()
            return credentials.get("userId") == f"@owner:{server_name}" and owner_exists(server_name)
        except InstallerError:
            return False

    def apply_health(self) -> None:
        self.last_health = collect_health(self.address, self.ledger)
        write_json(HEALTH_FILE, self.last_health, 0o600)
        if self.last_health["status"] == "unhealthy":
            failed = [name for name, value in self.last_health["checks"].items() if not value.get("ok")]
            raise InstallerError("Node health checks failed: " + ", ".join(failed))
        if self.last_health["status"] == "degraded":
            emit_event(Stage.HEALTH, "warning", "Node health is degraded; inspect disk usage before relying on chat.")

    def probe_health(self) -> bool:
        self.last_health = collect_health(self.address, self.ledger)
        return self.last_health["status"] != "unhealthy"

    def apply_encryption_self_test(self) -> None:
        with StageLog(Stage.ENCRYPTION_SELF_TEST) as log:
            if not (SELF_TEST_VENV / "bin/python").exists():
                SELF_TEST_VENV.parent.mkdir(parents=True, exist_ok=True)
                run_command(
                    "create encrypted self-test virtual environment",
                    ["python3", "-m", "venv", str(SELF_TEST_VENV)],
                    log=log,
                )
            run_command(
                "install pinned encrypted self-test client",
                [
                    str(SELF_TEST_VENV / "bin/python"),
                    "-m",
                    "pip",
                    "install",
                    "--disable-pip-version-check",
                    "--no-input",
                    "--no-cache-dir",
                    "--requirement",
                    str(source_path("synapse/self-test-requirements.txt")),
                ],
                log=log,
                timeout=1800,
            )
            result = run_command(
                "run real encrypted Matrix self-test",
                [str(SELF_TEST_VENV / "bin/python"), str(source_path("tests/encrypted_self_test.py"))],
                environment={
                    "ZEROVPN_MATRIX_LOCAL_URL": LOCAL_MATRIX_URL,
                    "ZEROVPN_MATRIX_OWNER_CREDENTIALS": str(OWNER_CREDENTIALS_FILE),
                    "ZEROVPN_MATRIX_REGISTRATION_SECRET": str(REGISTRATION_SECRET_FILE),
                },
                check=False,
                log=log,
                timeout=300,
            )
            if result.returncode != 0:
                failure_prefix = "ZEROVPN_E2EE_FAILURE "
                failure_line = next(
                    (line for line in reversed(result.stdout.splitlines()) if line.startswith(failure_prefix)),
                    None,
                )
                reason = "The real encrypted Matrix exchange failed."
                if failure_line is not None:
                    try:
                        parsed_failure = json.loads(failure_line[len(failure_prefix) :])
                        reason = sanitize_error(parsed_failure.get("reason", reason))
                    except json.JSONDecodeError:
                        pass
                raise InstallerError(reason)
            marker = "ZEROVPN_E2EE_RESULT "
            evidence_line = next((line for line in reversed(result.stdout.splitlines()) if line.startswith(marker)), None)
            if evidence_line is None:
                raise InstallerError("The encrypted self-test returned no machine-readable evidence.")
            try:
                evidence = json.loads(evidence_line[len(marker) :])
            except json.JSONDecodeError as error:
                raise InstallerError("The encrypted self-test evidence was invalid.") from error
            required_true = (
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
            if evidence.get("status") != "success" or not all(evidence.get(key) is True for key in required_true):
                raise InstallerError("The encrypted self-test did not satisfy every ciphertext and cleanup assertion.")
            versions = component_versions()
            self.ledger.data["selfTest"] = {
                "status": "success",
                "checkedAt": utc_timestamp(),
                "synapseVersion": versions.get("synapse"),
                "matrixNioVersion": versions.get("matrixNioSelfTest"),
                **{key: True for key in required_true},
            }
            self.ledger.save()

    def probe_encryption_self_test(self) -> bool:
        evidence = self.ledger.data.get("selfTest")
        if not isinstance(evidence, dict) or evidence.get("status") != "success":
            return False
        versions = component_versions()
        return (
            evidence.get("synapseVersion") == versions.get("synapse")
            and evidence.get("matrixNioVersion") == versions.get("matrixNioSelfTest")
        )

    def build_manifest(self) -> dict[str, Any]:
        node_id, server_name = ensure_node_identity()
        existing_installed_at = None
        if MANIFEST_FILE.exists():
            existing_installed_at = read_json(MANIFEST_FILE).get("installedAt")
        health = collect_health(self.address, self.ledger)
        self.last_health = health
        manifest = {
            "schemaVersion": MANIFEST_SCHEMA_VERSION,
            "installerVersion": INSTALLER_VERSION,
            "nodeId": node_id,
            "serverName": server_name,
            "matrixPrivateUrl": f"https://{self.address}",
            "tlsSpkiSha256": tls_spki_pin(),
            "components": component_versions(),
            "installedAt": existing_installed_at or utc_timestamp(),
            "updatedAt": utc_timestamp(),
            "health": health,
            "ownerMatrixUserId": f"@owner:{server_name}",
            "network": {
                "wireguardInterface": self.interface,
                "wireguardAddress": self.address,
                "matrixPort": 443,
                "synapseLoopbackPort": 8008,
                "federationEnabled": False,
            },
            "backupPaths": [
                "/etc/zerovpn/private-chat",
                "/var/lib/zerovpn/private-chat/synapse/signing.key",
                "/var/lib/postgresql",
            ],
        }
        validate_node_manifest(manifest)
        return manifest

    def apply_complete(self) -> None:
        manifest = self.build_manifest()
        if manifest["health"]["status"] == "unhealthy":
            raise InstallerError("The final node health state is unhealthy.")
        if not manifest["health"]["checks"]["encryptionSelfTest"]["ok"]:
            raise InstallerError("The encrypted self-test is not recorded as successful.")
        write_json(MANIFEST_FILE, manifest, 0o600)

    def probe_complete(self) -> bool:
        if not MANIFEST_FILE.exists():
            return False
        try:
            manifest = read_json(MANIFEST_FILE)
            validate_node_manifest(manifest)
            health = collect_health(self.address, self.ledger)
            return health["status"] != "unhealthy" and health["checks"]["encryptionSelfTest"]["ok"]
        except (InstallerError, ValueError):
            return False

    def stages(self) -> tuple[StageDefinition, ...]:
        return (
            StageDefinition(Stage.PRECHECK, self.probe_precheck, self.apply_precheck, always_run=True),
            StageDefinition(Stage.PACKAGES, self.probe_packages, self.apply_packages),
            StageDefinition(Stage.POSTGRES, self.probe_postgres, self.apply_postgres),
            StageDefinition(Stage.SYNAPSE, self.probe_synapse, self.apply_synapse),
            StageDefinition(Stage.TLS, self.probe_tls, self.apply_tls),
            StageDefinition(Stage.FIREWALL, self.probe_firewall, self.apply_firewall),
            StageDefinition(Stage.OWNER_ACCOUNT, self.probe_owner_account, self.apply_owner_account),
            StageDefinition(Stage.HEALTH, self.probe_health, self.apply_health, always_run=True),
            StageDefinition(
                Stage.ENCRYPTION_SELF_TEST,
                self.probe_encryption_self_test,
                self.apply_encryption_self_test,
            ),
            StageDefinition(Stage.COMPLETE, self.probe_complete, self.apply_complete, always_run=True),
        )


def load_options() -> tuple[str, str]:
    options = read_json(OPTIONS_FILE)
    interface = str(options.get("wireguardInterface", ""))
    address = str(options.get("wireguardAddress", ""))
    if not interface or not address:
        raise InstallerError("The private-chat installation options are missing.")
    return interface, address


def install(interface: str, address: str) -> int:
    # Snapshot before creating installer-owned paths so a fresh node is not
    # misreported as a partial prior installation.
    initial_preflight = collect_preflight(interface)
    ensure_private_directories()
    ledger = StageLedger(STATE_FILE)
    context = InstallerContext(interface, address, ledger, initial_preflight=initial_preflight)
    runner = StageRunner(ledger, emit_event)
    try:
        runner.run(context.stages())
    except StageFailure as error:
        print(
            "ZEROVPN_PRIVATE_CHAT_FAILURE "
            + json.dumps({"stage": error.stage.value, "reason": error.reason}, separators=(",", ":")),
            flush=True,
        )
        return 1
    print(
        "ZEROVPN_PRIVATE_CHAT_COMPLETE "
        + json.dumps(
            {
                "manifestPath": str(MANIFEST_FILE),
                "ownerCredentialsPath": str(OWNER_CREDENTIALS_FILE),
            },
            separators=(",", ":"),
        ),
        flush=True,
    )
    return 0


def health_command(as_json: bool) -> int:
    _, address = load_options()
    ledger = StageLedger(STATE_FILE)
    health = collect_health(address, ledger)
    write_json(HEALTH_FILE, health, 0o600)
    if MANIFEST_FILE.exists():
        manifest = read_json(MANIFEST_FILE)
        manifest["health"] = health
        manifest["updatedAt"] = utc_timestamp()
        validate_node_manifest(manifest)
        write_json(MANIFEST_FILE, manifest, 0o600)
    if as_json:
        print(json.dumps(health, separators=(",", ":")))
    else:
        print(f"Private Chat health: {health['status']}")
    return 0 if health["status"] != "unhealthy" else 1


def remove_installation() -> int:
    emit_event(Stage.COMPLETE, "running", "Removing only ZeroVPN private-chat components; WireGuard is retained.")
    for unit in (SYNAPSE_SERVICE, FIREWALL_SERVICE):
        run_command(f"stop {unit}", ["systemctl", "stop", unit], check=False, timeout=120)
        run_command(f"disable {unit}", ["systemctl", "disable", unit], check=False, timeout=120)
    firewall_remove = INSTALL_ROOT / "firewall/remove.sh"
    if firewall_remove.exists():
        run_command(
            "remove private-chat firewall chains",
            [str(firewall_remove)],
            check=False,
            timeout=60,
        )
    if NGINX_SITE_ENABLED.is_symlink() or NGINX_SITE_ENABLED.exists():
        NGINX_SITE_ENABLED.unlink()
    if NGINX_SITE_AVAILABLE.exists():
        NGINX_SITE_AVAILABLE.unlink()
    run_command("reload nginx after private-chat removal", ["systemctl", "reload", "nginx.service"], check=False)
    if shutil.which("dropdb"):
        run_command(
            "drop private-chat PostgreSQL database",
            ["runuser", "-u", "postgres", "--", "dropdb", "--if-exists", POSTGRES_DATABASE],
            check=False,
            timeout=120,
        )
    if shutil.which("pg_lsclusters"):
        try:
            postgres_drop_in = postgres_cluster_config_directory() / "conf.d/90-zerovpn-private-chat.conf"
            if postgres_drop_in.exists():
                postgres_drop_in.unlink()
                run_command(
                    "restart PostgreSQL after private-chat removal",
                    ["systemctl", "restart", "postgresql.service"],
                    check=False,
                    timeout=120,
                )
        except InstallerError:
            pass
        run_command(
            "drop private-chat PostgreSQL role",
            ["runuser", "-u", "postgres", "--", "psql", "--no-psqlrc", "-d", "postgres", "-c", f"DROP ROLE IF EXISTS {POSTGRES_ROLE};"],
            check=False,
            timeout=120,
        )
    for path in (SYNAPSE_UNIT_PATH, FIREWALL_UNIT_PATH):
        if path.exists():
            path.unlink()
    run_command("reload systemd after private-chat removal", ["systemctl", "daemon-reload"], check=False)
    run_command("remove Synapse service account", ["userdel", SYNAPSE_USER], check=False, timeout=60)
    for path in (ETC_ROOT, STATE_ROOT, INSTALL_ROOT, LOG_ROOT):
        if path.exists():
            shutil.rmtree(path)
    emit_event(Stage.COMPLETE, "success", "Private Chat components were removed; WireGuard was not changed.")
    if LOG_ROOT.exists():
        shutil.rmtree(LOG_ROOT)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Install or operate the ZeroVPN private-chat workload.")
    subparsers = parser.add_subparsers(dest="command", required=True)
    install_parser = subparsers.add_parser("install", help="Install or resume every idempotent stage.")
    install_parser.add_argument("--wireguard-interface", default="wg0")
    install_parser.add_argument("--wireguard-address", required=True)
    health_parser = subparsers.add_parser("health", help="Run non-secret node health checks.")
    health_parser.add_argument("--json", action="store_true")
    subparsers.add_parser("manifest", help="Print the non-secret node manifest.")
    remove_parser = subparsers.add_parser("remove", help="Remove chat while retaining WireGuard.")
    remove_parser.add_argument("--yes", action="store_true", help="Confirm removal.")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    require_root()
    args = build_parser().parse_args(argv)
    if args.command == "install":
        return install(args.wireguard_interface, args.wireguard_address)
    if args.command == "health":
        return health_command(args.json)
    if args.command == "manifest":
        manifest = read_json(MANIFEST_FILE)
        validate_node_manifest(manifest)
        print(json.dumps(manifest, separators=(",", ":")))
        return 0
    if args.command == "remove":
        if not args.yes:
            raise SystemExit("Pass --yes to remove Private Chat while retaining WireGuard.")
        return remove_installation()
    raise SystemExit("Unsupported command.")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PreflightError as error:
        print(
            "ZEROVPN_PRIVATE_CHAT_FAILURE "
            + json.dumps(
                {"stage": Stage.PRECHECK.value, "reason": sanitize_error(error)},
                separators=(",", ":"),
            ),
            flush=True,
        )
        raise SystemExit(1) from None
    except InstallerError as error:
        print(
            "ZEROVPN_PRIVATE_CHAT_FAILURE "
            + json.dumps({"stage": "PRIVATE_CHAT_UNKNOWN", "reason": sanitize_error(error)}, separators=(",", ":")),
            flush=True,
        )
        raise SystemExit(1) from None

#!/usr/bin/env python3
"""Real two-client Matrix encryption check for a provisioned local Synapse.

The script creates two short-lived accounts, creates encryption in the room's
initial state, proves both plaintext directions decrypt, independently fetches
the raw server events to prove they are ciphertext, then deactivates both
accounts and purges the room. It emits booleans only; usernames, passwords,
tokens, room IDs, event IDs, and message bodies are never printed.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import secrets
import shutil
import tempfile
import time
import urllib.parse
from pathlib import Path
from typing import Any, Callable

import aiohttp
from nio import (
    AsyncClient,
    AsyncClientConfig,
    ErrorResponse,
    JoinError,
    LoginError,
    RoomCreateError,
    RoomMessageText,
    RoomSendError,
    SyncError,
)


MEGOLM = "m.megolm.v1.aes-sha2"
RESULT_PREFIX = "ZEROVPN_E2EE_RESULT "


class SelfTestFailure(RuntimeError):
    pass


def required_path(environment_name: str) -> Path:
    value = os.environ.get(environment_name, "").strip()
    if not value:
        raise SelfTestFailure(f"Required environment setting {environment_name} is missing.")
    path = Path(value)
    if not path.is_file():
        raise SelfTestFailure(f"Required root-owned input {environment_name} is missing.")
    return path


def registration_mac(secret: bytes, nonce: str, username: str, password: str, admin: bool) -> str:
    mac = hmac.new(secret, digestmod=hashlib.sha1)
    mac.update(nonce.encode("utf-8"))
    mac.update(b"\x00")
    mac.update(username.encode("utf-8"))
    mac.update(b"\x00")
    mac.update(password.encode("utf-8"))
    mac.update(b"\x00")
    mac.update(b"admin" if admin else b"notadmin")
    return mac.hexdigest()


async def json_request(
    session: aiohttp.ClientSession,
    method: str,
    url: str,
    *,
    token: str | None = None,
    body: dict[str, Any] | None = None,
    expected: tuple[int, ...] = (200,),
) -> tuple[int, dict[str, Any]]:
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    async with session.request(method, url, headers=headers, json=body, timeout=20) as response:
        raw = await response.text()
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError as error:
            raise SelfTestFailure("A Matrix endpoint returned non-JSON data.") from error
        if response.status not in expected:
            raise SelfTestFailure(f"A Matrix endpoint returned HTTP {response.status}.")
        if not isinstance(payload, dict):
            raise SelfTestFailure("A Matrix endpoint returned an unexpected JSON value.")
        return response.status, payload


async def register_user(
    session: aiohttp.ClientSession,
    base_url: str,
    shared_secret: bytes,
    username: str,
    password: str,
) -> str:
    endpoint = base_url + "/_synapse/admin/v1/register"
    _, nonce_payload = await json_request(session, "GET", endpoint)
    nonce = str(nonce_payload.get("nonce", ""))
    if not nonce:
        raise SelfTestFailure("Synapse returned no shared-secret registration nonce.")
    _, response = await json_request(
        session,
        "POST",
        endpoint,
        body={
            "nonce": nonce,
            "username": username,
            "password": password,
            "admin": False,
            "mac": registration_mac(shared_secret, nonce, username, password, False),
        },
    )
    user_id = str(response.get("user_id", ""))
    if not user_id.startswith(f"@{username}:"):
        raise SelfTestFailure("Synapse returned an unexpected temporary user ID.")
    return user_id


async def owner_access_token(
    session: aiohttp.ClientSession,
    base_url: str,
    user_id: str,
    password: str,
) -> str:
    _, response = await json_request(
        session,
        "POST",
        base_url + "/_matrix/client/v3/login",
        body={
            "type": "m.login.password",
            "identifier": {"type": "m.id.user", "user": user_id},
            "password": password,
            "device_id": "ZEROVPN_SELFTEST_ADMIN",
            "initial_device_display_name": "ZeroVPN encrypted self-test cleanup",
        },
    )
    token = str(response.get("access_token", ""))
    if not token:
        raise SelfTestFailure("The owner Matrix account could not authenticate for cleanup.")
    return token


async def create_client(base_url: str, user_id: str, password: str, store_path: Path, label: str) -> AsyncClient:
    config = AsyncClientConfig(
        store_sync_tokens=True,
        encryption_enabled=True,
        max_limit_exceeded=0,
        max_timeouts=0,
    )
    client = AsyncClient(
        base_url,
        user=user_id,
        device_id=f"ZEROVPN-{label}-{secrets.token_hex(4)}",
        store_path=str(store_path),
        config=config,
    )
    login = await client.login(password=password, device_name=f"ZeroVPN encrypted self-test {label}")
    if isinstance(login, LoginError):
        await client.close()
        raise SelfTestFailure("A temporary Matrix identity could not log in.")
    initial_sync = await client.sync(timeout=3_000, full_state=True)
    if isinstance(initial_sync, SyncError):
        await client.close()
        raise SelfTestFailure("A temporary Matrix identity could not complete initial sync.")
    if client.should_upload_keys:
        uploaded = await client.keys_upload()
        if isinstance(uploaded, ErrorResponse):
            await client.close()
            raise SelfTestFailure("A temporary Matrix identity could not upload device keys.")
    return client


def timeline_events(response: Any, room_id: str) -> list[Any]:
    joined = getattr(getattr(response, "rooms", None), "join", {})
    room = joined.get(room_id) if isinstance(joined, dict) else None
    return list(getattr(getattr(room, "timeline", None), "events", ()) or ())


async def sync_until(
    client: AsyncClient,
    predicate: Callable[[Any], bool],
    *,
    attempts: int = 30,
) -> Any:
    for _ in range(attempts):
        response = await client.sync(timeout=1_000, full_state=False)
        if isinstance(response, SyncError):
            await asyncio.sleep(0.25)
            continue
        match = predicate(response)
        if match:
            return match
    raise SelfTestFailure("Timed out waiting for the encrypted Matrix event.")


async def raw_ciphertext_evidence(
    session: aiohttp.ClientSession,
    base_url: str,
    token: str,
    room_id: str,
    event_id: str,
    forbidden_plaintext: str,
) -> tuple[bool, bool]:
    room_segment = urllib.parse.quote(room_id, safe="")
    event_segment = urllib.parse.quote(event_id, safe="")
    _, event = await json_request(
        session,
        "GET",
        f"{base_url}/_matrix/client/v3/rooms/{room_segment}/event/{event_segment}",
        token=token,
    )
    content = event.get("content")
    raw = json.dumps(event, separators=(",", ":"), sort_keys=True)
    encrypted = (
        event.get("type") == "m.room.encrypted"
        and isinstance(content, dict)
        and isinstance(content.get("ciphertext"), str)
        and bool(content["ciphertext"])
    )
    return encrypted, forbidden_plaintext not in raw


async def send_and_receive(
    session: aiohttp.ClientSession,
    sender: AsyncClient,
    receiver: AsyncClient,
    room_id: str,
    message: str,
) -> tuple[bool, bool, bool]:
    response = await sender.room_send(
        room_id=room_id,
        message_type="m.room.message",
        content={"msgtype": "m.text", "body": message},
        ignore_unverified_devices=True,
    )
    if isinstance(response, RoomSendError):
        raise SelfTestFailure("A temporary Matrix identity could not send an encrypted event.")
    event_id = str(getattr(response, "event_id", ""))
    if not event_id:
        raise SelfTestFailure("Synapse returned no event ID for an encrypted send.")
    encrypted, plaintext_absent = await raw_ciphertext_evidence(
        session,
        sender.homeserver.rstrip("/"),
        str(sender.access_token),
        room_id,
        event_id,
        message,
    )

    def exact_decrypted_message(sync_response: Any) -> RoomMessageText | None:
        for event in timeline_events(sync_response, room_id):
            if isinstance(event, RoomMessageText) and event.body == message:
                return event
        return None

    received = await sync_until(receiver, exact_decrypted_message)
    return bool(received and received.body == message), encrypted, plaintext_absent


async def best_effort_leave(client: AsyncClient | None, room_id: str | None) -> None:
    if client is None or not room_id:
        return
    try:
        await client.room_leave(room_id)
        await client.room_forget(room_id)
    except Exception:  # noqa: BLE001 - cleanup continues with server-admin purge
        pass


async def deactivate_user(
    session: aiohttp.ClientSession,
    base_url: str,
    owner_token: str,
    user_id: str,
) -> bool:
    segment = urllib.parse.quote(user_id, safe="")
    await json_request(
        session,
        "POST",
        f"{base_url}/_synapse/admin/v1/deactivate/{segment}",
        token=owner_token,
        body={"erase": True},
    )
    _, details = await json_request(
        session,
        "GET",
        f"{base_url}/_synapse/admin/v2/users/{segment}",
        token=owner_token,
    )
    return details.get("deactivated") is True


async def purge_room(
    session: aiohttp.ClientSession,
    base_url: str,
    owner_token: str,
    room_id: str,
) -> bool:
    segment = urllib.parse.quote(room_id, safe="")
    await json_request(
        session,
        "DELETE",
        f"{base_url}/_synapse/admin/v2/rooms/{segment}",
        token=owner_token,
        body={"block": True, "purge": True, "force_purge": True},
    )
    for _ in range(30):
        status, _ = await json_request(
            session,
            "GET",
            f"{base_url}/_synapse/admin/v1/rooms/{segment}",
            token=owner_token,
            expected=(200, 404),
        )
        if status == 404:
            return True
        await asyncio.sleep(1)
    return False


async def run_test() -> dict[str, Any]:
    base_url = os.environ.get("ZEROVPN_MATRIX_LOCAL_URL", "http://127.0.0.1:8008").rstrip("/")
    if base_url != "http://127.0.0.1:8008":
        raise SelfTestFailure("The encrypted self-test is restricted to the local Synapse listener.")
    owner = json.loads(required_path("ZEROVPN_MATRIX_OWNER_CREDENTIALS").read_text(encoding="utf-8"))
    owner_user_id = str(owner.get("userId", ""))
    owner_password = str(owner.get("password", ""))
    if not owner_user_id.startswith("@owner:") or len(owner_password) < 32:
        raise SelfTestFailure("The owner cleanup credentials are invalid.")
    shared_secret = required_path("ZEROVPN_MATRIX_REGISTRATION_SECRET").read_text(encoding="utf-8").strip().encode("utf-8")
    if len(shared_secret) < 32:
        raise SelfTestFailure("The local registration secret is invalid.")

    suffix = secrets.token_hex(8)
    username_a = f"zv_selftest_a_{suffix}"
    username_b = f"zv_selftest_b_{suffix}"
    password_a = secrets.token_urlsafe(48)
    password_b = secrets.token_urlsafe(48)
    message_a = "zerovpn-a-to-b-" + secrets.token_hex(16)
    message_b = "zerovpn-b-to-a-" + secrets.token_hex(16)
    store_root = Path(tempfile.mkdtemp(prefix="zerovpn-e2ee-", dir="/tmp"))
    os.chmod(store_root, 0o700)
    client_a: AsyncClient | None = None
    client_b: AsyncClient | None = None
    user_a: str | None = None
    user_b: str | None = None
    room_id: str | None = None
    owner_token: str | None = None
    evidence: dict[str, Any] = {
        "status": "failed",
        "roomEncrypted": False,
        "aToBDecrypted": False,
        "aToBRawEncrypted": False,
        "aToBPlaintextAbsent": False,
        "bToADecrypted": False,
        "bToARawEncrypted": False,
        "bToAPlaintextAbsent": False,
        "temporaryAccountsDeactivated": False,
        "roomPurged": False,
    }
    primary_error: Exception | None = None

    async with aiohttp.ClientSession(raise_for_status=False) as session:
        try:
            owner_token = await owner_access_token(session, base_url, owner_user_id, owner_password)
            user_a = await register_user(session, base_url, shared_secret, username_a, password_a)
            user_b = await register_user(session, base_url, shared_secret, username_b, password_b)
            client_a = await create_client(base_url, user_a, password_a, store_root / "a", "A")
            client_b = await create_client(base_url, user_b, password_b, store_root / "b", "B")

            created = await client_a.room_create(
                is_direct=True,
                federate=False,
                invite=[user_b],
                initial_state=[
                    {
                        "type": "m.room.encryption",
                        "state_key": "",
                        "content": {"algorithm": MEGOLM},
                    }
                ],
            )
            if isinstance(created, RoomCreateError):
                raise SelfTestFailure("The encrypted room could not be created.")
            room_id = str(getattr(created, "room_id", ""))
            if not room_id:
                raise SelfTestFailure("Synapse returned no room ID for the self-test.")

            def invited(response: Any) -> bool:
                invite_rooms = getattr(getattr(response, "rooms", None), "invite", {})
                return isinstance(invite_rooms, dict) and room_id in invite_rooms

            await sync_until(client_b, invited)
            joined = await client_b.join(room_id)
            if isinstance(joined, JoinError):
                raise SelfTestFailure("The second temporary identity could not join the encrypted room.")
            await client_b.sync(timeout=3_000, full_state=True)
            await client_a.sync(timeout=3_000, full_state=True)
            await client_a.keys_query()
            await client_b.keys_query()
            room_a = client_a.rooms.get(room_id)
            room_b = client_b.rooms.get(room_id)
            evidence["roomEncrypted"] = bool(room_a and room_a.encrypted and room_b and room_b.encrypted)
            if not evidence["roomEncrypted"]:
                raise SelfTestFailure("Both clients did not observe m.room.encryption.")

            (
                evidence["aToBDecrypted"],
                evidence["aToBRawEncrypted"],
                evidence["aToBPlaintextAbsent"],
            ) = await send_and_receive(session, client_a, client_b, room_id, message_a)
            (
                evidence["bToADecrypted"],
                evidence["bToARawEncrypted"],
                evidence["bToAPlaintextAbsent"],
            ) = await send_and_receive(session, client_b, client_a, room_id, message_b)
        except Exception as error:  # noqa: BLE001 - cleanup must run for every failure
            primary_error = error
        finally:
            await best_effort_leave(client_b, room_id)
            await best_effort_leave(client_a, room_id)
            if client_b is not None:
                try:
                    await client_b.close()
                except Exception:
                    pass
            if client_a is not None:
                try:
                    await client_a.close()
                except Exception:
                    pass
            cleanup_errors: list[Exception] = []
            if owner_token:
                if room_id:
                    try:
                        evidence["roomPurged"] = await purge_room(session, base_url, owner_token, room_id)
                    except Exception as error:  # noqa: BLE001
                        cleanup_errors.append(error)
                deactivated: list[bool] = []
                for user_id in (user_b, user_a):
                    if not user_id:
                        continue
                    try:
                        deactivated.append(await deactivate_user(session, base_url, owner_token, user_id))
                    except Exception as error:  # noqa: BLE001
                        cleanup_errors.append(error)
                evidence["temporaryAccountsDeactivated"] = len(deactivated) == 2 and all(deactivated)
                try:
                    await json_request(
                        session,
                        "POST",
                        base_url + "/_matrix/client/v3/logout",
                        token=owner_token,
                        body={},
                    )
                except Exception:
                    pass
            shutil.rmtree(store_root, ignore_errors=True)
            if primary_error is None and cleanup_errors:
                primary_error = SelfTestFailure("Encrypted exchange passed but cleanup did not complete.")

    assertions = [value for key, value in evidence.items() if key not in {"status"}]
    if primary_error is None and all(assertion is True for assertion in assertions):
        evidence["status"] = "success"
        return evidence
    if isinstance(primary_error, SelfTestFailure):
        raise primary_error
    raise SelfTestFailure("The encrypted Matrix self-test failed; see the root-only stage log.") from primary_error


def main() -> int:
    try:
        evidence = asyncio.run(run_test())
    except SelfTestFailure as error:
        print(
            "ZEROVPN_E2EE_FAILURE "
            + json.dumps({"reason": str(error)[:400]}, separators=(",", ":")),
            flush=True,
        )
        return 1
    except Exception:
        # Never print unexpected third-party exception data because it can contain
        # identifiers or request details.
        print(
            "ZEROVPN_E2EE_FAILURE "
            + json.dumps({"reason": "Unexpected encrypted-client failure."}, separators=(",", ":")),
            flush=True,
        )
        return 1
    print(RESULT_PREFIX + json.dumps(evidence, separators=(",", ":"), sort_keys=True), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

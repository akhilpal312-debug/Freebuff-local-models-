"""OpenClaw Gateway adapter.

Implements the documented Gateway WebSocket protocol
(https://docs.openclaw.ai/gateway/protocol):

* text frames carrying JSON
* first client frame is `connect`; the gateway may first send a
  `connect.challenge` event whose `ts` must be echoed as `device.signedAt`
* after `hello-ok`: requests `{type:"req", id, method, params}` answered by
  `{type:"res", id, ok, payload|error}` and broadcast
  `{type:"event", event, payload}`
* the `agent` method requires an idempotency key and streams `agent` events

The Gateway is *not* bundled: it is deployed separately and the user pairs a
connection with a gateway URL and token. Tokens are stored encrypted by the
caller, never logged.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import time
import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

import websockets
from websockets.exceptions import ConnectionClosed, InvalidStatusCode, InvalidURI

from app.core.config import Settings
from app.core.errors import APIError
from app.core.redaction import redact

logger = logging.getLogger(__name__)

SCOPES = ["operator.read", "operator.write"]


class OpenClawError(APIError):
    def __init__(
        self,
        message: str,
        *,
        code: str = "openclaw_error",
        details: dict[str, Any] | None = None,
        status_code: int = 502,
    ) -> None:
        super().__init__(status_code, code, message, details=details or {})


@dataclass
class GatewayInfo:
    ok: bool
    status: str
    message: str
    protocol_version: int | None = None
    server_version: str | None = None
    scopes: list[str] = field(default_factory=list)
    methods: list[str] = field(default_factory=list)
    pairing_request_id: str | None = None
    latency_ms: int = 0


def normalise_url(url: str) -> str:
    url = url.strip()
    if not url:
        raise OpenClawError(
            "A gateway URL is required.", code="invalid_gateway_url", status_code=400
        )
    if url.startswith("https://"):
        url = "wss://" + url[len("https://") :]
    elif url.startswith("http://"):
        url = "ws://" + url[len("http://") :]
    if not url.startswith(("ws://", "wss://")):
        raise OpenClawError(
            "Gateway URLs must start with ws://, wss:// or https://.",
            code="invalid_gateway_url",
            status_code=400,
        )
    return url


class OpenClawClient:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    def _connect_params(self, token: str | None, device_id: str, challenge_ts: int | None) -> dict:
        params: dict[str, Any] = {
            "minProtocol": self.settings.openclaw_protocol_min,
            "maxProtocol": self.settings.openclaw_protocol_max,
            "client": {
                "id": self.settings.openclaw_client_id,
                "version": self.settings.openclaw_client_version,
                "platform": "android",
                "mode": "operator",
            },
            "role": "operator",
            "scopes": SCOPES,
            "caps": [],
            "commands": [],
            "permissions": {},
            "locale": "en-US",
            "userAgent": f"{self.settings.openclaw_client_id}/{self.settings.openclaw_client_version}",
            "device": {
                "id": device_id,
                "signedAt": int(challenge_ts if challenge_ts is not None else time.time() * 1000),
            },
        }
        if token:
            params["auth"] = {"token": token}
        if challenge_ts is not None:
            params["device"]["nonce"] = str(challenge_ts)
        return params

    async def _handshake(
        self, url: str, token: str | None, device_id: str
    ) -> tuple[Any, GatewayInfo]:
        started = time.perf_counter()
        try:
            websocket = await websockets.connect(
                normalise_url(url), open_timeout=10, close_timeout=5, max_size=4 * 1024 * 1024
            )
        except (InvalidURI, InvalidStatusCode, OSError) as exc:
            raise OpenClawError(
                f"Could not connect to the gateway: {type(exc).__name__}.",
                code="gateway_unreachable",
            ) from exc
        challenge_ts: int | None = None
        try:
            with contextlib.suppress(TimeoutError):
                raw = await asyncio.wait_for(websocket.recv(), timeout=2.0)
                frame = json.loads(raw)
                if frame.get("type") == "event" and frame.get("event") == "connect.challenge":
                    value = (frame.get("payload") or {}).get("ts")
                    if isinstance(value, int) and value >= 0:
                        challenge_ts = value
                    else:
                        await websocket.close()
                        raise OpenClawError(
                            "The gateway sent a malformed connect challenge.",
                            code="invalid_challenge",
                        )
        except json.JSONDecodeError as exc:
            await websocket.close()
            raise OpenClawError(
                "The gateway sent a non-JSON frame.", code="protocol_error"
            ) from exc

        request_id = uuid.uuid4().hex
        await websocket.send(
            json.dumps(
                {
                    "type": "req",
                    "id": request_id,
                    "method": "connect",
                    "params": self._connect_params(token, device_id, challenge_ts),
                }
            )
        )
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                raw = await asyncio.wait_for(websocket.recv(), timeout=max(1.0, deadline - time.monotonic()))
            except TimeoutError as exc:
                await websocket.close()
                raise OpenClawError(
                    "The gateway did not answer the connect request.", code="handshake_timeout"
                ) from exc
            try:
                frame = json.loads(raw)
            except json.JSONDecodeError:
                continue
            if frame.get("type") != "res" or frame.get("id") != request_id:
                continue
            if not frame.get("ok"):
                error = frame.get("error") or {}
                await websocket.close()
                details = error.get("details") or {}
                code = str(error.get("code") or "connect_rejected")
                message = redact(str(error.get("message") or "The gateway rejected the connection."))
                pairing_id = details.get("requestId") or details.get("pairingRequestId")
                if code == "PAIRING_REQUIRED" or pairing_id:
                    raise OpenClawError(
                        "This device needs pairing approval in the gateway before it can "
                        "connect.",
                        code="pairing_required",
                        details={"request_id": pairing_id, **(details or {})},
                        status_code=409,
                    )
                raise OpenClawError(
                    message, code=code, details={"gateway_code": code}, status_code=400
                )
            payload = frame.get("payload") or {}
            auth = payload.get("auth") or {}
            info = GatewayInfo(
                ok=True,
                status="connected",
                message="Connected to the OpenClaw gateway.",
                protocol_version=payload.get("protocolVersion") or payload.get("protocol"),
                server_version=(payload.get("server") or {}).get("version")
                if isinstance(payload.get("server"), dict)
                else payload.get("serverVersion"),
                scopes=list(auth.get("scopes") or SCOPES),
                methods=list((payload.get("features") or {}).get("methods") or []),
                latency_ms=int((time.perf_counter() - started) * 1000),
            )
            logger.info("openclaw connected protocol=%s", info.protocol_version)
            return websocket, info
        await websocket.close()
        raise OpenClawError("The gateway handshake timed out.", code="handshake_timeout")

    async def probe(self, url: str, token: str | None, device_id: str) -> GatewayInfo:
        try:
            websocket, info = await self._handshake(url, token, device_id)
        except OpenClawError as exc:
            return GatewayInfo(
                ok=False,
                status="error",
                message=exc.message,
                pairing_request_id=(exc.details or {}).get("request_id"),
            )
        await websocket.close()
        return info

    async def run_agent(
        self,
        *,
        url: str,
        token: str | None,
        device_id: str,
        message: str,
        session_key: str,
        agent_id: str | None = None,
        timeout_seconds: float = 300.0,
    ) -> AsyncIterator[dict[str, Any]]:
        """Run an agent turn and yield normalised events."""
        websocket, info = await self._handshake(url, token, device_id)
        yield {"type": "connected", "protocol_version": info.protocol_version}
        request_id = uuid.uuid4().hex
        params: dict[str, Any] = {
            "message": message,
            "sessionKey": session_key,
            "idempotencyKey": request_id,
        }
        if agent_id:
            params["agentId"] = agent_id
        await websocket.send(
            json.dumps({"type": "req", "id": request_id, "method": "agent", "params": params})
        )
        deadline = time.monotonic() + timeout_seconds
        accepted = False
        try:
            while time.monotonic() < deadline:
                try:
                    raw = await asyncio.wait_for(
                        websocket.recv(), timeout=max(1.0, deadline - time.monotonic())
                    )
                except TimeoutError:
                    yield {"type": "error", "message": "The gateway run timed out."}
                    return
                try:
                    frame = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                kind = frame.get("type")
                if kind == "event" and frame.get("event") == "agent":
                    payload = frame.get("payload") or {}
                    for event in self._normalise_agent_event(payload):
                        yield event
                elif kind == "res" and frame.get("id") == request_id:
                    if not frame.get("ok"):
                        error = frame.get("error") or {}
                        yield {
                            "type": "error",
                            "message": redact(str(error.get("message") or "Gateway error")),
                            "code": error.get("code"),
                        }
                        return
                    payload = frame.get("payload") or {}
                    status = str(payload.get("status") or "")
                    if status in {"accepted", "running"} and not accepted:
                        accepted = True
                        yield {"type": "accepted", "run_id": payload.get("runId")}
                        continue
                    yield {
                        "type": "done",
                        "status": status or "completed",
                        "summary": payload.get("summary") or payload.get("text") or "",
                        "run_id": payload.get("runId"),
                    }
                    return
        except ConnectionClosed:
            yield {"type": "error", "message": "The gateway closed the connection."}
        finally:
            with contextlib.suppress(Exception):
                await websocket.close()

    @staticmethod
    def _normalise_agent_event(payload: dict[str, Any]) -> list[dict[str, Any]]:
        events: list[dict[str, Any]] = []
        if payload.get("delta") or payload.get("text"):
            events.append({"type": "delta", "text": str(payload.get("delta") or payload.get("text"))})
        if payload.get("tool"):
            events.append(
                {
                    "type": "tool",
                    "name": str(payload.get("tool")),
                    "status": payload.get("status"),
                }
            )
        if payload.get("status") in {"completed", "failed", "cancelled"} and not payload.get(
            "delta"
        ):
            events.append({"type": "status", "status": payload.get("status")})
        return events

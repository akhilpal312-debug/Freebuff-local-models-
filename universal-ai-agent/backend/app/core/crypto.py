from __future__ import annotations

import base64
import hashlib
import hmac

from cryptography.fernet import Fernet, InvalidToken

from app.core.errors import APIError


class CredentialCipher:
    """Encrypts provider API keys at rest with Fernet (AES-128-CBC + HMAC).

    The key never lives in the database. `fingerprint()` produces a stable,
    non-reversible identifier so a UI can show *which* key is stored without
    ever returning the key itself.
    """

    def __init__(self, key: str | None, *, environment: str) -> None:
        if key:
            self._fernet = self._fernet_from(key)
        elif environment.lower() in {"production", "prod"}:
            raise RuntimeError(
                "CREDENTIAL_ENCRYPTION_KEY must be set in production; refusing to start "
                "with a derived development key."
            )
        else:
            self._fernet = self._fernet_from("universal-ai-agent-development-only-key")

    @staticmethod
    def _fernet_from(raw: str) -> Fernet:
        try:
            return Fernet(raw.encode("utf-8"))
        except (ValueError, TypeError):
            # Accept any sufficiently random passphrase and derive a valid key.
            digest = hashlib.sha256(raw.encode("utf-8")).digest()
            return Fernet(base64.urlsafe_b64encode(digest))

    def encrypt(self, plaintext: str) -> str:
        return self._fernet.encrypt(plaintext.encode("utf-8")).decode("ascii")

    def decrypt(self, ciphertext: str) -> str:
        try:
            return self._fernet.decrypt(ciphertext.encode("ascii")).decode("utf-8")
        except InvalidToken as exc:  # pragma: no cover - configuration failure
            raise APIError(
                500,
                "credential_decrypt_failed",
                "Stored credentials could not be decrypted. Re-enter the API key.",
            ) from exc

    @staticmethod
    def fingerprint(plaintext: str) -> str:
        return hmac.new(
            b"universal-ai-agent-fingerprint", plaintext.encode("utf-8"), hashlib.sha256
        ).hexdigest()[:12]

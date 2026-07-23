-- Tier 1 security: device tokens are stored as SHA-256 hashes, never plain text.
-- The plaintext is held only on the short-lived pairing_code row so the player
-- can collect it once during pairing; a sweep clears it after expiry.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE pairing_codes
    ADD COLUMN device_token_plain VARCHAR(120);

-- Existing paired screens: replace stored plaintext with its SHA-256 hex hash.
-- Devices keep sending the plaintext; the server hashes incoming tokens before
-- comparing, so already-paired players continue to work unchanged.
UPDATE screens
SET device_token = encode(digest(device_token, 'sha256'), 'hex')
WHERE device_token IS NOT NULL
  AND length(device_token) <> 64; -- skip anything already hashed (64 hex chars)

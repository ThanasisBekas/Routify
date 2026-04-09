-- Widen webhook_subscription.secret to TEXT for @Sensitive field-level encryption.
-- Encrypted AES-256-GCM payloads (with {enc} prefix, IV, tag, and Base64 encoding)
-- exceed the previous VARCHAR(255) limit.

ALTER TABLE routify_identity.webhook_subscription
    ALTER COLUMN secret TYPE TEXT;


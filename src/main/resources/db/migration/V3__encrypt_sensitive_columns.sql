-- Widen every column that now holds ciphertext instead of plaintext.
--
-- Two different kinds of ciphertext live in these columns:
--   * client-encrypted (end-to-end): stories.title, stories.description,
--     stories.reference_url, participants.display_name. The server has no key.
--   * server-encrypted (at rest): sessions.name, votes.card_value,
--     stories.final_estimate, users.email.
--
-- AES-GCM adds a 12-byte IV and a 16-byte tag, then base64 inflates by ~4/3,
-- so a bounded plaintext becomes a much longer string; `text` avoids having to
-- reason about the exact ceiling. Request size limits are enforced by Bean
-- Validation on the ciphertext instead.
--
-- No backfill is included: this migration widens types only. A deployment that
-- already holds plaintext rows keeps serving them (the at-rest converter passes
-- unprefixed values through on read), but those rows stay unencrypted until
-- re-written. Re-encrypt existing data before relying on the guarantees above.

alter table sessions alter column name type text;
alter table participants alter column display_name type text;
alter table stories alter column title type text;
alter table stories alter column reference_url type text;
alter table stories alter column final_estimate type text;
alter table votes alter column card_value type text;
alter table users alter column email type text;

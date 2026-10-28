# Privacy and encryption design

This app is built so that the people running it can see as little as possible.
Two independent layers do that work, and they protect against different
adversaries. The distinction matters: mixing them up leads to overclaiming.

## Layer 1 — end-to-end encryption (client-held key)

**Fields:** story title, story description, story reference URL, participant
display names.

The browser generates a random AES-256 key ("room key") when a session is
created. That key is **never sent to the server**. It travels to other
participants in the fragment of the invite link (`/?join=CODE#k=…`), which
browsers do not transmit, or by being shared out of band and pasted into the
join form. It is held in `sessionStorage` for the tab's lifetime and stripped
from the visible URL once adopted.

These fields are encrypted with AES-256-GCM before they leave the page
(`e1.` + base64url(iv ‖ ciphertext ‖ tag), fresh 12-byte IV per value) and the
server stores and echoes the blobs verbatim. It holds no key for them.

**Protects against:** the database administrator, backups, dumps, read
replicas, SQL-injection data theft, **and the app owner/operator** — someone
with full control of the running server, its memory, and its configuration
still cannot read story text or names.

**What it costs, honestly:**

- **Joining with only a session code does not work.** The key has to come from
  somewhere the server cannot see, so a participant needs the invite link or
  the room key. Anyone who joins without the right key gets a working session
  with `🔒 encrypted` in place of text they cannot read.
- **Export is rendered in the browser.** The server hands over a bundle of
  ciphertext; the client decrypts it and writes the CSV/JSON file. There is no
  server-side path to a readable export.
- **The server cannot validate or sanitize what it cannot read.** Plaintext
  length limits are enforced in the browser; the server can only bound the
  ciphertext size (see the constants in `Dtos`). Stored-XSS safety therefore
  rests on the client rendering all user data through `textContent`, never
  `innerHTML` — this is a load-bearing invariant, so keep it that way.
- **Losing the room key loses the data.** There is no recovery path, by design.
- **A room key leak is permanent for that session.** Anyone who obtains the
  link can decrypt that session's text; there is no re-keying.
- **WebCrypto requires a secure context.** Over plain HTTP on a LAN address the
  app will refuse to encrypt. Serve it over HTTPS (or localhost).

## Layer 2 — encryption at rest (server-held key)

**Fields:** session name, vote card values, story final estimate, user email.

Encrypted with AES-256-GCM by a JPA attribute converter on the way to the
column, using a key supplied through `APP_DATA_KEY` (base64 of exactly 32
bytes) from the environment or a secret manager. The key is never stored in a
column, in source, or in an image. A fresh IV per value means equal plaintexts
produce different ciphertexts, so the database leaks no equality relationships
— an administrator cannot tell that two participants voted the same card.
The GCM tag makes tampering fail loudly instead of decrypting to garbage.

**Protects against:** the database administrator, backups, dumps, read
replicas, SQL-injection data theft.

**Does not protect against:** the app owner/operator. The process must hold
this key to function, so anyone controlling it can decrypt these fields.

### Why vote values are not end-to-end encrypted

The server needs them in the clear to validate a vote against the session
deck, compute the distribution/min/max/average/median every participant sees,
detect consensus, and decide auto-reveal. Moving that client-side would also
move the computation into a place where a participant could forge the
statistics shown to everyone else. Given that trade, votes are protected at
rest with the server-held key and the limitation is stated rather than hidden.

If hiding votes from the operator matters more than trustworthy statistics,
that is a deliberate redesign, not a configuration change.

## Data minimization

- **Client addresses are never retained.** The rate limiter keys its buckets on
  `HMAC-SHA256(per-process random salt, address)`, truncated. The salt is
  random per process and never persisted, so the mapping is not reproducible
  even by the operator after a restart.
- **Session codes stay out of logs.** A code is the capability to join a
  session, so log statements identify sessions by opaque UUID instead, and
  error messages never echo the code. Tomcat access logging is explicitly
  disabled because request paths embed the code.
- **SQL and bound parameters are never logged.** Parameters would carry
  decrypted session names and vote values; `show-sql` is off and the relevant
  Hibernate loggers are pinned off.
- **Failure messages from snapshot delivery are not logged**, only the session
  id, since exception text can carry session content.
- **Operator endpoints are closed to participants.** `/actuator/metrics` runs
  on its own security filter chain that deliberately does not consult the JWT
  filter, so a guest's session token cannot open it — previously any
  participant token read it and learned session counts and JVM internals.
  Health and info stay public for platform probes.

## Known residual exposure

Stated plainly, because a security document that only lists wins is not useful:

- **Session codes appear in URL paths** (`/api/sessions/{code}/…`). Our own
  access logs are off, but a reverse proxy, CDN, or browser history will still
  see them. Treat a code like a shared secret.
- **Vote values and session names are readable by the operator** (Layer 2, by
  design — see above).
- **Deck values are stored in the clear.** Built-in decks are public constants;
  a custom deck's values are not encrypted.
- **Structural metadata is not hidden from the operator**: how many
  participants a session has, who voted when, join/leave timing, story counts,
  and round timestamps. Encrypting content does not conceal shape.
- **The room key lives in `sessionStorage`**, so a successful XSS against the
  page could exfiltrate it. This is inherent to browser-based E2EE and is why
  the `textContent`-only rendering rule above matters.
- **`APP_DATA_KEY` rotation is not automated.** Rotating it makes existing
  at-rest columns unreadable; re-encrypt before rotating.
- **Migration `V3` widens columns but does not backfill.** Rows written before
  encryption stay plaintext until rewritten (the at-rest converter passes
  unprefixed values through on read so nothing breaks). Re-encrypt existing
  data before relying on the guarantees above.

## Operational requirements

```bash
# 32 random bytes, base64 — from a secret manager in production
export APP_DATA_KEY="$(head -c 32 /dev/urandom | base64)"
export APP_JWT_SECRET="$(head -c 48 /dev/urandom | base64)"
# operator credential for /actuator/metrics; with none set the endpoint is
# closed to everyone, which is the default
export APP_ACTUATOR_PASSWORD="$(head -c 24 /dev/urandom | base64)"
```

The dev defaults in `application.yml` exist only so `bootRun` works; they are
clearly labelled and must not be used anywhere real. Serve over HTTPS so
WebCrypto is available and the room key is not exposed in transit.
`docs/DEPLOYMENT.md` covers a deployment that satisfies both.

---
name: run-planning-poker
description: Build, run, and drive the Planning Poker app. Use when asked to start planning-poker, run or build it, run its tests, take a screenshot of its UI, reproduce a bug, or interact with the running estimation session.
---

Spring Boot 3.5 / Java 21 backend serving a vanilla-JS SPA on port 8080, with
STOMP-over-WebSocket for real-time updates. Drive it with
`.claude/skills/run-planning-poker/driver.mjs`, a Playwright harness that runs
headless Chromium.

**The driver is not optional convenience.** Story text and participant names
are end-to-end encrypted with a room key the server never sees, so a
participant cannot join with only a session code — naive automation fills in
the join form, gets a toast, and silently never enters the session. The driver
captures the key from the facilitator's page and joins everyone through the
invite link. Use it rather than writing raw Playwright calls.

All paths below are relative to the repo root.

## Prerequisites

Everything is preinstalled in this image; no `apt-get` was needed. Verify:

```bash
java -version 2>&1 | tail -1        # ...build 21.x... (tail, not head: JAVA_TOOL_OPTIONS noise)
node --version                      # v22
test -x /opt/pw-browsers/chromium && echo chromium ok
node -e "import('/opt/node22/lib/node_modules/playwright/index.js').then(m=>console.log('playwright:', m.default.chromium?'ok':'MISSING'))"
command -v tmux lsof
```

Java 21 and a headless Chromium are the hard requirements. Playwright lives in
the **global** node prefix — the repo has no `node_modules` and does not need
one.

## Build

```bash
./gradlew bootJar
```

Produces `build/libs/planning-poker-0.1.0.jar` (~66 MB, fat jar). The committed
wrapper works offline here; plain `gradle` works too.

## Run (agent path)

Start the server, wait for health, drive it, stop it:

```bash
nohup java -jar build/libs/planning-poker-0.1.0.jar > /tmp/pp.log 2>&1 &
timeout 90 bash -c 'until curl -sf http://localhost:8080/actuator/health >/dev/null; do sleep 1; done'

node .claude/skills/run-planning-poker/driver.mjs smoke

lsof -ti:8080 -sTCP:LISTEN | xargs -r kill
```

`smoke` runs a full multi-participant round — facilitator creates a session and
a story, two voters and an observer join via the invite link, votes are cast,
hidden, revealed, and an estimate recorded — then asserts the privacy
properties (no plaintext in any request body; the API serves ciphertext).
Exit code 0 on success, 1 on any failed check. Screenshots land in
`/tmp/pp-shots/` (`fac.png`, `ana.png`, `olly.png`), taken at reveal time
because recording the estimate clears the results panel.

Expected tail:

```
  ok   server holds the title as ciphertext
  shot  /tmp/pp-shots/fac.png
  ok   no page errors or failed requests

smoke OK
```

### Interactive REPL

For poking at a specific state, run the driver as a REPL under tmux. Every
command echoes `OK` when it finishes, so poll for that instead of sleeping:

```bash
tmux new-session -d -s pp -x 200 -y 50
tmux send-keys -t pp 'node .claude/skills/run-planning-poker/driver.mjs repl' Enter
timeout 60 bash -c 'until tmux capture-pane -t pp -p | grep -q "^READY"; do sleep 0.3; done'
```

Then use this helper — it waits for the `OK` count to increase, so commands
never overlap:

```bash
send() {
  local before=$(tmux capture-pane -t pp -p | grep -c '^OK')
  tmux send-keys -t pp "$1" Enter
  timeout 60 bash -c "until [ \$(tmux capture-pane -t pp -p | grep -c '^OK') -gt $before ]; do sleep 0.3; done" \
    || echo "TIMEOUT on: $1"
}

send 'desc Payment retries | Retry failed charges up to 3 times.'
send 'select 1'
send 'join ana Ana Voter'
send 'vote ana 13'
send 'reveal'
send 'ss ana revealed-via-repl'
send 'text ana #stats'
tmux capture-pane -t pp -p | grep -v '^$' | tail -12
```

Read output with `grep -v '^$'` — `capture-pane` pads to the full pane height,
so a bare `tail` shows only blank lines.

| command | what it does |
|---|---|
| `create <name>` | create a session as facilitator; prints `code=`, `key=`, `link=` |
| `join <who> <display name>` | join via the invite link (key handled for you) |
| `observe <who> <display name>` | join as a non-voting observer |
| `story <title>` | add a story |
| `desc <title> \| <description>` | add a story with a description |
| `select [n]` | start voting on story n (default 1) |
| `vote <who> <card>` | cast a vote |
| `reveal` / `reset` | facilitator round controls |
| `estimate <value>` | record the final estimate |
| `ss <who> [label]` | screenshot → `/tmp/pp-shots/` |
| `text <who> <selector>` | print `textContent` of the first match |
| `eval <who> <js>` | evaluate JS in that page |
| `errors` | page errors and failed requests collected so far |
| `who` | list open participants |
| `quit` | close the browser and exit |

Tear down with `tmux kill-session -t pp`.

## Run in a container

The repo ships a `Dockerfile` (multi-stage; Alpine JRE runtime, non-root, JVM
flags sized for a 512 MiB instance). Verified locally:

```bash
docker build -t planning-poker:local .
docker run -d --name pp -p 8080:8080 --memory 512m \
  -e PORT=8080 \
  -e APP_JWT_SECRET="$(head -c 48 /dev/urandom | base64 -w0)" \
  -e APP_DATA_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
  planning-poker:local
timeout 120 bash -c 'until curl -sf http://localhost:8080/actuator/health >/dev/null; do sleep 1; done'
node .claude/skills/run-planning-poker/driver.mjs smoke
docker rm -f pp
```

411 MB image, ~272 MiB of the 512 MiB limit, ~7 s to boot.

## Check a deployment

The same smoke doubles as a post-deploy gate — it exits non-zero if the
deployed origin cannot do WebCrypto, which is the most likely way a deployment
is broken:

```bash
node .claude/skills/run-planning-poker/driver.mjs smoke --url https://your-service.run.app
```

It leaves one finished session behind, which expires on its own. Deploy with
`./deploy.sh --verify` to run it automatically; see `docs/DEPLOYMENT.md`.

## Direct invocation (Java internals)

Most backend changes need only a targeted test, which is far faster than the
full app:

```bash
./gradlew test --tests 'com.planningpoker.crypto.DataEncryptionTest'
```

Takes ~3s warm. Useful entry points: `DataEncryptionTest` (crypto),
`DatabaseHoldsNoPlaintextTest` (reads columns over JDBC to prove nothing
readable is stored), `SessionFlowIntegrationTest` (service-level round),
`StompIntegrationTest` (multi-client WebSocket fan-out).

## Run (human path)

```bash
./gradlew bootRun      # serves http://localhost:8080 until Ctrl-C
```

Same app; only useful if you can open a browser. Stop it with
`lsof -ti:8080 -sTCP:LISTEN | xargs -r kill`.

## Test

```bash
./gradlew test
```

34 tests, all passing, ~30s cold. Counts:

```bash
python3 -c "
import xml.etree.ElementTree as ET, glob
t=sum(int(ET.parse(p).getroot().get('tests')) for p in glob.glob('build/test-results/test/*.xml'))
f=sum(int(ET.parse(p).getroot().get('failures'))+int(ET.parse(p).getroot().get('errors')) for p in glob.glob('build/test-results/test/*.xml'))
print(f'{t} tests, {f} failures')"
```

## Gotchas

- **You cannot join with just a session code.** The app is end-to-end
  encrypted; joining needs the room key, which lives only in the facilitator's
  browser. The join form shows a room-key field and refuses with *"This session
  is end-to-end encrypted…"*. Always join via `/?join=CODE#k=KEY` — the driver's
  `join` does this. To get the key by hand: click `#btn-show-room-key` on the
  facilitator page and read `#room-key-value`.
- **Serve on `localhost`, never the container IP.** WebCrypto requires a secure
  context. Over `http://192.0.2.2:8080` `window.isSecureContext` is `false`,
  `crypto.subtle` is `undefined`, and session creation fails outright with
  *"This browser cannot encrypt (needs HTTPS or localhost)"*. `http://localhost:8080`
  is a secure context and works. The driver's preflight check catches this and
  aborts with exit 1.
- **More than one instance silently breaks real-time.** The STOMP broker and
  rate limiter are both in-process, so participants on different instances stop
  seeing each other's votes with no error anywhere. Any deployment must pin a
  single instance.
- **Playwright is only in the global prefix.** A bare `import('playwright')`
  fails from this repo (`ERR_MODULE_NOT_FOUND`; no local `node_modules`, empty
  `NODE_PATH`), and the global entry is CommonJS whose named exports do not
  surface `chromium` — it is on `.default.chromium`. The driver tries both
  specifiers and both shapes; copy that loader if you write your own script.
- **`confirm()` dialogs make destructive actions silently no-op.** Delete story,
  kick, and facilitator transfer all call `window.confirm`, and Playwright
  *auto-dismisses* dialogs. Verified: without a handler the story survives the
  ✕ click with no error; with `page.on('dialog', d => d.accept())` it is
  deleted. The driver registers the handler on every page.
- **An empty story list still contains one `<li>`** — the "No stories yet."
  placeholder. Counting `.story-list li` to detect a deletion gives a false
  negative; count `.story-list li .story-actions` or match on text.
- **Never kill the server by process-name pattern.** `pkill -f planning-poker`
  — and even the `[p]lanning-poker` bracket trick — matches the agent's own
  shell when that same command line mentions the jar, killing the session
  (exit 143/144). Use the port: `lsof -ti:8080 -sTCP:LISTEN | xargs -r kill`.
- **State is in-memory H2 and resets on every restart.** Session codes from a
  previous run are gone; create a fresh session each time. There is no fixture
  to load.
- **Raw STOMP clients must connect to `/ws/websocket`, not `/ws`.** `/ws` is the
  SockJS endpoint; a `WebSocketStompClient` pointed at it fails with
  *"Connection closed"*. Browsers use SockJS and are unaffected.

## Troubleshooting

- **`Error: No server at http://localhost:8080 after 60000ms`** — the driver ran
  before the app was listening. Start the jar and poll `/actuator/health` as
  shown above; check `/tmp/pp.log` if it never comes up.
- **`TypeError: Cannot read properties of undefined (reading 'launch')`** — the
  Playwright import resolved but `chromium` was undefined. Use
  `m.chromium ?? m.default?.chromium` (see the loader gotcha above).
- **`IllegalStateException: At-rest encryption key must decode to exactly 32
  bytes (AES-256), got 31`** — `APP_DATA_KEY` is set to base64 of the wrong
  length. Generate one with `head -c 32 /dev/urandom | base64`, or unset it to
  use the dev default baked into `application.yml`.
- **Driver check `average of 5 and 8 is 6.5` fails** — statistics changed, or a
  vote did not land. Screenshot the facilitator (`ss fac`) and inspect
  `#vote-list`; a missing vote usually means `vote` ran before the round opened.
- **tmux `capture-pane` output looks empty** — it is padded to the pane height.
  Pipe through `grep -v '^$'` before `tail`.

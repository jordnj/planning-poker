# Deploying to Cloud Run

Zero-cost hosting for a small team. Cloud Run is chosen because this app's two
awkward requirements come free there: managed TLS (mandatory — see below) and
WebSocket support, with scale-to-zero so an idle deployment costs nothing.

## Two constraints that drive every setting

**HTTPS is mandatory, not a nice-to-have.** Story text and participant names
are encrypted in the browser with WebCrypto, which only exists in a secure
context. Over plain HTTP on anything but `localhost`, `crypto.subtle` is
`undefined` and the app refuses to create a session at all. Cloud Run always
terminates TLS, so this is satisfied by default — but it is why you must never
"temporarily" expose it over plain HTTP on an IP address.

**Exactly one instance.** The STOMP broker is Spring's in-memory simple broker
and the rate limiter is an in-process map. With two instances, participants in
the same session land on different ones and silently stop seeing each other's
votes. `--max-instances 1` in `deploy.sh` is a correctness requirement, not a
cost control. Lifting it means moving to a real broker relay first (RT-2).

## Deploy

```bash
gcloud auth login
gcloud config set project YOUR_PROJECT_ID
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com

./deploy.sh --verify
```

`--verify` runs the browser smoke against the deployed URL afterwards, which
checks the things most likely to be wrong in a fresh deployment: that the
origin yields a secure context, that a full round works end to end, that no
plaintext leaves the browser, and that `/actuator/metrics` is closed.

`./deploy.sh --dry-run` prints the exact `gcloud` command with secrets
redacted. Settings come from the environment: `SERVICE`, `REGION`, `PROJECT`,
`MEMORY`.

## Secrets

The script generates `APP_JWT_SECRET`, `APP_DATA_KEY` and
`APP_ACTUATOR_PASSWORD` on the first deploy and **reuses them afterwards** by
reading them back off the deployed service. That reuse matters:

- rotating `APP_DATA_KEY` makes every at-rest encrypted column (session names,
  vote values, final estimates, user emails) permanently unreadable;
- rotating `APP_JWT_SECRET` signs everyone out mid-session.

The committed defaults in `application.yml` are labelled dev-only and are in
git history. Deploying with them would let anyone forge facilitator tokens and
decrypt the database, so never let the real deployment fall back to them.

Secrets are stored as plain environment variables on the service, visible to
anyone with console access to the project. That is the free option. Google
Secret Manager is the better practice; check its current pricing, as it is not
necessarily free.

## Sizing

Measured on the image this repo builds, under a 512 MiB limit:

| | |
|---|---|
| image | 411 MB (Alpine JRE; stays under Artifact Registry's 0.5 GB free storage) |
| heap cap | 232 MB (`-XX:MaxRAMPercentage=45`, read from the cgroup limit) |
| RSS idle | ~272 MiB of 512 MiB |
| RSS after a full session | ~328 MiB |
| cold start | ~7 s JVM boot, plus image pull |

`-XX:+UseSerialGC` beats G1 for a single small instance: lower footprint, no
concurrent GC threads. Extra JVM tuning can go in `JAVA_TOOL_OPTIONS` without
editing the Dockerfile.

## What this costs you

- **Cold starts.** Whoever opens the first session after an idle period waits
  for the container to start. Once a session is live the WebSocket counts as an
  active request, so CPU stays allocated and it is responsive.
- **Sessions vanish when it scales to zero.** The database is in-memory H2, so
  the data is gone on restart. For planning poker that is mostly fine — sessions
  are ephemeral by design and close after 4 h idle — but you cannot leave a
  session parked for an hour and come back to it. For durability, point it at
  Postgres (`--spring.profiles.active=postgres`, `DB_URL`, `DB_USERNAME`,
  `DB_PASSWORD`) and re-read the key-rotation warning above.
- **CPU is throttled between requests**, so the `@Scheduled` idle-session
  cleanup does not run while asleep. Harmless here, since the data is ephemeral.
- **A billing account is required**, even though usage should land inside the
  always-free allowance. Set a budget alert; `--max-instances 1` bounds the
  blast radius.
- **Free-tier terms change.** Confirm current limits rather than trusting this
  document.

## Operating it

```bash
# logs
gcloud run services logs read planning-poker --region us-central1 --limit 50

# metrics (the password is printed on first deploy)
curl -u "actuator:$APP_ACTUATOR_PASSWORD" https://YOUR-URL/actuator/metrics

# re-run the post-deploy check at any time
node .claude/skills/run-planning-poker/driver.mjs smoke --url https://YOUR-URL

# old images accumulate in Artifact Registry and count against free storage
gcloud artifacts docker images list \
  us-central1-docker.pkg.dev/YOUR_PROJECT/cloud-run-source-deploy/planning-poker
```

`/actuator/health` is public so the platform can probe it; everything else
under `/actuator` requires the operator credential.

#!/usr/bin/env bash
#
# Deploy Planning Poker to Google Cloud Run, sized to stay inside the
# always-free tier for a small team.
#
#   ./deploy.sh              deploy (generates secrets on first run)
#   ./deploy.sh --verify     deploy, then run the browser smoke against the URL
#   ./deploy.sh --dry-run    print the gcloud command without running it
#   ./deploy.sh --allow-new-secrets
#                            mint fresh secrets even when the existing service
#                            cannot be read (destroys access to encrypted data)
#
# Config via environment: SERVICE, REGION, PROJECT, MEMORY.
#
set -euo pipefail

SERVICE="${SERVICE:-planning-poker}"
REGION="${REGION:-us-central1}"
MEMORY="${MEMORY:-512Mi}"
PROJECT="${PROJECT:-}"

DRY_RUN=false
VERIFY=false
ALLOW_NEW_SECRETS=false
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    --verify)  VERIFY=true ;;
    --allow-new-secrets) ALLOW_NEW_SECRETS=true ;;
    -h|--help) sed -n '3,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")"

# ---------------------------------------------------------------- preflight

if ! command -v gcloud >/dev/null 2>&1; then
  cat >&2 <<'EOF'
gcloud is not installed. Install the Google Cloud CLI, then:
  gcloud auth login
  gcloud config set project YOUR_PROJECT_ID
  gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com
EOF
  exit 1
fi

if [ -z "$PROJECT" ]; then
  PROJECT="$(gcloud config get-value project 2>/dev/null || true)"
fi
if [ -z "$PROJECT" ] || [ "$PROJECT" = "(unset)" ]; then
  echo "No project set. Use PROJECT=my-project ./deploy.sh or 'gcloud config set project ...'." >&2
  exit 1
fi

# --------------------------------------------------------- secret handling
#
# Reuse whatever the deployed service already holds. Regenerating APP_DATA_KEY
# would make every existing at-rest column (session names, vote values,
# estimates, emails) permanently unreadable, and rotating APP_JWT_SECRET would
# sign out everyone mid-session. Only mint a key when there is genuinely none.
#
# "Genuinely none" has to mean the service does not exist — NOT merely that we
# failed to read it. An expired credential or a transient API error would
# otherwise look identical to a first deploy and silently replace the key, so
# an indeterminate answer stops the deploy instead of guessing.

DESCRIBE_ERR="$(mktemp)"
trap 'rm -f "$DESCRIBE_ERR"' EXIT

# Ask whether the service exists with `list`, not `describe`. A describe of a
# missing service is an error, whereas a filtered list answers "no such
# service" with exit 0 and no output — so a first deploy produces no error
# output at all, and there is no gcloud error text to pattern-match.
set +e
LIST_OUT="$(gcloud run services list \
  --project "$PROJECT" --region "$REGION" \
  --filter="metadata.name=$SERVICE" --format="value(metadata.name)" 2>"$DESCRIBE_ERR")"
LIST_RC=$?
set -e

SERVICE_EXISTS=false
DESCRIBE_JSON=""
INDETERMINATE=false

if [ "$LIST_RC" -eq 0 ]; then
  if printf '%s\n' "$LIST_OUT" | grep -qx "$SERVICE"; then
    SERVICE_EXISTS=true
  fi
else
  INDETERMINATE=true
fi

if [ "$SERVICE_EXISTS" = true ]; then
  set +e
  DESCRIBE_JSON="$(gcloud run services describe "$SERVICE" \
    --region "$REGION" --project "$PROJECT" --format=json 2>"$DESCRIBE_ERR")"
  DESCRIBE_RC=$?
  set -e
  if [ "$DESCRIBE_RC" -ne 0 ]; then
    # it exists but we cannot read it: never mint over a live service
    INDETERMINATE=true
  fi
fi

if [ "$INDETERMINATE" = true ]; then
  if [ "$ALLOW_NEW_SECRETS" = true ]; then
    SERVICE_EXISTS=false
    DESCRIBE_JSON=""
    echo "WARNING: could not read the existing service; continuing with fresh" >&2
    echo "secrets because --allow-new-secrets was given." >&2
  else
    {
      echo "Could not determine whether '$SERVICE' already exists in $REGION."
      echo
      echo "gcloud said:"
      sed 's/^/  /' "$DESCRIBE_ERR"
      echo
      echo "Refusing to continue. If the service does exist, deploying now would"
      echo "replace APP_DATA_KEY and make every encrypted column (session names,"
      echo "vote values, estimates, emails) permanently unreadable. Fix the error"
      echo "above and re-run, or pass --allow-new-secrets if there is genuinely"
      echo "nothing to preserve."
    } >&2
    exit 1
  fi
fi

read_deployed_env() {  # $1 = env var name
  [ "$SERVICE_EXISTS" = true ] || return 0
  printf '%s' "$DESCRIBE_JSON" | python3 -I -c "
import sys, json
try:
    spec = json.load(sys.stdin)['spec']['template']['spec']['containers'][0]
except Exception:
    sys.exit(0)
for entry in spec.get('env', []):
    if entry.get('name') == '$1':
        print(entry.get('value', ''))
        break
" 2>/dev/null || true
}

JWT_SECRET="$(read_deployed_env APP_JWT_SECRET)"
DATA_KEY="$(read_deployed_env APP_DATA_KEY)"
ACTUATOR_PASSWORD="$(read_deployed_env APP_ACTUATOR_PASSWORD)"

NEW_SECRETS=false
if [ -z "$JWT_SECRET" ]; then
  JWT_SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
  NEW_SECRETS=true
fi
if [ -z "$DATA_KEY" ]; then
  if [ "$SERVICE_EXISTS" = true ]; then
    echo "WARNING: '$SERVICE' is deployed but carries no APP_DATA_KEY, so it has" >&2
    echo "been running on the dev default. A new key is being generated; anything" >&2
    echo "already encrypted under the old one becomes unreadable." >&2
  fi
  # must decode to exactly 32 bytes; the app refuses to start otherwise
  DATA_KEY="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  NEW_SECRETS=true
fi
if [ -z "$ACTUATOR_PASSWORD" ]; then
  ACTUATOR_PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -d '\n')"
  NEW_SECRETS=true
fi

# A custom delimiter keeps a generated password containing a comma from being
# read as a second variable.
ENV_VARS="^##^APP_JWT_SECRET=${JWT_SECRET}##APP_DATA_KEY=${DATA_KEY}##APP_ACTUATOR_PASSWORD=${ACTUATOR_PASSWORD}"

# ------------------------------------------------------------------- deploy
#
# --max-instances=1 is a correctness requirement, not a cost control: the STOMP
# broker and the rate limiter are both in-process, so a second instance would
# split a session's participants and they would stop seeing each other's votes.
# --min-instances=0 is what makes an idle deployment free.

GCLOUD_ARGS=(
  run deploy "$SERVICE"
  --source .
  --project "$PROJECT"
  --region "$REGION"
  --platform managed
  --allow-unauthenticated
  --port 8080
  --memory "$MEMORY"
  --cpu 1
  --min-instances 0
  --max-instances 1
  --concurrency 80
  --timeout 3600          # WebSockets are long-lived; this is the per-request cap
  --set-env-vars "$ENV_VARS"
  --quiet
)

deploy_failure_hint() {
  # The raw gcloud error for the most common first-deploy failure is a wall of
  # IAM jargon. Printed unconditionally on failure rather than by matching the
  # output, because the deploy streams its progress and is not captured.
  PROJECT_NUMBER="$(gcloud projects describe "$PROJECT" --format='value(projectNumber)' 2>/dev/null || true)"
  BUILD_SA="${PROJECT_NUMBER:-PROJECT_NUMBER}-compute@developer.gserviceaccount.com"
  cat >&2 <<EOF

If the error above mentions storage.objects.get, Artifact Registry or denied
permissions, the build service account lacks the roles a source deploy needs.
Projects created recently do not grant them automatically:

  gcloud projects add-iam-policy-binding $PROJECT \\
    --member="serviceAccount:$BUILD_SA" \\
    --role="roles/cloudbuild.builds.builder"

IAM changes take a minute or two to take effect, then re-run this script.
EOF
}

if [ "$DRY_RUN" = true ]; then
  printf 'gcloud'
  for a in "${GCLOUD_ARGS[@]}"; do
    case "$a" in
      *APP_JWT_SECRET=*) printf " '^##^APP_JWT_SECRET=***##APP_DATA_KEY=***##APP_ACTUATOR_PASSWORD=***'" ;;
      *) printf ' %q' "$a" ;;
    esac
  done
  printf '\n'
  exit 0
fi

echo "Deploying $SERVICE to $REGION in $PROJECT ..."
set +e
gcloud "${GCLOUD_ARGS[@]}"
DEPLOY_RC=$?
set -e
if [ "$DEPLOY_RC" -ne 0 ]; then
  echo "gcloud run deploy failed with exit code $DEPLOY_RC" >&2
  deploy_failure_hint
  exit "$DEPLOY_RC"
fi

URL="$(gcloud run services describe "$SERVICE" --region "$REGION" --project "$PROJECT" \
        --format='value(status.url)')"

echo
echo "Deployed: $URL"

if [ "$NEW_SECRETS" = true ]; then
  cat <<EOF

New secrets were generated and are stored as environment variables on the
service. Keep this actuator password somewhere safe — it is the only way to
read /actuator/metrics:

  APP_ACTUATOR_PASSWORD=$ACTUATOR_PASSWORD

Later deploys reuse the existing values. Do not rotate APP_DATA_KEY unless you
have re-encrypted the data first: every encrypted column becomes unreadable.
EOF
fi

if [ "$VERIFY" = true ]; then
  echo
  echo "Running the browser smoke against the live URL ..."
  node .claude/skills/run-planning-poker/driver.mjs smoke --url "$URL"
fi

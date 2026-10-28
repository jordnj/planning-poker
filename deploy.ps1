<#
.SYNOPSIS
    Deploy Planning Poker to Google Cloud Run, sized to stay inside the
    always-free tier for a small team. PowerShell port of deploy.sh.

.DESCRIPTION
    Written for stock Windows PowerShell 5.1 as well as PowerShell 7, so it
    avoids 7-only syntax (ternaries, ??). Uses ConvertFrom-Json where the bash
    script shells out to python3, so nothing beyond gcloud is required.

.EXAMPLE
    .\deploy.ps1
    Deploy, generating secrets on the first run.

.EXAMPLE
    .\deploy.ps1 -Verify
    Deploy, then run the browser smoke against the deployed URL.

.EXAMPLE
    .\deploy.ps1 -DryRun
    Print the gcloud command, with secrets redacted, without running it.

.EXAMPLE
    .\deploy.ps1 -AllowNewSecrets
    Mint fresh secrets even when the existing service cannot be read. This
    destroys access to anything already encrypted at rest.
#>
[CmdletBinding()]
param(
    [switch] $DryRun,
    [switch] $Verify,
    [switch] $AllowNewSecrets,
    [string] $Service = $(if ($env:SERVICE) { $env:SERVICE } else { 'planning-poker' }),
    [string] $Region  = $(if ($env:REGION)  { $env:REGION }  else { 'us-central1' }),
    [string] $Memory  = $(if ($env:MEMORY)  { $env:MEMORY }  else { '512Mi' }),
    [string] $Project = $(if ($env:PROJECT) { $env:PROJECT } else { '' })
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# ------------------------------------------------------------------ helpers

function Write-Err {
    # deploy.sh sends diagnostics to stderr; Write-Host would put them on
    # stdout and break piping/CI parity, and Write-Error would throw under
    # $ErrorActionPreference = 'Stop'.
    param([string] $Message)
    [Console]::Error.WriteLine($Message)
}

function Invoke-GCloud {
    # Returns stdout; sets $script:LastGCloudExit. Native stderr is discarded
    # so a "service does not exist" probe is not treated as a failure.
    param([string[]] $GCloudArgs, [switch] $Quiet)
    $output = & gcloud @GCloudArgs 2>$null
    $script:LastGCloudExit = $LASTEXITCODE
    if (-not $Quiet) { return $output }
}

function New-RandomKey {
    # Cryptographic randomness. RandomNumberGenerator.Create()+GetBytes works
    # on .NET Framework (PS 5.1) and .NET (PS 7); Fill() is 7-only.
    param([int] $ByteCount)
    $bytes = New-Object byte[] $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

function Get-DeployedEnvValue {
    # Reads one env var off the deployed revision, or '' when the service or
    # the variable does not exist.
    param([string] $Name, [string] $Json)
    if ([string]::IsNullOrWhiteSpace($Json)) { return '' }
    try { $parsed = $Json | ConvertFrom-Json } catch { return '' }
    $containers = $parsed.spec.template.spec.containers
    if (-not $containers) { return '' }
    foreach ($entry in $containers[0].env) {
        if ($entry.name -eq $Name) {
            if ($null -eq $entry.value) { return '' }
            return [string] $entry.value
        }
    }
    return ''
}

# ---------------------------------------------------------------- preflight

if (-not (Get-Command gcloud -ErrorAction SilentlyContinue)) {
    Write-Err @'
gcloud is not installed. Install the Google Cloud CLI, then:
  winget install --id Google.CloudSDK -e
  gcloud init
  gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com

Use PowerShell rather than cmd.exe: cmd treats ^ as an escape character and
mangles the --set-env-vars delimiter this script relies on.
'@
    exit 1
}

if ([string]::IsNullOrWhiteSpace($Project)) {
    $Project = (Invoke-GCloud @('config', 'get-value', 'project') | Out-String).Trim()
}
if ([string]::IsNullOrWhiteSpace($Project) -or $Project -eq '(unset)') {
    Write-Err @'
No project set. Either:
  gcloud config set project YOUR_PROJECT_ID
or pass one:
  .\deploy.ps1 -Project YOUR_PROJECT_ID

A new project also needs a billing account linked before Cloud Run can be
enabled:
  gcloud billing accounts list
  gcloud billing projects link YOUR_PROJECT_ID --billing-account=XXXXXX-XXXXXX-XXXXXX
'@
    exit 1
}

# ----------------------------------------------------------- secret handling
#
# Reuse whatever the deployed service already holds. Regenerating APP_DATA_KEY
# would make every existing at-rest column (session names, vote values,
# estimates, emails) permanently unreadable, and rotating APP_JWT_SECRET would
# sign out everyone mid-session. Only mint a key when there is genuinely none.

$describeErr = [System.IO.Path]::GetTempFileName()
try {
    $describeJson = (& gcloud run services describe $Service `
        --region $Region --project $Project --format=json 2>$describeErr | Out-String)
    $describeExit = $LASTEXITCODE
    $describeError = ''
    if (Test-Path -LiteralPath $describeErr) {
        $describeError = (Get-Content -Raw -LiteralPath $describeErr -ErrorAction SilentlyContinue)
    }
    if ($null -eq $describeError) { $describeError = '' }
} finally {
    Remove-Item -LiteralPath $describeErr -ErrorAction SilentlyContinue
}

# "No secrets yet" must mean the service does not exist, NOT merely that we
# could not read it: an expired credential looks identical to a first deploy
# and would silently replace APP_DATA_KEY.
$serviceExists = $false
if ($describeExit -eq 0) {
    $serviceExists = $true
} elseif ($describeError -match '(?i)NOT_FOUND|cannot find service|could not be found|does not exist') {
    $serviceExists = $false
} elseif ($AllowNewSecrets) {
    $serviceExists = $false
    Write-Err 'WARNING: could not read the existing service; continuing with fresh secrets because -AllowNewSecrets was given.'
} else {
    Write-Err "Could not determine whether '$Service' already exists in $Region."
    Write-Err ''
    Write-Err 'gcloud said:'
    foreach ($line in ($describeError -split "`n")) {
        if ($line.Trim()) { Write-Err ('  ' + $line.TrimEnd()) }
    }
    Write-Err ''
    Write-Err @'
Refusing to continue. If the service does exist, deploying now would replace
APP_DATA_KEY and make every encrypted column (session names, vote values,
estimates, emails) permanently unreadable. Fix the error above and re-run, or
pass -AllowNewSecrets if there is genuinely nothing to preserve.
'@
    exit 1
}

if (-not $serviceExists) { $describeJson = '' }

$jwtSecret        = Get-DeployedEnvValue -Name 'APP_JWT_SECRET'        -Json $describeJson
$dataKey          = Get-DeployedEnvValue -Name 'APP_DATA_KEY'          -Json $describeJson
$actuatorPassword = Get-DeployedEnvValue -Name 'APP_ACTUATOR_PASSWORD' -Json $describeJson

$newSecrets = $false
if ([string]::IsNullOrWhiteSpace($jwtSecret)) {
    $jwtSecret = New-RandomKey 48
    $newSecrets = $true
}
if ([string]::IsNullOrWhiteSpace($dataKey)) {
    if ($serviceExists) {
        Write-Err @"
WARNING: '$Service' is deployed but carries no APP_DATA_KEY, so it has been
running on the dev default. A new key is being generated; anything already
encrypted under the old one becomes unreadable.
"@
    }
    # must decode to exactly 32 bytes; the app refuses to start otherwise
    $dataKey = New-RandomKey 32
    $newSecrets = $true
}
if ([string]::IsNullOrWhiteSpace($actuatorPassword)) {
    $actuatorPassword = New-RandomKey 24
    $newSecrets = $true
}

# A custom delimiter keeps a generated password containing a comma from being
# read as a second variable. Base64 never contains '#'.
$envVars = "^##^APP_JWT_SECRET=$jwtSecret##APP_DATA_KEY=$dataKey##APP_ACTUATOR_PASSWORD=$actuatorPassword"

# -------------------------------------------------------------------- deploy
#
# --max-instances=1 is a correctness requirement, not a cost control: the STOMP
# broker and the rate limiter are both in-process, so a second instance would
# split a session's participants and they would stop seeing each other's votes.
# --min-instances=0 is what makes an idle deployment free.
# --timeout 3600 is the per-request cap, and WebSockets are long-lived.

$deployArgs = @(
    'run', 'deploy', $Service,
    '--source', '.',
    '--project', $Project,
    '--region', $Region,
    '--platform', 'managed',
    '--allow-unauthenticated',
    '--port', '8080',
    '--memory', $Memory,
    '--cpu', '1',
    '--min-instances', '0',
    '--max-instances', '1',
    '--concurrency', '80',
    '--timeout', '3600',
    '--set-env-vars', $envVars,
    '--quiet'
)

if ($DryRun) {
    $redacted = $deployArgs | ForEach-Object {
        if ($_ -like '^##^APP_JWT_SECRET=*') {
            '^##^APP_JWT_SECRET=***##APP_DATA_KEY=***##APP_ACTUATOR_PASSWORD=***'
        } else { $_ }
    }
    Write-Host ('gcloud ' + ($redacted -join ' '))
    exit 0
}

Write-Host "Deploying $Service to $Region in $Project ..."
& gcloud @deployArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "gcloud run deploy failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

$url = (& gcloud run services describe $Service --region $Region --project $Project `
            --format='value(status.url)' | Out-String).Trim()

Write-Host ''
Write-Host "Deployed: $url"

if ($newSecrets) {
    Write-Host ''
    Write-Host @"
New secrets were generated and are stored as environment variables on the
service. Keep this actuator password somewhere safe — it is the only way to
read /actuator/metrics:

  APP_ACTUATOR_PASSWORD=$actuatorPassword

Later deploys reuse the existing values. Do not rotate APP_DATA_KEY unless you
have re-encrypted the data first: every encrypted column becomes unreadable.
"@
}

if ($Verify) {
    Write-Host ''
    Write-Host 'Running the browser smoke against the live URL ...'
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        Write-Host 'node is not installed; skipping. Check it by hand with:'
        Write-Host "  node .claude/skills/run-planning-poker/driver.mjs smoke --url $url"
        exit 0
    }
    & node (Join-Path '.claude' (Join-Path 'skills' (Join-Path 'run-planning-poker' 'driver.mjs'))) `
        'smoke' '--url' $url
    exit $LASTEXITCODE
}

<#
.SYNOPSIS
  Runs the Agentic FX demonstration against a running stack (docker compose up --build).

.DESCRIPTION
  1. Signs in as the seeded demo user and finds the "Europe Trip" goal.
  2. Improves BRL->EUR by 1.15%: the agent proposes R$2,000, policy ALLOWs, it executes automatically.
  3. Improves by 1.85%: the agent proposes R$3,500, policy REQUIRES APPROVAL.
  4. You approve (or -AutoApprove): the quote is revalidated, policy re-runs, the conversion executes.
  5. Prints balances and the audit timeline.

.EXAMPLE
  .\scripts\demo.ps1
  .\scripts\demo.ps1 -AutoApprove
#>
param(
    [string]$Api = "http://localhost:8080",
    [string]$Email = "demo@agentic.fx",
    [string]$Password = "demo-password",
    [switch]$AutoApprove
)

$ErrorActionPreference = "Stop"
$inv = [System.Globalization.CultureInfo]::InvariantCulture

function Step($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }
function Say($text, $color = "Gray") { Write-Host "   $text" -ForegroundColor $color }

function Call($method, $path, $body = $null, $key = $null) {
    $headers = @{ Authorization = "Bearer $script:token" }
    if ($key) { $headers["Idempotency-Key"] = $key }
    $params = @{ Method = $method; Uri = "$Api$path"; Headers = $headers; ContentType = "application/json" }
    if ($null -ne $body) { $params.Body = ($body | ConvertTo-Json -Depth 6) }
    Invoke-RestMethod @params
}

function WaitFor($what, [scriptblock]$condition, $seconds = 30) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $result = & $condition
        if ($result) { return $result }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for: $what"
}

function MoveRate([decimal]$factor) {
    $eur = (Call GET "/fx/rates") | Where-Object pair -eq "BRL_EUR"
    $target = [decimal]$eur.reference * $factor
    Call POST "/dev/fx/rates" @{ pair = "BRL_EUR"; rate = [double]$target } | Out-Null
    Say ("BRL->EUR {0} -> {1} (reference {2}, {3:+0.00;-0.00}%)" -f `
        ([decimal]$eur.rate).ToString("0.00000", $inv), $target.ToString("0.00000", $inv),
        ([decimal]$eur.reference).ToString("0.00000", $inv), (($factor - 1) * 100))
}

Step "Signing in as $Email"
$login = Invoke-RestMethod -Method POST -Uri "$Api/auth/token" -ContentType "application/json" `
    -Body (@{ email = $Email; password = $Password } | ConvertTo-Json)
$script:token = $login.accessToken
$goal = (Call GET "/goals") | Where-Object { $_.status -eq "ACTIVE" } | Select-Object -First 1
if (-not $goal) { throw "No active goal found. Start the stack with the dev profile (docker compose up)." }
Say "Goal: $($goal.name) - $($goal.acquired.display) of $($goal.targetAmount.display) by $($goal.deadline)"
$before = (Call GET "/goals/$($goal.id)").conversions.Count

Step "Step 1 - the market improves 1.15%"
MoveRate 1.0115
$detail = WaitFor "first conversion" { $d = Call GET "/goals/$($goal.id)"; if ($d.conversions.Count -gt $before) { $d } }
$c = $detail.conversions[0]
Say "Agent proposed and policy ALLOWED: $($c.sourceAmount.display) -> $($c.targetAmount.display) (executed automatically)" Green

Step "The market holds for 20 seconds (the agent does not buy again at the same level)"
Start-Sleep -Seconds 20
Say (Call GET "/goals/$($goal.id)").agent.message

Step "Step 2 - the market improves 1.85%"
MoveRate 1.0185
$approval = WaitFor "approval request" { (Call GET "/approvals") | Select-Object -First 1 }
$q = $approval.proposal.quote
Say "Agent recommendation: convert $($q.sourceAmount.display) -> $($q.targetAmount.display) at $($q.rate), fee $($q.fee.display)" Yellow
Say $approval.proposal.explanation.whatHappened
Say "Policy: $($approval.policy.decision) ($($approval.policy.reasonCodes -join ', '))" Yellow

if (-not $AutoApprove) {
    $answer = Read-Host "   Approve this conversion? [Y/n]"
    if ($answer -match '^[nN]') {
        Call POST "/approvals/$($approval.id)/reject" @{ reason = "Rejected in demo" } ([guid]::NewGuid()) | Out-Null
        Say "Rejected. The agent will not propose again during its cool-down." Red
        exit 0
    }
}

Step "Approving - quote revalidated, policy re-evaluated, conversion executed"
$outcome = Call POST "/approvals/$($approval.id)/approve" $null ([guid]::NewGuid())
Say "Policy: $($outcome.policy.decision) ($($outcome.policy.reasonCodes -join ', '))" Green
Say "Executed: $($outcome.conversion.sourceAmount.display) -> $($outcome.conversion.targetAmount.display)" Green

Step "Balances"
(Call GET "/accounts/me/balances") | ForEach-Object { Say "$($_.currency)  $($_.available.display)" }

Step "Audit timeline"
(Call GET "/goals/$($goal.id)/audit") | Select-Object -Last 14 | ForEach-Object {
    $time = ([datetime]$_.timestamp).ToLocalTime().ToString("HH:mm:ss")
    $policy = if ($_.policyDecision) { " [$($_.policyDecision)]" } else { "" }
    Say "$time  $($_.type)$policy"
}
Write-Host ""
Write-Host "Open http://localhost:3000 to see the same story in the dashboard." -ForegroundColor Cyan

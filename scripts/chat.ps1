param(
    [Parameter(Mandatory = $true)]
    [string]$Question,

    [string]$BaseUrl = "http://localhost:8080"
)

$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $utf8NoBom

$sessionResponse = Invoke-RestMethod -Uri "$BaseUrl/api/chat/sessions" -Method Post -ContentType "application/json" -Body "{}"
$sessionId = $sessionResponse.id
Write-Host "Sesja: $sessionId"

$body = @{ content = $Question } | ConvertTo-Json -Compress
$response = Invoke-RestMethod -Uri "$BaseUrl/api/chat/sessions/$sessionId/messages" -Method Post -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($body))

Write-Host ""
Write-Host "Odpowiedź:" -ForegroundColor Cyan
Write-Host $response.answer
Write-Host ""
Write-Host "Kroki agenta: $($response.steps.Count)" -ForegroundColor Yellow
foreach ($step in $response.steps) {
    Write-Host "  [$($step.stepIndex)] $($step.tool) ($($step.durationMs) ms) — $($step.outputSummary)"
}
Write-Host ""
Write-Host "Źródła: $($response.sources.Count) | Latencja: $($response.latencyMs) ms"

param(
    [Parameter(Mandatory = $true)]
    [string]$Question,

    [string]$BaseUrl = "http://localhost:8080"
)

$body = @{ question = $Question } | ConvertTo-Json -Compress

Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/ask" `
    -ContentType "application/json; charset=utf-8" `
    -Body $body

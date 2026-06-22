param(
    [Parameter(Mandatory = $true)]
    [string]$Question,

    [string]$BaseUrl = "http://localhost:8080"
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$body = @{ question = $Question } | ConvertTo-Json -Compress

$response = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/ask" `
    -ContentType "application/json; charset=utf-8" `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body))

$response | ConvertTo-Json -Depth 6 -Compress:$false

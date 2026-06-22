param(
    [string]$Folder = "data/documents",
    [string]$BaseUrl = "http://localhost:8080",
    [int]$PollSeconds = 2,
    [int]$TimeoutSeconds = 1800
)

$body = @{ folder = $Folder } | ConvertTo-Json -Compress

$job = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/documents/ingest-folder" `
    -ContentType "application/json; charset=utf-8" `
    -Body $body

Write-Host "Folder job: $($job.jobId)"
& "$PSScriptRoot\job-wait.ps1" -JobId $job.jobId -BaseUrl $BaseUrl -PollSeconds $PollSeconds -TimeoutSeconds $TimeoutSeconds

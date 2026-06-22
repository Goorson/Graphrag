param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [string]$BaseUrl = "http://localhost:8080",
    [int]$PollSeconds = 2,
    [int]$TimeoutSeconds = 600
)

$body = @{ path = $Path } | ConvertTo-Json -Compress

$job = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/documents" `
    -ContentType "application/json; charset=utf-8" `
    -Body $body

Write-Host "Job utworzony: $($job.jobId) status=$($job.status)"
& "$PSScriptRoot\job-wait.ps1" -JobId $job.jobId -BaseUrl $BaseUrl -PollSeconds $PollSeconds -TimeoutSeconds $TimeoutSeconds

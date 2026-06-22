param(
    [Parameter(Mandatory = $true)]
    [string]$File,

    [string]$BaseUrl = "http://localhost:8080",
    [int]$PollSeconds = 2,
    [int]$TimeoutSeconds = 600
)

if (-not (Test-Path $File)) {
    Write-Error "Plik nie istnieje: $File"
    exit 1
}

$job = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/api/documents/upload" `
    -Form @{ file = Get-Item $File }

Write-Host "Job utworzony: $($job.jobId) status=$($job.status)"
& "$PSScriptRoot\job-wait.ps1" -JobId $job.jobId -BaseUrl $BaseUrl -PollSeconds $PollSeconds -TimeoutSeconds $TimeoutSeconds

param(
    [Parameter(Mandatory = $true)]
    [string]$JobId,

    [string]$BaseUrl = "http://localhost:8080",
    [int]$PollSeconds = 2,
    [int]$TimeoutSeconds = 600
)

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)

while ((Get-Date) -lt $deadline) {
    $status = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/jobs/$JobId"
    Write-Host "[$($status.status)] progress=$($status.progressPct)% attempts=$($status.attempts)/$($status.maxAttempts)"

    if ($status.status -eq "DONE") {
        Write-Host "Gotowe. documentId=$($status.documentId)"
        if ($status.documentId) {
            $doc = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/documents/$($status.documentId)"
            Write-Host "graphStatus=$($doc.graphStatus)"
        }
        return $status
    }
    if ($status.status -eq "FAILED") {
        Write-Error "Job FAILED: $($status.errorMessage)"
        exit 1
    }

    Start-Sleep -Seconds $PollSeconds
}

Write-Error "Timeout po ${TimeoutSeconds}s"
exit 1

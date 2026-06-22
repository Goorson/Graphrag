param(
    [string]$BaseUrl = "http://localhost:8080"
)

$docs = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/documents"

foreach ($doc in $docs) {
    if ($doc.status -ne "INDEXED") {
        Write-Host "Pomijam $($doc.filename) — status=$($doc.status)"
        continue
    }
    Write-Host "Przebudowa grafu: $($doc.filename) ($($doc.id))..."
    try {
        Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/graph/rebuild/$($doc.id)" | Out-Null
        $updated = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/documents/$($doc.id)"
        Write-Host "  graphStatus=$($updated.graphStatus)"
    } catch {
        Write-Warning "  Błąd: $($_.Exception.Message)"
    }
}

Write-Host "Gotowe."

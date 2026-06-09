# download_species_images.ps1
# Run once from the project root to download all species images into assets.
# Usage:  powershell -ExecutionPolicy Bypass -File download_species_images.ps1
#
# Downloads full-resolution images from Wikimedia Commons with rate-limit
# respect: 3-second delay between requests + exponential backoff on 429s.

$ErrorActionPreference = "Continue"
$imgDir = Join-Path $PSScriptRoot "app\src\main\assets\species_images"
if (-not (Test-Path $imgDir)) { New-Item -ItemType Directory -Path $imgDir -Force | Out-Null }

$manifest = Join-Path $imgDir "download_manifest.txt"
if (-not (Test-Path $manifest)) {
    Write-Error "download_manifest.txt not found at $manifest"
    exit 1
}

$lines = Get-Content $manifest | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$total = $lines.Count
$ok = 0; $fail = 0; $skipped = 0
$delaySeconds = 3
$maxRetries = 4

Write-Host "`n=== Downloading $total species images (full-res, throttled) ===" -ForegroundColor Cyan
Write-Host "    $delaySeconds sec between requests to respect Wikimedia rate limits`n" -ForegroundColor DarkGray

foreach ($line in $lines) {
    $parts = $line -split '\|', 2
    $fname = $parts[0].Trim()
    $url   = $parts[1].Trim()
    $dest  = Join-Path $imgDir $fname

    # Skip files already downloaded
    if (Test-Path $dest) {
        $size = (Get-Item $dest).Length
        if ($size -gt 5000) {
            Write-Host "  SKIP $fname ($([math]::Round($size/1024))KB)" -ForegroundColor DarkGray
            $ok++
            $skipped++
            continue
        } else {
            Remove-Item $dest -Force
        }
    }

    $success = $false
    for ($attempt = 1; $attempt -le $maxRetries; $attempt++) {
        Write-Host "  [$([math]::Floor(($ok + $fail + 1)))/$total] $fname " -NoNewline

        if ($attempt -gt 1) {
            Write-Host "(retry $attempt) " -NoNewline -ForegroundColor Yellow
        }

        try {
            $headers = @{
                "User-Agent" = "MycilliyumsApp/1.0 (jack@mazlabz.ai; one-time asset bundle; Kotlin/Android)"
            }
            Invoke-WebRequest -Uri $url -OutFile $dest -Headers $headers -TimeoutSec 60 -ErrorAction Stop
            $size = (Get-Item $dest).Length
            Write-Host "OK ($([math]::Round($size/1024))KB)" -ForegroundColor Green
            $ok++
            $success = $true
            break
        } catch {
            $msg = $_.Exception.Message
            if ($msg -match "429") {
                # Rate limited — back off exponentially
                $backoff = $delaySeconds * [math]::Pow(2, $attempt)
                Write-Host "rate-limited, waiting ${backoff}s..." -ForegroundColor Yellow
                Start-Sleep -Seconds $backoff
            } else {
                Write-Host "FAIL: $msg" -ForegroundColor Red
                break
            }
        }
    }

    if (-not $success) {
        $fail++
        if (Test-Path $dest) { Remove-Item $dest -Force }
    }

    # Polite delay between requests
    if ($ok + $fail -lt $total) {
        Start-Sleep -Seconds $delaySeconds
    }
}

Write-Host "`n=== Done: $ok succeeded ($skipped already existed), $fail failed out of $total ===" -ForegroundColor Cyan
if ($fail -eq 0) {
    Write-Host "All images downloaded. Build the app." -ForegroundColor Green
} else {
    Write-Host "Re-run the script to retry the $fail failed downloads (existing files are skipped)." -ForegroundColor Yellow
}

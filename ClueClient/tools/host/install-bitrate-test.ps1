# Installs Clue's bitrate test app into Vibepollo/Apollo/Sunshine on this PC (run once, elevated).
#  - copies bitrate-test.html to C:\ProgramData\Clue
#  - adds an app named "Clue Bitrate Test" to the host's apps.json that opens it full screen in an
#    isolated Chrome profile (no account, no sync)
#  - restarts the host service so the app shows up in the client's app list
# Usage (elevated PowerShell): .\install-bitrate-test.ps1 [-ConfigDir "C:\Program Files\Apollo\config"] [-Service ApolloService]
param(
    [string]$ConfigDir = 'C:\Program Files\Apollo\config',
    [string]$Service = 'ApolloService',
    [string]$Log = ''
)
$ErrorActionPreference = 'Stop'
function Say($m) { if ($Log) { $m | Out-File -FilePath $Log -Encoding utf8 -Append } else { $m } }
try {
    $here = Split-Path -Parent $MyInvocation.MyCommand.Path
    $dataDir = 'C:\ProgramData\Clue'
    New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
    Copy-Item -LiteralPath (Join-Path $here 'bitrate-test.html') -Destination (Join-Path $dataDir 'bitrate-test.html') -Force

    $chrome = @("$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
                "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe") |
              Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $chrome) { throw 'chrome.exe not found' }
    $page = 'file:///C:/ProgramData/Clue/bitrate-test.html'
    $cmd = "`"$chrome`" --user-data-dir=`"$dataDir\chrome-bitrate`" --no-first-run --no-default-browser-check --disable-sync --kiosk --app=$page"

    $appsPath = Join-Path $ConfigDir 'apps.json'
    Copy-Item -LiteralPath $appsPath -Destination "$appsPath.clue-backup" -Force
    $raw = Get-Content -LiteralPath $appsPath -Raw
    $parsed = $raw | ConvertFrom-Json   # validation only; the file is edited as text
    if ($parsed.apps | Where-Object { $_.name -eq 'Clue Bitrate Test' }) {
        Say "OK already installed ($($parsed.apps.Count) apps)"
    } else {
        # Insert one object before the bracket that closes "apps", leaving every other byte as is
        # (re-serializing with ConvertTo-Json would flatten one-element arrays such as "detached").
        $start = $raw.IndexOf('"apps"')
        $open = $raw.IndexOf('[', $start)
        $depth = 0; $inStr = $false; $esc = $false; $close = -1
        for ($i = $open; $i -lt $raw.Length; $i++) {
            $ch = $raw[$i]
            if ($inStr) { if ($esc) { $esc = $false } elseif ($ch -eq '\') { $esc = $true } elseif ($ch -eq '"') { $inStr = $false }; continue }
            if ($ch -eq '"') { $inStr = $true } elseif ($ch -eq '[' -or $ch -eq '{') { $depth++ } elseif ($ch -eq ']' -or $ch -eq '}') { $depth--; if ($depth -eq 0) { $close = $i; break } }
        }
        if ($close -lt 0) { throw 'could not find the end of the apps array' }
        $entry = [ordered]@{ name = 'Clue Bitrate Test'; cmd = $cmd; 'exit-timeout' = 2; uuid = 'C1E0B17E-0000-4000-8000-000000000001' } | ConvertTo-Json
        $before = $raw.Substring(0, $close).TrimEnd()
        $sep = if ($before.EndsWith('[')) { '' } else { ',' }
        $new = $before + $sep + "`r`n" + $entry + "`r`n    " + $raw.Substring($close)
        $null = $new | ConvertFrom-Json   # must still be valid JSON
        [System.IO.File]::WriteAllText($appsPath, $new, (New-Object System.Text.UTF8Encoding($false)))
        Say "OK added ($($parsed.apps.Count + 1) apps)"
    }
    Restart-Service -Name $Service -Force
    Start-Sleep -Seconds 3
    Say "OK $(Get-Date -Format o) service=$((Get-Service -Name $Service).Status)"
} catch {
    Say "ERROR $(Get-Date -Format o) $($_.Exception.Message)"
}

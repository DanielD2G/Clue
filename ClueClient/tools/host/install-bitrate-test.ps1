# Installs (or updates) Clue's bitrate test app in Vibepollo/Apollo/Sunshine on this PC. Run elevated.
#  - copies bitrate-test.html to C:\ProgramData\Clue
#  - adds an app named "Clue Bitrate Test" to the host's apps.json (replacing an earlier one) that
#    opens the page full screen in an isolated Chrome profile (no account, no sync)
#  - restarts the host service so the app shows up in the client's app list
# Usage (elevated PowerShell): .\install-bitrate-test.ps1 [-ConfigDir "C:\Program Files\Apollo\config"] [-Service ApolloService] [-Log file]
param(
    [string]$ConfigDir = 'C:\Program Files\Apollo\config',
    [string]$Service = 'ApolloService',
    [string]$Log = ''
)
$ErrorActionPreference = 'Stop'
function Say($m) { if ($Log) { $m | Out-File -FilePath $Log -Encoding utf8 -Append } else { $m } }

# Index of the bracket that closes the JSON array opening at $open (skips strings).
function Find-Close([string]$text, [int]$open) {
    $depth = 0; $inStr = $false; $esc = $false
    for ($i = $open; $i -lt $text.Length; $i++) {
        $ch = $text[$i]
        if ($inStr) {
            if ($esc) { $esc = $false } elseif ($ch -eq '\') { $esc = $true } elseif ($ch -eq '"') { $inStr = $false }
            continue
        }
        if ($ch -eq '"') { $inStr = $true }
        elseif ($ch -eq '[' -or $ch -eq '{') { $depth++ }
        elseif ($ch -eq ']' -or $ch -eq '}') { $depth--; if ($depth -eq 0) { return $i } }
    }
    return -1
}

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
    $profile = Join-Path $dataDir 'chrome-bitrate'
    # Chrome must keep animating even when Windows reports the window as hidden: on a resumed
    # session the stream's display is recreated, and Chrome would otherwise freeze the page.
    $flags = @('--no-first-run', '--no-default-browser-check', '--disable-sync',
               '--disable-renderer-backgrounding', '--disable-background-timer-throttling',
               '--disable-backgrounding-occluded-windows', '--kiosk', "--app=$page")
    $cmd = "`"$chrome`" --user-data-dir=`"$profile`" " + ($flags -join ' ')

    $appsPath = Join-Path $ConfigDir 'apps.json'
    Copy-Item -LiteralPath $appsPath -Destination "$appsPath.clue-backup" -Force
    $raw = Get-Content -LiteralPath $appsPath -Raw
    $null = $raw | ConvertFrom-Json   # validation only; the file is edited as text

    # Drop an earlier entry of ours (a flat object), so re-running updates the command.
    $existing = [regex]::Match($raw, '"name"\s*:\s*"Clue Bitrate Test"')
    if ($existing.Success) {
        $objStart = $raw.LastIndexOf('{', $existing.Index)
        $objEnd = $raw.IndexOf('}', $existing.Index)
        $cut = $objStart
        $prev = $raw.Substring(0, $objStart).TrimEnd()
        if ($prev.EndsWith(',')) { $cut = $prev.Length - 1 }
        $raw = $raw.Substring(0, $cut) + $raw.Substring($objEnd + 1)
        $null = $raw | ConvertFrom-Json
    }

    # Insert one object before the bracket that closes "apps", leaving every other byte as is
    # (re-serializing with ConvertTo-Json would flatten one-element arrays such as "detached").
    $open = $raw.IndexOf('[', $raw.IndexOf('"apps"'))
    $close = Find-Close $raw $open
    if ($close -lt 0) { throw 'could not find the end of the apps array' }
    $entry = [ordered]@{ name = 'Clue Bitrate Test'; cmd = $cmd; 'exit-timeout' = 2; uuid = 'C1E0B17E-0000-4000-8000-000000000001' } | ConvertTo-Json
    $before = $raw.Substring(0, $close).TrimEnd()
    $sep = if ($before.EndsWith('[')) { '' } else { ',' }
    $new = $before + $sep + "`r`n" + $entry + "`r`n    " + $raw.Substring($close)
    $count = ($new | ConvertFrom-Json).apps.Count   # must still be valid JSON
    [System.IO.File]::WriteAllText($appsPath, $new, (New-Object System.Text.UTF8Encoding($false)))
    Say "OK $(if ($existing.Success) { 'updated' } else { 'added' }) ($count apps)"

    Restart-Service -Name $Service -Force
    Start-Sleep -Seconds 3
    Say "OK $(Get-Date -Format o) service=$((Get-Service -Name $Service).Status)"
} catch {
    Say "ERROR $(Get-Date -Format o) $($_.Exception.Message)"
}

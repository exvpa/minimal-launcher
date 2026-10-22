<#
  Bravia (Google TV) clean-up helper for the Sony XR-55A83J.
  Everything here uses "pm disable-user", which is undone by "restore" or a factory reset.
  Nothing changes on the TV unless you pass -Apply.

  .\tv.ps1 connect  -Ip 192.168.1.50
  .\tv.ps1 scan                                   # saves a package list to share/review
  .\tv.ps1 debloat                                # dry run: shows what would be disabled
  .\tv.ps1 debloat  -Apply [-IncludeOptional]
  .\tv.ps1 launcher -Package com.spocky.projengmenu -Apply   # make another launcher the Home screen
  .\tv.ps1 stock-launcher -Apply                  # put Google TV Home back
  .\tv.ps1 restore  -Apply                        # re-enable everything this script disabled
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('connect', 'scan', 'debloat', 'launcher', 'stock-launcher', 'restore')]
    [string]$Action,
    [string]$Ip,
    [string]$Package,
    [switch]$IncludeOptional,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$LogFile = Join-Path $PSScriptRoot 'disabled-packages.txt'

# Ads, promotions and Google extras. Safe to disable on Google TV.
$Recommended = @(
    'tv.samba.ssm',                       # Samba TV: tracks what's on screen for ad targeting
    'tv.samba.ssm.ui',
    'com.sonyericsson.idd.agent',         # Sony usage-data reporting
    'com.sony.dtv.promos',                # Sony promotional content
    'com.sony.dtv.sonyselect',            # Sony Select app promotions
    'com.sony.dtv.demosupport',           # Store demo mode
    'com.sony.dtv.multiscreendemo',       # Store demo mode
    'com.tcl.channelplus',                # TCL Channel+ (free channels with ads)
    'com.tcl.waterfall.overseas',         # TCL Channel feed
    'com.tcl.messagebox',                 # TCL promotional messages
    'com.tcl.exhibit',                    # Store demo mode
    'com.tcl.esticker',                   # Store "sticker" overlay
    'com.amazon.amazonvideo.livingroom',  # Prime Video (not used)
    'com.google.android.play.games',      # Play Games
    'com.google.android.videos',          # Google TV / Play Movies storefront
    'com.google.android.youtube.tvmusic', # YouTube Music
    'com.google.android.apps.tv.dreamx'   # Ambient mode (screensaver with suggestions); the basic screensaver remains
)

# Preinstalled streaming apps. Only disable the ones you don't use (edit this list).
$Optional = @(
    'com.netflix.ninja',                  # Netflix (and the remote's Netflix button below)
    'com.netflix.partner.ncm'
)

# Never disabled by this script: settings, inputs, Play Store, voice, casting, core Google services.
$Protected = 'settings|systemui|gms|gsf|vending|katniss|mediashell|tvx|inputmethod|packageinstaller|providers|launcherx|setupwraith'

$StockLauncher = @('com.google.android.apps.tv.launcherx', 'com.google.android.tungsten.setupwraith')

function Invoke-Adb {
    $out = & adb @args
    if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') failed: $out" }
    $out
}

function Assert-Connected {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        throw 'adb is not installed. Run: winget install Google.PlatformTools  (then open a new terminal)'
    }
    $devices = Invoke-Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
    if (-not $devices) { throw 'No TV connected. Run: .\tv.ps1 connect -Ip <TV IP address>' }
    if (@($devices).Count -gt 1) { throw "More than one device connected:`n$($devices -join "`n")" }
    # One restore log per TV
    $serial = (@($devices)[0] -split "`t")[0] -replace '[^\w.]', '_'
    $script:LogFile = Join-Path $PSScriptRoot "disabled-packages-$serial.txt"
}

function Get-Installed {
    Invoke-Adb shell pm list packages | ForEach-Object { $_ -replace '^package:', '' }
}

function Confirm-Apply([string]$what) {
    if (-not $Apply) { Write-Host "`nDry run. Add -Apply to $what." -ForegroundColor Yellow; return $false }
    $true
}

switch ($Action) {
    'connect' {
        if (-not $Ip) { throw 'Pass -Ip <TV IP address> (Settings > Network & Internet on the TV).' }
        Invoke-Adb connect "${Ip}:5555"
        Write-Host 'If it says "unauthorized", accept the "Allow ADB/USB debugging?" prompt on the TV, tick "Always allow", and run connect again.'
    }

    'scan' {
        Assert-Connected
        $file = Join-Path $PSScriptRoot ("scan-{0:yyyyMMdd-HHmm}.txt" -f (Get-Date))
        $lines = @('# Model / build')
        $lines += Invoke-Adb shell getprop ro.product.model
        $lines += Invoke-Adb shell getprop ro.build.fingerprint
        $lines += '', '# Home screen activities'
        try { $lines += Invoke-Adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME }
        catch { $lines += "(could not query: $_)" }
        $lines += '', '# User-installed / updatable apps (-3)'
        $lines += Invoke-Adb shell pm list packages -3 | Sort-Object
        $lines += '', '# System apps (-s)'
        $lines += Invoke-Adb shell pm list packages -s | Sort-Object
        $lines += '', '# Already disabled (-d)'
        $lines += Invoke-Adb shell pm list packages -d | Sort-Object
        $lines | Set-Content -Path $file -Encoding utf8
        Write-Host "Saved $file"
    }

    'debloat' {
        Assert-Connected
        $installed = Get-Installed
        # Skip what's already disabled, so "restore" only re-enables what this script turned off
        $disabled = Invoke-Adb shell pm list packages -d | ForEach-Object { $_ -replace '^package:', '' }
        $targets = $Recommended
        if ($IncludeOptional) { $targets += $Optional }
        $targets = $targets | Where-Object { $_ -notmatch $Protected -and $installed -contains $_ -and $disabled -notcontains $_ }
        if (-not $targets) { Write-Host 'Nothing to disable.'; break }
        Write-Host 'Will disable:'; $targets | ForEach-Object { Write-Host "  $_" }
        if (-not (Confirm-Apply 'disable them')) { break }
        foreach ($p in $targets) {
            $r = Invoke-Adb shell pm disable-user --user 0 $p
            Write-Host "  $r"
            Add-Content -Path $LogFile -Value $p -Encoding utf8
        }
    }

    'launcher' {
        Assert-Connected
        if (-not $Package) { throw 'Pass -Package <launcher package>, e.g. com.spocky.projengmenu (Projectivy).' }
        if ((Get-Installed) -notcontains $Package) { throw "$Package is not installed. Install it from the Play Store on the TV first." }
        Write-Host "Will disable Google TV Home ($($StockLauncher -join ', ')) so the Home button opens $Package."
        if (-not (Confirm-Apply 'switch launchers')) { break }
        foreach ($p in $StockLauncher) {
            Invoke-Adb shell pm disable-user --user 0 $p | Write-Host
            Add-Content -Path $LogFile -Value $p -Encoding utf8
        }
        Invoke-Adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Null
        Write-Host 'Done. Press Home on the remote to check. Undo with: .\tv.ps1 stock-launcher -Apply'
    }

    'stock-launcher' {
        Assert-Connected
        if (-not (Confirm-Apply 're-enable Google TV Home')) { break }
        foreach ($p in $StockLauncher) { Invoke-Adb shell pm enable $p | Write-Host }
    }

    'restore' {
        Assert-Connected
        if (-not (Test-Path $LogFile)) { Write-Host 'Nothing recorded in disabled-packages.txt.'; break }
        $targets = Get-Content $LogFile | Where-Object { $_ } | Sort-Object -Unique
        Write-Host 'Will re-enable:'; $targets | ForEach-Object { Write-Host "  $_" }
        if (-not (Confirm-Apply 're-enable them')) { break }
        foreach ($p in $targets) { Invoke-Adb shell pm enable $p | Write-Host }
        Remove-Item $LogFile
    }
}

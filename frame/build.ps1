<#
  Builds frame.apk without Gradle: aapt2 (manifest) -> javac -> d8 -> zipalign -> apksigner.
  Needs the Android SDK (platform 34, build-tools 35.0.0) and a JDK.

  .\build.ps1             # build out\frame.apk
  .\build.ps1 -Install    # build, install on the connected TV and open it
#>
param([switch]$Install)

$ErrorActionPreference = 'Stop'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\35.0.0"
$androidJar = "$sdk\platforms\android-34\android.jar"
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-23'
$env:Path = "$env:JAVA_HOME\bin;" + [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [Environment]::GetEnvironmentVariable('Path', 'Machine')

$root = $PSScriptRoot
$out = Join-Path $root 'out'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

function Check([string]$step) { if ($LASTEXITCODE -ne 0) { throw "$step failed" } }

# Icon libraries: the source PNGs in icons\ (white), icons-color\ and icons-mac\ are large, so ship 256px copies in assets\
$iconSets = 'icons', 'icons-color', 'icons-mac'
Add-Type -AssemblyName System.Drawing
foreach ($set in $iconSets) {
New-Item -ItemType Directory -Force "$out\assets\$set" | Out-Null
foreach ($file in Get-ChildItem "$root\$set" -Filter *.png) {
    $src = [System.Drawing.Image]::FromFile($file.FullName)
    $scale = [Math]::Min(1.0, 256.0 / [Math]::Max($src.Width, $src.Height))
    $w = [int][Math]::Round($src.Width * $scale); $h = [int][Math]::Round($src.Height * $scale)
    $dst = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($dst)
    $g.InterpolationMode = 'HighQualityBicubic'; $g.PixelOffsetMode = 'HighQuality'; $g.CompositingMode = 'SourceCopy'
    $g.DrawImage($src, 0, 0, $w, $h)
    $g.Dispose(); $src.Dispose()
    $dst.Save("$out\assets\$set\$($file.Name)", [System.Drawing.Imaging.ImageFormat]::Png)
    $dst.Dispose()
}
}

# Assets are zipped in below: aapt2 -A on Windows stores "assets/icons\x.png" paths that Android can't open
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $androidJar --manifest "$root\AndroidManifest.xml" `
    --min-sdk-version 28 --target-sdk-version 34 --version-code 2 --version-name 0.2
Check 'aapt2 link'

$sources = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
# java.* comes from the JDK's Java 8 API (android.jar lacks the lambda bootstrap); d8 desugars lambdas
& javac --release 8 -Xlint:-options -encoding UTF-8 -classpath $androidJar -d "$out\classes" $sources
Check 'javac'

$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --release --min-api 28 --lib $androidJar --output "$out\dex" $classes
Check 'd8'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex') | Out-Null
# Inter (SIL Open Font License, see fonts\OFL.txt), the open font closest to SF Pro
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$root\fonts\Inter.ttf", 'assets/fonts/Inter.ttf') | Out-Null
foreach ($set in $iconSets) {
    foreach ($icon in Get-ChildItem "$out\assets\$set" -Filter *.png) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $icon.FullName, "assets/$set/$($icon.Name)") | Out-Null
    }
}
$zip.Dispose()

& "$bt\zipalign.exe" -f -p 4 "$out\base.apk" "$out\aligned.apk"
Check 'zipalign'

$keystore = Join-Path $root 'debug.keystore'
if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -keystore $keystore -storepass android -keypass android -alias frame `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Frame' | Out-Null
    Check 'keytool'
}
& "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:android --out "$out\frame.apk" "$out\aligned.apk"
Check 'apksigner'
Write-Host "Built $out\frame.apk"

if ($Install) {
    & adb install -r "$out\frame.apk"
    Check 'adb install'
    & adb shell am start -n com.frame.home/.HomeActivity | Out-Null
}


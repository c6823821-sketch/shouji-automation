$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = 'E:\Android\Sdk'
$bt = Join-Path $sdk 'build-tools\android-14'
$androidJar = Join-Path $sdk 'platforms\android-34\android.jar'
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$build = Join-Path $root "build_$stamp"
$gen = Join-Path $build 'gen'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $gen, $classes, $dex, $dist | Out-Null

$compiled = Join-Path $build 'resources.zip'
& (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $root 'res') -o $compiled
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

$unsigned = Join-Path $build 'unsigned.apk'
& (Join-Path $bt 'aapt2.exe') link -o $unsigned -I $androidJar --manifest (Join-Path $root 'AndroidManifest.xml') --java $gen --min-sdk-version 26 --target-sdk-version 34 --version-code 4 --version-name 1.1.2 --auto-add-overlay -R $compiled
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

$sources = @()
$sources += Get-ChildItem -Path (Join-Path $root 'java') -Filter '*.java' -Recurse -File | ForEach-Object FullName
$sources += Get-ChildItem -Path $gen -Filter '*.java' -Recurse -File | ForEach-Object FullName
& javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath $androidJar -classpath $androidJar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$classesJar = Join-Path $build 'classes.jar'
& jar cf $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

& (Join-Path $bt 'd8.bat') --lib $androidJar --min-api 26 --output $dex $classesJar
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Push-Location $dex
try {
    & (Join-Path $bt 'aapt.exe') add $unsigned 'classes.dex'
    if ($LASTEXITCODE -ne 0) { throw 'aapt add failed' }
} finally {
    Pop-Location
}

$aligned = Join-Path $build 'aligned.apk'
& (Join-Path $bt 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$ks = Join-Path $root 'toutiao-thanks.jks'
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -v -keystore $ks -storepass toutiao123 -keypass toutiao123 -alias toutiaothanks -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Toutiao Thanks, OU=Personal, O=Codex, L=Beijing, S=Beijing, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

$signed = Join-Path $dist 'toutiao-thanks-v1.1.2.apk'
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-key-alias toutiaothanks --ks-pass pass:toutiao123 --key-pass pass:toutiao123 --out $signed $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& (Join-Path $bt 'apksigner.bat') verify --verbose $signed
if ($LASTEXITCODE -ne 0) { throw 'apk verify failed' }
Write-Output "APK=$signed"
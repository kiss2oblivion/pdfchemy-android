#Requires -Version 7.0
param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $AndroidSdk) { throw 'Pass -AndroidSdk with the installed Android SDK directory.' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo '.security-runtime/release-smoke-2026-10-07' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$apk = Join-Path $repo 'app-host/build/outputs/apk/release/app-host-release.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Build a signed assembleRelease first.' }
$apksigner = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
    Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $apksigner) { throw 'Android apksigner was not found.' }
$certificate = (& $apksigner verify --print-certs $apk 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$expected = 'f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b'
if ($certificate -notmatch [regex]::Escape("certificate SHA-256 digest: $expected")) {
    throw 'Signing fingerprint changed; reconcile the signing report before packaging.'
}
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Output exists; choose a new directory to preserve previous test evidence.' }
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$evidence = Join-Path $repo 'reports/release-certification-2026-10-07'
Copy-Item -LiteralPath $apk -Destination $OutputDirectory
Copy-Item -LiteralPath (Join-Path $evidence 'physical-device-smoke.md') -Destination $OutputDirectory
Copy-Item -LiteralPath (Join-Path $evidence 'signing/current-upload-certificate.pem') -Destination $OutputDirectory
Copy-Item -LiteralPath (Join-Path $evidence 'corpus') -Destination (Join-Path $OutputDirectory 'producer-evidence') -Recurse
Copy-Item -LiteralPath (Join-Path $repo 'app-host/src/androidTest/assets/vanguard-benign') -Destination (Join-Path $OutputDirectory 'corpus') -Recurse
foreach ($name in @('tool-smoke-matrix.md', 'tool-smoke-matrix.json')) {
    Copy-Item -LiteralPath (Join-Path $repo "reports/release-candidate-2026-10-06/$name") -Destination $OutputDirectory
}
$utf8 = [Text.UTF8Encoding]::new($false)
$rows = Get-Content -LiteralPath (Join-Path $OutputDirectory 'tool-smoke-matrix.json') -Raw | ConvertFrom-Json
$results = $rows | ForEach-Object { [pscustomobject]@{ screen = $_.screen; status = 'NOT RUN'; device = ''; tester = ''; evidence = ''; defects = '' } }
[IO.File]::WriteAllLines((Join-Path $OutputDirectory 'tool-results.csv'), ($results | ConvertTo-Csv -NoTypeInformation), $utf8)
$fixtures = Get-ChildItem -LiteralPath (Join-Path $OutputDirectory 'corpus') -Filter '*.pdf' | Sort-Object Name
$corpusResults = $fixtures | ForEach-Object { [pscustomobject]@{ file = $_.Name; status = 'NOT RUN'; device = ''; vanguard = ''; rendered = ''; navigation = ''; evidence = '' } }
[IO.File]::WriteAllLines((Join-Path $OutputDirectory 'corpus-results.csv'), ($corpusResults | ConvertTo-Csv -NoTypeInformation), $utf8)
$audioCases = @('short-export', 'long-unicode', 'reader-speech-isolation', 'picker-cancel', 'early-cancel',
    'mid-synthesis-cancel', 'publication-cancel', 'background-lock', 'api34-type', 'api35-type-timeout',
    'missing-voice', 'storage-pressure', 'saf-providers', 'concurrent-export', 'process-interruption')
$audioResults = $audioCases | ForEach-Object { [pscustomobject]@{ case = $_; status = 'NOT RUN'; device = ''; tester = ''; evidence = ''; defects = '' } }
[IO.File]::WriteAllLines((Join-Path $OutputDirectory 'audio-results.csv'), ($audioResults | ConvertTo-Csv -NoTypeInformation), $utf8)
$head = & git -C $repo rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify repository HEAD.' }
$trackedChanges = & git -C $repo status --porcelain --untracked-files=no
$hashes = Get-ChildItem -LiteralPath $OutputDirectory -File -Recurse | ForEach-Object {
    [pscustomobject]@{ file = [IO.Path]::GetRelativePath($OutputDirectory, $_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$receipt = [ordered]@{
    head = "$head"; tracked_tree_dirty = [bool]$trackedChanges
    packaged_utc = [DateTime]::UtcNow.ToString('o'); version = '2.0.6 (13)'
    signature_sha256 = $expected; apk_signature_verified = $true
    physical_execution = 'NOT RUN'; play_upload_registration = 'UNVERIFIED'; files = @($hashes)
}
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'package-receipt.json'), ($receipt | ConvertTo-Json -Depth 5), $utf8)
Write-Output "Signed smoke package: $OutputDirectory"

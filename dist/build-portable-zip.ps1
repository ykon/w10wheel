param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SbtCommand = "sbt",
    [string]$AppVersion = "",
    [switch]$SkipSbt
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path (Join-Path $JavaHome "bin\jpackage.exe"))) {
    $FallbackJavaHome = "C:\Program Files\Java\jdk-25.0.5.1"
    if (Test-Path (Join-Path $FallbackJavaHome "bin\jpackage.exe")) {
        $JavaHome = $FallbackJavaHome
    }
    else {
        throw "jpackage.exe not found. Set -JavaHome or JAVA_HOME to a JDK 25 installation."
    }
}

$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
$jpackageExe = Join-Path $JavaHome "bin\jpackage.exe"

if (-not $SkipSbt) {
    if ($SbtCommand -eq "sbt" -and -not (Get-Command sbt -ErrorAction SilentlyContinue)) {
        $FallbackSbt = "C:\sbt\bin\sbt.bat"
        if (Test-Path $FallbackSbt) {
            $SbtCommand = $FallbackSbt
        }
        else {
            throw "sbt command not found on PATH and fallback '$FallbackSbt' does not exist."
        }
    }

    Push-Location $RepoRoot
    try {
        & $SbtCommand --server stageJpackageInput
        if ($LASTEXITCODE -ne 0) {
            throw "sbt stageJpackageInput failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

$jpackageDir = Join-Path $RepoRoot "target\jpackage"
$inputDir = Join-Path $jpackageDir "input"
$mainJarFile = Join-Path $jpackageDir "main-jar.txt"
$versionFile = Join-Path $jpackageDir "version.txt"

if (-not (Test-Path $inputDir)) {
    throw "Staged input directory not found: $inputDir"
}
if (-not (Test-Path $mainJarFile)) {
    throw "main-jar.txt not found: $mainJarFile"
}
if (-not (Test-Path $versionFile)) {
    throw "version.txt not found: $versionFile"
}

$mainJar = (Get-Content -Raw -Encoding UTF8 $mainJarFile).Trim()
$versionFromFile = (Get-Content -Raw -Encoding UTF8 $versionFile).Trim()

$rawVer = if ([string]::IsNullOrWhiteSpace($AppVersion)) { $versionFromFile } else { $AppVersion.Trim() }
$displayVersion = $rawVer -replace '^[vV]', ''

# build.sbt is the single source of truth for the version (the jar manifest and the app's Info dialog use it).
if ($displayVersion -ne $versionFromFile) {
    throw "Version mismatch: requested '$displayVersion' but build.sbt version is '$versionFromFile'. Update build.sbt or use a matching tag."
}

$readmePath = Join-Path $RepoRoot "dist\Readme.ja.txt"
$readme = [System.IO.File]::ReadAllText($readmePath, [System.Text.Encoding]::UTF8)
$readmeVersion = [regex]::Match($readme, '(?m)^\s+(\d+(?:\.\d+)+)\s*$').Groups[1].Value
if ($readmeVersion -ne $displayVersion) {
    throw "Version mismatch: dist\Readme.ja.txt version is '$readmeVersion' but build version is '$displayVersion'."
}
if (-not $readme.Contains(": v${displayVersion}:")) {
    throw "dist\Readme.ja.txt has no history entry for v${displayVersion}."
}

if ($displayVersion -match '^\d+\.\d+$') {
    $jpackageVersion = "$displayVersion.0"
}
elseif ($displayVersion -match '^\d+\.\d+\.\d+(\.\d+)?$') {
    $jpackageVersion = $displayVersion
}
else {
    throw "Invalid version format for jpackage: '$displayVersion' (expected X.Y, X.Y.Z, or X.Y.Z.W)"
}

$outputDir = Join-Path $jpackageDir "output"
$stageDir = Join-Path $jpackageDir "stage"

if (Test-Path $outputDir) { Remove-Item -Recurse -Force $outputDir }
if (Test-Path $stageDir) { Remove-Item -Recurse -Force $stageDir }
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
New-Item -ItemType Directory -Force -Path $stageDir | Out-Null

$jpackageArgs = @(
    "--type", "app-image",
    "--name", "W10Wheel",
    "--app-version", $jpackageVersion,
    "--vendor", "Yuki Ono",
    "--description", "Mouse Wheel Simulator",
    "--copyright", "Copyright (c) 2016 Yuki Ono",
    "--input", $inputDir,
    "--main-jar", $mainJar,
    "--main-class", "hooktest.W10Wheel",
    "--icon", (Join-Path $RepoRoot "icon\AppIcon.ico"),
    "--add-modules", "java.base,java.desktop,java.logging,java.naming,jdk.unsupported",
    "--java-options", '--enable-native-access=ALL-UNNAMED',
    "--java-options", '-Dlogback.configurationFile=$ROOTDIR\logback.xml',
    "--dest", $outputDir
)

& $jpackageExe @jpackageArgs
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE"
}

$packageName = "W10Wheel-$displayVersion-x64"
$packageRoot = Join-Path $stageDir $packageName
$builtImage = Join-Path $outputDir "W10Wheel"

if (-not (Test-Path $builtImage)) {
    throw "Expected jpackage app-image not found: $builtImage"
}

Move-Item -Path $builtImage -Destination $packageRoot

Copy-Item -Path (Join-Path $RepoRoot "AccelTable.txt") -Destination (Join-Path $packageRoot "AccelTable.txt")
Copy-Item -Path (Join-Path $RepoRoot "Library.txt") -Destination (Join-Path $packageRoot "Library.txt")
Copy-Item -Path (Join-Path $RepoRoot "License.txt") -Destination (Join-Path $packageRoot "License.txt")
Copy-Item -Path (Join-Path $RepoRoot "dist\Readme.ja.txt") -Destination (Join-Path $packageRoot "Readme.ja.txt")
Copy-Item -Path (Join-Path $RepoRoot "src\main\resources\logback.xml") -Destination (Join-Path $packageRoot "logback.xml")

$zipName = "$packageName.zip"
$zipPath = Join-Path $jpackageDir $zipName
if (Test-Path $zipPath) { Remove-Item -Force $zipPath }

Compress-Archive -Path $packageRoot -DestinationPath $zipPath -CompressionLevel Optimal

$hash = (Get-FileHash -Path $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
$shaPath = "$zipPath.sha256"
[System.IO.File]::WriteAllText($shaPath, "$hash  $zipName`n", [System.Text.UTF8Encoding]::new($false))

$zipSize = (Get-Item $zipPath).Length
Write-Output "Created portable zip: $zipPath ($zipSize bytes)"
Write-Output "Created sha256 file:  $shaPath ($hash)"

#Requires -Version 7
<#
.SYNOPSIS
    Checks that README.md still describes the software that is here.

.DESCRIPTION
    The README is the only description most visitors read, and it is the one file
    nobody's build touches — so it drifts: a version bump lands without the table,
    a screen is deleted while its screenshot stays linked, a gate is renamed while
    the table still names it.

    Every claim checked here is mechanically checkable. Prose is not: no script can
    tell whether a sentence is still true, which is why the release workflow asks
    for a README pass as well. This catches the part that can be caught.

    Run from the repository root:
        pwsh ./scripts/check-readme.ps1
#>
[CmdletBinding()]
param(
    [string] $ReadmePath = 'README.md',
    [string] $RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

function Get-Text([string] $path) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "missing file: $path"
    }
    return Get-Content -LiteralPath $path -Raw
}

function Get-TomlVersion([string] $text, [string] $key) {
    $match = [regex]::Match($text, "(?m)^\s*$key\s*=\s*`"([^`"]+)`"")
    if ($match.Success) { return $match.Groups[1].Value }
    return $null
}

function Get-ReadmeTableValue([string] $text, [string] $label) {
    $match = [regex]::Match($text, "(?m)^\|\s*$([regex]::Escape($label))\s*\|([^|]+)\|")
    if ($match.Success) { return $match.Groups[1].Value.Trim() }
    return $null
}

$readme = Get-Text (Join-Path $RepoRoot $ReadmePath)
$toml = Get-Text (Join-Path $RepoRoot 'gradle/libs.versions.toml')
$build = Get-Text (Join-Path $RepoRoot 'app/build.gradle.kts')
$wrapper = Get-Text (Join-Path $RepoRoot 'gradle/wrapper/gradle-wrapper.properties')

$violations = [System.Collections.Generic.List[string]]::new()

# ---------------------------------------------------------------- versions ---
# 没有 jvmToolchain 声明的工程，用字节码目标回答"这台机器要什么 Java 版本发布出来的 APK"
$jdk = [regex]::Match($build, 'jvmToolchain\((\d+)\)')
if (-not $jdk.Success) {
    $jdk = [regex]::Match($build, 'targetCompatibility\s*=\s*JavaVersion\.VERSION_(\d+)')
}
$compileSdk = [regex]::Match($build, 'compileSdk\s*\{\s*version\s*=\s*release\((\d+)\)\s*\{\s*minorApiLevel\s*=\s*(\d+)')
$minSdk = [regex]::Match($build, '(?m)^\s*minSdk\s*=\s*(\d+)')
$gradle = [regex]::Match($wrapper, 'gradle-([0-9.]+)-bin\.zip')

$expected = [ordered]@{
    'JDK'                   = if ($jdk.Success) { $jdk.Groups[1].Value } else { $null }
    'Android SDK Platform'  = if ($compileSdk.Success) { "$($compileSdk.Groups[1].Value).$($compileSdk.Groups[2].Value)" } else { $null }
    '最低 Android 版本'      = if ($minSdk.Success) { $minSdk.Groups[1].Value } else { $null }
    'Gradle'                = if ($gradle.Success) { $gradle.Groups[1].Value } else { $null }
    'Android Gradle Plugin' = Get-TomlVersion $toml 'androidGradlePlugin'
    'Kotlin'                = Get-TomlVersion $toml 'kotlin'
}

foreach ($label in $expected.Keys) {
    $actual = $expected[$label]
    if (-not $actual) {
        $violations.Add("could not read $label from the build files, so the README row cannot be checked")
        continue
    }
    $row = Get-ReadmeTableValue $readme $label
    if (-not $row) {
        $violations.Add("README has no '$label' row in its 环境要求 table (build says $actual)")
        continue
    }
    if (-not $row.Contains($actual)) {
        $violations.Add("README says $label is '$row'; the build says $actual")
    }
}

# --------------------------------------------------------------- artefacts ---
# A screenshot of a deleted screen is worse than no screenshot: it describes an app
# that does not exist. The same for a gate the README promises and no longer ships.
$linked = [regex]::Matches($readme, 'docs/shots/([\w\-.]+\.png)') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique
foreach ($name in $linked) {
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot "docs/shots/$name"))) {
        $violations.Add("README links docs/shots/$name, which is not in the repository")
    }
}

$gates = [regex]::Matches($readme, 'scripts/(check-[\w\-]+\.ps1)') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique
foreach ($gate in $gates) {
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot "scripts/$gate"))) {
        $violations.Add("README names scripts/$gate, which no longer exists")
    }
}

# ---------------------------------------------------------------- verdict ----
if ($violations.Count -gt 0) {
    Write-Host 'README IS NO LONGER ACCURATE' -ForegroundColor Red
    foreach ($line in $violations) {
        Write-Host "  - $line" -ForegroundColor Red
    }
    Write-Host ''
    Write-Host 'A version table that disagrees with the build is a reader being told the wrong thing.' -ForegroundColor Yellow
    exit 1
}

Write-Host "CLEAN - $($expected.Count) version rows match the build, $($linked.Count) screenshots and $($gates.Count) gate references exist." -ForegroundColor Green
exit 0

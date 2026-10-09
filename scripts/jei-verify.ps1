# The single entry point for the verification this repository requires before any
# page-level work may be called done. It runs, in order:
#
#   1. the common unit tests                     (:common:test)
#   2. the client recipe page tests              (scripts/jei-client-tests.ps1)
#   3. the declared mod smoke test               (scripts/jei-declared-smoke.ps1)
#
# Each stage is run to completion, its own console output is kept under
# build/tmp/jei-verify/, and one summary line is printed per stage. The script
# exits non-zero as soon as the whole run is over if any stage failed.
#
# Running the harnesses needs the test mod pack at
# targets/neoforge-1.21.1/run/mods; how it was assembled is documented in
# scripts/jei-mod-env/README.md.
[CmdletBinding()]
param(
    # JDK 21 that runs Gradle. Defaults to JAVA_HOME, then to the path this repo
    # was developed on, so the script is not machine-locked.
    [string] $JdkPath,
    # Passed through to the declared smoke test, which then runs Gradle offline.
    [switch] $Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $repoRoot 'build\tmp\jei-verify'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

$jdk = if ($JdkPath) { $JdkPath } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\program\jdk-21' }
if (-not (Test-Path -LiteralPath $jdk -PathType Container)) {
    throw "JDK 21 was not found at '$jdk'; pass -JdkPath or set JAVA_HOME to a JDK 21 installation."
}
$env:JAVA_HOME = $jdk

# The unit-test stage runs the root Gradle wrapper, which needs the repository
# root as the working directory wherever this script was started from.
Set-Location -LiteralPath $repoRoot

# --- stage runner -----------------------------------------------------------

$stages = New-Object System.Collections.Generic.List[object]

# Runs one stage through cmd.exe with its output redirected to a log file,
# prints a single summary line and remembers the result.
#
# The stage's own streams go to files rather than through a captured pipe on
# purpose: a harness that leaves a child behind (a game server it could not
# stop) would keep the pipe open, and a capture ends only at EOF, so the whole
# verifier would hang instead of reporting the failure. Reading the log after
# the process exits cannot hang.
function Invoke-Stage {
    param(
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $CommandLine
    )
    $logPath = Join-Path $logDirectory ($Name + '.log')
    $errorPath = Join-Path $logDirectory ($Name + '.stderr.log')
    Remove-Item -LiteralPath $logPath, $errorPath -Force -ErrorAction SilentlyContinue
    Write-Host ''
    Write-Host ("----- {0} -----" -f $Name)
    $started = Get-Date
    # cmd performs the redirection itself, so the stage writes to a pair of files
    # instead of to a pipe this script would have to read to EOF. A harness that
    # leaves a child behind (a server it could not stop) holds a file handle then,
    # which cannot block us, while an inherited pipe write-end would hang the whole
    # verifier. The exit code is taken from $LASTEXITCODE because a redirected
    # Start-Process child does not report a usable ExitCode.
    & cmd.exe /d /c ('{0} > "{1}" 2> "{2}"' -f $CommandLine, $logPath, $errorPath)
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 1 }
    $hasStderr = $false
    if (Test-Path -LiteralPath $errorPath -PathType Leaf) {
        $hasStderr = (Get-Item -LiteralPath $errorPath).Length -gt 0
    }
    if ($hasStderr) {
        Add-Content -LiteralPath $logPath -Value '--- stderr ---'
        Get-Content -LiteralPath $errorPath | Add-Content -LiteralPath $logPath
    }
    $seconds = [int] ((Get-Date) - $started).TotalSeconds
    if ($Name -eq 'unit-tests') {
        $summary = Get-UnitTestSummary
    } else {
        # A harness prints its own verdict last; that line is the summary.
        $summary = @(Get-Content -LiteralPath $logPath | Where-Object { $_ -ne '' }) |
            Select-Object -Last 1
        if (-not $summary) { $summary = 'no output' }
    }
    $verdict = if ($code -eq 0) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1}: {2} (exit {3}, {4}s, log {5})" -f
        $verdict, $Name, $summary, $code, $seconds, $logPath)
    if ($code -ne 0) {
        Write-Host ("--- last lines of {0} ---" -f $Name)
        Get-Content -LiteralPath $logPath -Tail 30 | ForEach-Object { Write-Host $_ }
    }
    $stages.Add([pscustomobject] @{ Name = $Name; Code = $code })
}

# The common test count, read from the JUnit XML the run just wrote.
function Get-UnitTestSummary {
    $resultsDirectory = Join-Path $repoRoot 'common\build\test-results\test'
    if (-not (Test-Path -LiteralPath $resultsDirectory -PathType Container)) {
        return 'no test results were written'
    }
    $tests = 0
    $failed = 0
    foreach ($file in Get-ChildItem -LiteralPath $resultsDirectory -Filter 'TEST-*.xml' -File) {
        [xml] $document = Get-Content -LiteralPath $file.FullName -Raw
        $tests += [int] $document.testsuite.tests
        $failed += [int] $document.testsuite.failures + [int] $document.testsuite.errors
    }
    return ("{0} unit tests, {1} failed" -f $tests, $failed)
}

# --- the three stages -------------------------------------------------------

$gradleWrapper = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle wrapper not found: $gradleWrapper"
}

Invoke-Stage -Name 'unit-tests' `
    -CommandLine ('"{0}" :common:test --console plain --no-daemon' -f $gradleWrapper)

$clientTests = Join-Path $PSScriptRoot 'jei-client-tests.ps1'
Invoke-Stage -Name 'client-page-tests' `
    -CommandLine ('powershell -NoProfile -ExecutionPolicy Bypass -File "{0}" -JdkPath "{1}"' -f $clientTests, $jdk)

$declaredSmoke = Join-Path $PSScriptRoot 'jei-declared-smoke.ps1'
$smokeArguments = 'powershell -NoProfile -ExecutionPolicy Bypass -File "{0}" -JdkPath "{1}"' -f $declaredSmoke, $jdk
if ($Offline) { $smokeArguments += ' -Offline' }
Invoke-Stage -Name 'declared-smoke' -CommandLine $smokeArguments

# --- verdict ----------------------------------------------------------------

$failedStages = @($stages | Where-Object { $_.Code -ne 0 })
Write-Host ''
if ($failedStages.Count -eq 0) {
    Write-Host ("jei-verify PASSED ({0} stage(s): {1})" -f $stages.Count, (($stages | ForEach-Object { $_.Name }) -join ', '))
    exit 0
}
Write-Host ("jei-verify FAILED ({0} of {1} stage(s): {2})" -f
    $failedStages.Count, $stages.Count, (($failedStages | ForEach-Object { $_.Name }) -join ', '))
exit 1

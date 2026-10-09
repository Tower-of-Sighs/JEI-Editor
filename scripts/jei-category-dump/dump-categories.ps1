# Launch the NeoForge 1.21.1 dev client, wait for the temporary JEI category
# diagnostic to write run/jei-category-dump.txt, then stop the client.
#
# Copy JeiCategoryDiagnostic.java (same directory) into
# targets/neoforge-1.21.1/src/main/java/cc/sighs/JEIEditor/client/ first, and
# delete it from there again once the dump has been captured.
param(
    [int] $TimeoutSeconds = 1800,
    # JDK 21 that runs Gradle. Defaults to JAVA_HOME, then to the path this repo
    # was developed on, so the script is not machine-locked.
    [string] $JdkPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Windows.Forms

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$targetDirectory = Join-Path $repoRoot 'targets\neoforge-1.21.1'
$runDirectory = Join-Path $targetDirectory 'run'
$dumpPath = Join-Path $runDirectory 'jei-category-dump.txt'
$ingredientPath = Join-Path $runDirectory 'jei-ingredient-types.txt'
$heartbeatPath = Join-Path $runDirectory 'jei-category-dump.heartbeat.txt'
$logDirectory = Join-Path $repoRoot 'build\tmp\modsearch'
$stdoutPath = Join-Path $logDirectory 'client.stdout.log'
$stderrPath = Join-Path $logDirectory 'client.stderr.log'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
Remove-Item -LiteralPath $stdoutPath, $stderrPath, $dumpPath, $heartbeatPath -Force -ErrorAction SilentlyContinue

$jdk = if ($JdkPath) { $JdkPath } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\program\jdk-21' }
if (-not (Test-Path -LiteralPath $jdk -PathType Container)) {
    throw "JDK 21 was not found at '$jdk'; pass -JdkPath or set JAVA_HOME to a JDK 21 installation."
}
$env:JAVA_HOME = $jdk
$wrapper = Join-Path $targetDirectory 'gradlew.bat'

$process = Start-Process -FilePath 'cmd.exe' `
    -ArgumentList @('/d', '/c', "`"$wrapper`" runClient --console plain --no-daemon") `
    -WorkingDirectory $targetDirectory -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath -PassThru

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$failed = $false
$dumped = $false
$dismissed = $false
while ([DateTime]::UtcNow -lt $deadline) {
    if ((Test-Path -LiteralPath $dumpPath) -and (Test-Path -LiteralPath $ingredientPath)) { $dumped = $true; break }
    if ($process.HasExited) { break }
    if (Test-Path -LiteralPath $stdoutPath) {
        $tail = Get-Content -LiteralPath $stdoutPath -Tail 80 -ErrorAction SilentlyContinue
        if ($tail -match 'Failed to start the minecraft game|Missing or unsupported mandatory dependencies|Incompatible mod set|Failed to create mod container') {
            $failed = $true
            Start-Sleep -Seconds 8
            break
        }
        if ($tail -match 'Conflicts between mods' -and -not $dismissed) {
            $dismissed = $true
            Start-Sleep -Seconds 6
            foreach ($key in '{ENTER}', '{TAB}', '{ENTER}') {
                [void][System.Windows.Forms.SendKeys]::SendWait($key)
                Start-Sleep -Milliseconds 700
            }
        }
    }
    if (Test-Path -LiteralPath $heartbeatPath) {
        Write-Host ("heartbeat: " + (Get-Content -LiteralPath $heartbeatPath -Raw))
    }
    Start-Sleep -Seconds 10
}

if ($dumped) { Start-Sleep -Seconds 3 }
$exited = $process.WaitForExit(30000)
if (-not $exited) {
    Write-Host 'Stopping the client process tree.'
    & taskkill.exe /PID $process.Id /T /F | Out-Null
}
if ($failed) { Write-Host 'MOD LOAD FAILURE DETECTED' }
if ($dumped) { Write-Host "JEI CATEGORY DUMP WRITTEN: $dumpPath" } else { Write-Host 'NO DUMP PRODUCED' }

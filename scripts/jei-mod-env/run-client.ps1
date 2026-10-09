# Launch the NeoForge 1.21.1 dev client with every jar in run/mods and stop once
# the main menu is reached (JEI finishing its plugin pass) or a load failure shows.
param(
    [int] $TimeoutSeconds = 900,
    [string] $JdkPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Windows.Forms

# This script lives in scripts/jei-mod-env, two levels below the repository root.
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$targetDirectory = Join-Path $repoRoot 'targets\neoforge-1.21.1'
$logDirectory = Join-Path $repoRoot 'build\tmp\modsearch'
$stdoutPath = Join-Path $logDirectory 'client.stdout.log'
$stderrPath = Join-Path $logDirectory 'client.stderr.log'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue

# The JDK that runs Gradle. Override with -JdkPath, or set JAVA_HOME.
if (-not $JdkPath) {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath $env:JAVA_HOME -PathType Container)) {
        $JdkPath = $env:JAVA_HOME
    } else {
        $JdkPath = 'D:\program\jdk-21'
    }
}
if (-not (Test-Path -LiteralPath $JdkPath -PathType Container)) {
    throw "JDK 21 was not found at '$JdkPath'; pass -JdkPath or set JAVA_HOME to a JDK 21 installation."
}
$env:JAVA_HOME = $JdkPath
$wrapper = Join-Path $targetDirectory 'gradlew.bat'

$process = Start-Process -FilePath 'cmd.exe' `
    -ArgumentList @('/d', '/c', "`"$wrapper`" runClient --console plain --no-daemon") `
    -WorkingDirectory $targetDirectory -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath -PassThru

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$failed = $false
$succeeded = $false
$dismissed = $false
while ([DateTime]::UtcNow -lt $deadline) {
    if ($process.HasExited) { break }
    if (Test-Path -LiteralPath $stdoutPath) {
        $tail = Get-Content -LiteralPath $stdoutPath -Tail 80 -ErrorAction SilentlyContinue
        if ($tail -match 'Failed to start the minecraft game|Missing or unsupported mandatory dependencies|Incompatible mod set|Failed to create mod container') {
            $failed = $true
            Start-Sleep -Seconds 8
            break
        }
        # A `discouraged` conflict shows a confirmation screen that must be
        # accepted before the client continues loading.
        if ($tail -match 'Conflicts between mods' -and -not $dismissed) {
            $dismissed = $true
            Start-Sleep -Seconds 6
            foreach ($key in '{ENTER}', '{TAB}', '{ENTER}') {
                [void][System.Windows.Forms.SendKeys]::SendWait($key)
                Start-Sleep -Milliseconds 700
            }
        }
        if ($tail -match 'Starting JEI took') {
            $succeeded = $true
            Start-Sleep -Seconds 5
            break
        }
    }
    Start-Sleep -Seconds 5
}

$exited = $process.WaitForExit(30000)
if (-not $exited) {
    Write-Host 'Stopping the client process.'
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "Client process exited with code $($process.ExitCode)."
}
if ($failed) { Write-Host 'MOD LOAD FAILURE DETECTED' }
if ($succeeded) { Write-Host 'CLIENT REACHED MAIN MENU WITH MODS LOADED' }

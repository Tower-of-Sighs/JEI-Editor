# One-off diagnostic: which recipes a mod's JEI page displays, and where they come
# from server side. It boots the dedicated server with the test pack on a throwaway
# world, asks RecipeProvenanceProbe for a report per page family, and can also try
# whether a datapack file reaches a recipe that a mod synthesises.
#
# It is a diagnostic template, not part of the pipeline: copy the probe into the
# target's sources first, run this script, then delete the copy again - the same
# procedure as scripts/jei-category-dump/JeiCategoryDiagnostic.java. The report is
# written to the run directory as config/jeieditor/<name>.txt and copied to
# build/tmp/recon/.
#
#   copy scripts\jei-category-dump\RecipeProvenanceProbe.java ^
#        targets\neoforge-1.21.1\src\main\java\cc\sighs\JEIEditor\server\
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-category-dump\probe-provenance.ps1
#   del targets\neoforge-1.21.1\src\main\java\cc\sighs\JEIEditor\server\RecipeProvenanceProbe.java
#
# The class registers two RCON commands: "jeiprovenance <name> <tokens>" (tokens:
# serializer:<id>, exact:<id>, prefix:<text>, contains:<text>, all:) and
# "jeiprobeforce <token>", which writes the canonical JSON of a chosen recipe into
# the generated pack with one field changed, reloads, and reports what the live
# recipe then encodes to - a change that survives proves the file reaches it.
[CmdletBinding()]
param(
    [int] $RconPort = 25575,
    [int] $ServerPort = 25565,
    [string] $RconPassword = 'jeieditor-local-test',
    [int] $StartupTimeoutSeconds = 900,
    # JDK 21 that runs Gradle. Defaults to JAVA_HOME, then to the path this repo
    # was developed on, so the script is not machine-locked.
    [string] $JdkPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$jdk = if ($JdkPath) { $JdkPath } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\program\jdk-21' }
if (-not (Test-Path -LiteralPath $jdk -PathType Container)) {
    throw "JDK 21 was not found at '$jdk'; pass -JdkPath or set JAVA_HOME to a JDK 21 installation."
}
$env:JAVA_HOME = $jdk
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$targetDirectory = Join-Path $repoRoot 'targets\neoforge-1.21.1'
$wrapper = Join-Path $targetDirectory 'gradlew.bat'
$probeSource = Join-Path $targetDirectory 'src\main\java\cc\sighs\JEIEditor\server\RecipeProvenanceProbe.java'
if (-not (Test-Path -LiteralPath $probeSource -PathType Leaf)) {
    throw ("Copy scripts\jei-category-dump\RecipeProvenanceProbe.java to $probeSource first, " +
        'run this script, then delete that copy again.')
}
$runDirectory = Join-Path $targetDirectory 'run'
$logDirectory = Join-Path $repoRoot 'build\tmp\recon'
$worldName = 'jeiprobe-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$worldDirectory = Join-Path $runDirectory $worldName
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$stdoutPath = Join-Path $logDirectory 'probe.stdout.log'
$stderrPath = Join-Path $logDirectory 'probe.stderr.log'
Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue

$serverPropertiesPath = Join-Path $runDirectory 'server.properties'
$serverProperties = if (Test-Path -LiteralPath $serverPropertiesPath -PathType Leaf) { @(Get-Content -LiteralPath $serverPropertiesPath) } else { @() }
foreach ($property in @{
    'enable-rcon' = 'true'
    'rcon.password' = $RconPassword
    'rcon.port' = [string] $RconPort
    'server-port' = [string] $ServerPort
    'level-name' = $worldName
    'online-mode' = 'false'
}.GetEnumerator()) {
    $prefix = $property.Key + '='
    $replacement = $prefix + $property.Value
    $found = $false
    $serverProperties = @($serverProperties | ForEach-Object {
        if ($_ -match ('^' + [regex]::Escape($prefix))) { $found = $true; $replacement } else { $_ }
    })
    if (-not $found) { $serverProperties += $replacement }
}
Set-Content -LiteralPath $serverPropertiesPath -Value $serverProperties -Encoding ascii
Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Value 'eula=true' -Encoding ascii

$hiddenJars = New-Object System.Collections.Generic.List[string]
$modsDirectory = Join-Path $runDirectory 'mods'
foreach ($jar in Get-ChildItem -LiteralPath $modsDirectory -Filter '*.jar' -File) {
    foreach ($prefix in @('chest-helper__', 'create-jei-compat__')) {
        if ($jar.Name.StartsWith($prefix)) {
            $hidden = $jar.FullName + '.probe-disabled'
            Move-Item -LiteralPath $jar.FullName -Destination $hidden -Force
            $hiddenJars.Add($hidden)
        }
    }
}

function Send-RconPacket {
    param($Stream, [int] $RequestId, [int] $Type, [string] $Body)
    $payload = [Text.Encoding]::UTF8.GetBytes($Body + [char] 0 + [char] 0)
    $length = 4 + 4 + $payload.Length
    $packet = New-Object byte[] ($length + 4)
    [BitConverter]::GetBytes($length).CopyTo($packet, 0)
    [BitConverter]::GetBytes($RequestId).CopyTo($packet, 4)
    [BitConverter]::GetBytes($Type).CopyTo($packet, 8)
    $payload.CopyTo($packet, 12)
    $Stream.Write($packet, 0, $packet.Length)
}
function Receive-RconPacket {
    param($Stream)
    $header = New-Object byte[] 4
    [void] $Stream.Read($header, 0, 4)
    $length = [BitConverter]::ToInt32($header, 0)
    if ($length -lt 10 -or $length -gt 1048576) { throw "Invalid RCON packet length: $length" }
    $data = New-Object byte[] $length
    $offset = 0
    while ($offset -lt $length) {
        $read = $Stream.Read($data, $offset, $length - $offset)
        if ($read -le 0) { throw 'RCON connection closed while reading a packet' }
        $offset += $read
    }
    [PSCustomObject] @{
        Id = [BitConverter]::ToInt32($data, 0)
        Type = [BitConverter]::ToInt32($data, 4)
        Body = [Text.Encoding]::UTF8.GetString($data, 8, $length - 10)
    }
}
function Invoke-Rcon {
    param($Stream, [ref] $RequestId, [string] $Command)
    $RequestId.Value++
    $request = $RequestId.Value
    Send-RconPacket $Stream $request 2 $Command
    $response = Receive-RconPacket $Stream
    if ($response.Id -ne $request) { throw "Unexpected RCON response id $($response.Id), expected $request" }
    return $response.Body
}

$serverProcess = $null
$rconClient = $null
$rconStream = $null
try {
    $serverProcess = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList @('/d', '/c', "$([char]34)$wrapper$([char]34) runServer --console plain --no-daemon") `
        -WorkingDirectory $targetDirectory -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    while ($true) {
        if ($serverProcess.HasExited) { throw "server exited early; see $stdoutPath" }
        try { $probe = [Net.Sockets.TcpClient]::new('127.0.0.1', $RconPort); $probe.Dispose(); break } catch { Start-Sleep -Milliseconds 500 }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'RCON never opened' }
    }
    $rconClient = [Net.Sockets.TcpClient]::new('127.0.0.1', $RconPort)
    $rconStream = $rconClient.GetStream()
    $requestId = 7000
    Send-RconPacket $rconStream $requestId 3 $RconPassword
    $auth = Receive-RconPacket $rconStream
    if ($auth.Id -ne $requestId) { throw 'RCON auth failed' }

    $tokens = 'all: serializer:jearchaeology:brush serializer:jearchaeology:sniff ' +
        'serializer:create:emptying serializer:create:filling serializer:create:mixing ' +
        'serializer:immersiveengineering:arc_furnace serializer:mekanism:smelting'
    Write-Host '--- phase 1: provenance dump before any reload'
    Write-Host (Invoke-Rcon $rconStream ([ref] $requestId) ('jeiprovenance phase1 ' + $tokens))
    Start-Sleep -Seconds 2
    $provenancePath = Join-Path $runDirectory 'config\jeieditor\phase1.txt'
    if (-not (Test-Path -LiteralPath $provenancePath -PathType Leaf)) { throw 'no phase1 report was written' }
    Copy-Item -LiteralPath $provenancePath -Destination (Join-Path $logDirectory 'phase1.txt') -Force
    Get-Content -LiteralPath $provenancePath | ForEach-Object { Write-Host $_ }

    Write-Host '--- phase 2: re-dump after a /reload (datapack sync)'
    [void] (Invoke-Rcon $rconStream ([ref] $requestId) 'reload')
    Start-Sleep -Seconds 8
    Write-Host (Invoke-Rcon $rconStream ([ref] $requestId) ('jeiprovenance phase2 serializer:jearchaeology:brush serializer:jearchaeology:sniff all:'))
    Start-Sleep -Seconds 2
    $phase2Path = Join-Path $runDirectory 'config\jeieditor\phase2.txt'
    if (Test-Path -LiteralPath $phase2Path -PathType Leaf) {
        Copy-Item -LiteralPath $phase2Path -Destination (Join-Path $logDirectory 'phase2.txt') -Force
        Get-Content -LiteralPath $phase2Path | ForEach-Object { Write-Host $_ }
    } else {
        Write-Host 'no phase2 report was written'
    }
    Write-Host '--- phase 3: does a datapack file reach a recipe the mod synthesises?'
    foreach ($token in @('serializer:create:emptying', 'serializer:immersiveengineering:arc_furnace',
            'serializer:create:mixing', 'serializer:create:filling')) {
        Write-Host ("  force {0}: {1}" -f $token,
            (Invoke-Rcon $rconStream ([ref] $requestId) ('jeiprobeforce ' + $token)))
        Start-Sleep -Seconds 15
    }
    $reloadPath = Join-Path $runDirectory 'config\jeieditor\provenance-reload.txt'
    if (Test-Path -LiteralPath $reloadPath -PathType Leaf) {
        Copy-Item -LiteralPath $reloadPath -Destination (Join-Path $logDirectory 'provenance-reload.txt') -Force
        Get-Content -LiteralPath $reloadPath | ForEach-Object { Write-Host $_ }
    } else {
        Write-Host 'no provenance-reload.txt was written'
    }

    [void] (Invoke-Rcon $rconStream ([ref] $requestId) 'stop')
    $rconStream.Dispose(); $rconStream = $null
    $rconClient.Dispose(); $rconClient = $null
    [void] $serverProcess.WaitForExit(60000)
    Write-Host '--- probe run complete'
} finally {
    if ($rconStream -ne $null) { try { [void] (Invoke-Rcon $rconStream ([ref] $requestId) 'stop') } catch { } }
    if ($rconStream -ne $null) { $rconStream.Dispose() }
    if ($rconClient -ne $null) { $rconClient.Dispose() }
    if ($serverProcess -ne $null) {
        [void] $serverProcess.WaitForExit(30000)
        if (-not $serverProcess.HasExited) { & taskkill.exe /PID $serverProcess.Id /T /F | Out-Null }
        $serverProcess.Dispose()
    }
    foreach ($hidden in $hiddenJars) {
        if (Test-Path -LiteralPath $hidden -PathType Leaf) {
            Move-Item -LiteralPath $hidden -Destination ($hidden -replace '\.probe-disabled$', '') -Force
        }
    }
}

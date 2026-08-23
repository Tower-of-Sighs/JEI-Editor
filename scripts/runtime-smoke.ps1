[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Target,

    [string] $ImportName,
    [int] $RconPort = 25575,
    [int] $ServerPort = 25565,
    [string] $RconPassword = 'jeieditor-local-test',
    [int] $StartupTimeoutSeconds = 180,
    [int] $MinimumExportedEdits = 1,
    [switch] $Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Target -ne 'neoforge-1.21.1') {
    throw 'Only the neoforge-1.21.1 target is supported by this project.'
}

if ($RconPort -lt 1 -or $RconPort -gt 65535 -or $ServerPort -lt 1 -or $ServerPort -gt 65535) {
    throw 'RCON and server ports must be between 1 and 65535'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$targetRoot = Join-Path $repoRoot 'targets'
if ([string]::IsNullOrWhiteSpace($Target) -or
    [System.IO.Path]::IsPathRooted($Target) -or
    $Target.IndexOfAny([System.IO.Path]::GetInvalidFileNameChars()) -ge 0 -or
    $Target.Contains('/') -or $Target.Contains('\') -or $Target.Contains('..')) {
    throw "Target must be a direct directory name under targets/: $Target"
}

$targetDirectory = Join-Path $targetRoot $Target
$wrapper = Join-Path $targetDirectory 'gradlew.bat'
if (-not (Test-Path -LiteralPath $targetDirectory -PathType Container) -or
    -not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw "Target or Gradle wrapper not found: $Target"
}

$runDirectory = Join-Path $targetDirectory 'run'
$logDirectory = Join-Path $targetDirectory 'build\runtime-smoke'
$worldName = 'jeieditor-smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$worldDirectory = Join-Path $runDirectory $worldName
$policyConfigPath = Join-Path $runDirectory 'config\jeieditor.properties'
$policyBackupPath = Join-Path $logDirectory 'jeieditor.properties.original'
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $policyConfigPath) -Force | Out-Null
$policyWasPresent = Test-Path -LiteralPath $policyConfigPath -PathType Leaf
Remove-Item -LiteralPath $policyBackupPath -Force -ErrorAction SilentlyContinue
if ($policyWasPresent) {
    Copy-Item -LiteralPath $policyConfigPath -Destination $policyBackupPath -Force
}
# Smoke imports must start from an unrestricted policy, regardless of a prior negative test.
Set-Content -LiteralPath $policyConfigPath -Value @(
    'min_permission_level=2'
    'allow_namespaces='
    'deny_namespaces='
) -Encoding ascii
$stdoutPath = Join-Path $logDirectory 'server.stdout.log'
$stderrPath = Join-Path $logDirectory 'server.stderr.log'
Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue

$serverPropertiesPath = Join-Path $runDirectory 'server.properties'
$serverProperties = if (Test-Path -LiteralPath $serverPropertiesPath -PathType Leaf) {
    @(Get-Content -LiteralPath $serverPropertiesPath)
} else {
    @()
}
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
        if ($_ -match ('^' + [regex]::Escape($prefix))) {
            $found = $true
            $replacement
        } else {
            $_
        }
    })
    if (-not $found) { $serverProperties += $replacement }
}
Set-Content -LiteralPath $serverPropertiesPath -Value $serverProperties -Encoding ascii
$eulaPath = Join-Path $runDirectory 'eula.txt'
Set-Content -LiteralPath $eulaPath -Value 'eula=true' -Encoding ascii

$smokeNamespace = 'jeieditor_smoke'
$smokeRecipeId = $smokeNamespace + ':simple'
$recipeFolder = 'recipe'
$packFormat = 48
$fixtureRoot = Join-Path $worldDirectory 'datapacks\jeieditor-runtime'
$fixtureRecipePath = Join-Path $fixtureRoot ('data\' + $smokeNamespace + '\' + $recipeFolder + '\simple.json')
New-Item -ItemType Directory -Path (Split-Path -Parent $fixtureRecipePath) -Force | Out-Null
Set-Content -LiteralPath (Join-Path $fixtureRoot 'pack.mcmeta') `
    -Value ('{"pack":{"pack_format":' + $packFormat + ',"description":"JEI Editor runtime smoke"}}') -Encoding ascii
$fixtureIngredient = '{"item":"minecraft:stone"}'
$fixtureRecipe = '{"type":"minecraft:crafting_shaped","pattern":["A"],"key":{"A":' + $fixtureIngredient + '},"result":{"id":"minecraft:diamond","count":1}}'
Set-Content -LiteralPath $fixtureRecipePath -Value $fixtureRecipe -Encoding ascii

$cookingRecipes = @(
    @{ Name = 'smelting'; Input = 'minecraft:stone'; Output = 'minecraft:iron_ingot'; Experience = '0.1'; Time = '200'; NewExperience = '0.8'; NewTime = '400' },
    @{ Name = 'blasting'; Input = 'minecraft:cobblestone'; Output = 'minecraft:gold_ingot'; Experience = '0.2'; Time = '100'; NewExperience = '0.9'; NewTime = '250' },
    @{ Name = 'smoking'; Input = 'minecraft:netherrack'; Output = 'minecraft:brick'; Experience = '0.3'; Time = '100'; NewExperience = '1.0'; NewTime = '300' },
    @{ Name = 'campfire_cooking'; Input = 'minecraft:clay_ball'; Output = 'minecraft:brick'; Experience = '0.4'; Time = '600'; NewExperience = '1.1'; NewTime = '900' }
)
$fixturePatches = New-Object System.Collections.Generic.List[string]
$fingerprintInput = $smokeRecipeId + '|minecraft:crafting_shaped|input.0=input:minecraft:stone:1|output=output:minecraft:diamond:1'
$hash = [Security.Cryptography.SHA256]::Create()
$fingerprint = (($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($fingerprintInput)) |
        ForEach-Object { $_.ToString('x2') }) -join '')
$hash.Dispose()
[void] $fixturePatches.Add('{"recipe_id":"' + $smokeRecipeId +
    '","serializer":"minecraft:crafting_shaped","base_fingerprint":"' + $fingerprint +
    '","fields":{"output.count":"2"}}')
foreach ($cooking in $cookingRecipes) {
    $recipeId = $smokeNamespace + ':' + $cooking.Name
    $recipePath = Join-Path $fixtureRoot ('data\' + $smokeNamespace + '\' + $recipeFolder + '\' + $cooking.Name + '.json')
    $ingredient = '{"item":"' + $cooking.Input + '"}'
    $result = '{"id":"' + $cooking.Output + '","count":1}'
    $recipe = '{"type":"minecraft:' + $cooking.Name + '","ingredient":' + $ingredient + ',"result":' + $result + ',"experience":' + $cooking.Experience + ',"cookingtime":' + $cooking.Time + '}'
    Set-Content -LiteralPath $recipePath -Value $recipe -Encoding ascii
    $fingerprintInput = $recipeId + '|minecraft:' + $cooking.Name + '|input.0=input:' + $cooking.Input + ':1|output=output:' + $cooking.Output + ':1|experience=' + $cooking.Experience + '|cooking_time=' + $cooking.Time
    $hash = [Security.Cryptography.SHA256]::Create()
    $fingerprint = (($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($fingerprintInput)) |
            ForEach-Object { $_.ToString('x2') }) -join '')
    $hash.Dispose()
    $cookingFields = '"output.count":"2","recipe.experience":"' + $cooking.NewExperience + '","recipe.cooking_time":"' + $cooking.NewTime + '"'
    [void] $fixturePatches.Add('{"recipe_id":"' + $recipeId + '","serializer":"minecraft:' + $cooking.Name + '","base_fingerprint":"' + $fingerprint + '","fields":{' + $cookingFields + '}}')
}
$fixtureBundle = '{"format_version":1,"patches":[' + ($fixturePatches -join ',') + ']}'
$fixtureBundlePath = Join-Path $runDirectory 'config\jeieditor\exports\runtime-fixture.json'
New-Item -ItemType Directory -Path (Split-Path -Parent $fixtureBundlePath) -Force | Out-Null
Set-Content -LiteralPath $fixtureBundlePath -Value $fixtureBundle -Encoding ascii

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
    if ($length -lt 10 -or $length -gt 1048576) {
        throw "Invalid RCON packet length: $length"
    }
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
    if ($response.Id -ne $request) {
        throw "Unexpected RCON response id $($response.Id), expected $request"
    }
    return $response.Body
}

function Wait-RconPort {
    param([int] $Port, [int] $TimeoutSeconds, $Process)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "Server process exited before RCON became available; inspect $stdoutPath and $stderrPath"
        }
        try {
            $probe = [Net.Sockets.TcpClient]::new('127.0.0.1', $Port)
            $probe.Dispose()
            return
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "RCON did not become available within $TimeoutSeconds seconds"
}

function Get-ExportCount([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Expected export was not created: $Path"
    }
    $bundle = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if ($bundle.format_version -ne 1) { throw "Unexpected export format version" }
    return @($bundle.patches).Count
}

function Wait-ForExportMinimum {
    param(
        $Stream,
        [ref] $RequestId,
        [string] $ExportName,
        [int] $MinimumCount,
        [int] $TimeoutSeconds
    )
    $path = Join-Path $runDirectory ('config\jeieditor\exports\' + $ExportName + '.json')
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        [void] (Invoke-Rcon $Stream $RequestId ('jeieditor export ' + $ExportName))
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            try {
                $count = Get-ExportCount $path
                if ($count -ge $MinimumCount) { return $count }
            } catch {
                # The command may be observing the file while an async import is still completing.
            }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for at least $MinimumCount imported edits in $ExportName"
}

$serverProcess = $null
$rconClient = $null
$rconStream = $null
try {
    $arguments = @('runServer', '--console', 'plain', '--no-daemon')
    if ($Offline) { $arguments += '--offline' }
    $argumentText = $arguments -join ' '
    $serverProcess = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList @('/d', '/c', "$([char]34)$wrapper$([char]34) $argumentText") `
        -WorkingDirectory $targetDirectory -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath -PassThru

    Wait-RconPort -Port $RconPort -TimeoutSeconds $StartupTimeoutSeconds -Process $serverProcess
    $rconClient = [Net.Sockets.TcpClient]::new('127.0.0.1', $RconPort)
    $rconStream = $rconClient.GetStream()
    $requestId = 1000
    Send-RconPacket $rconStream $requestId 3 $RconPassword
    $auth = Receive-RconPacket $rconStream
    if ($auth.Id -ne $requestId) { throw 'RCON authentication failed' }

    $help = Invoke-Rcon $rconStream ([ref] $requestId) 'help jeieditor'
    if ($help -notmatch 'jeieditor') { throw 'jeieditor command was not registered' }

    $exportDirectory = Join-Path $runDirectory 'config\jeieditor\exports'
    $firstPath = Join-Path $exportDirectory 'runtime-smoke.json'
    $secondPath = Join-Path $exportDirectory 'runtime-smoke-reload.json'
    $restartPath = Join-Path $exportDirectory 'runtime-smoke-restart.json'
    Remove-Item -LiteralPath $firstPath, $secondPath, $restartPath -Force -ErrorAction SilentlyContinue
    [void] (Invoke-Rcon $rconStream ([ref] $requestId) 'jeieditor import runtime-fixture')
    $expectedFixtureCount = 1 + $cookingRecipes.Count
    [void] (Wait-ForExportMinimum $rconStream ([ref] $requestId) 'runtime-smoke' $expectedFixtureCount $StartupTimeoutSeconds)
    if (-not [string]::IsNullOrWhiteSpace($ImportName)) {
        [void] (Invoke-Rcon $rconStream ([ref] $requestId) "jeieditor import $ImportName")
        [void] (Wait-ForExportMinimum $rconStream ([ref] $requestId) 'runtime-smoke' $expectedFixtureCount $StartupTimeoutSeconds)
    }

    [void] (Invoke-Rcon $rconStream ([ref] $requestId) 'jeieditor export runtime-smoke')
    $firstCount = Get-ExportCount $firstPath
    if ($firstCount -lt [Math]::Max($MinimumExportedEdits, $expectedFixtureCount)) {
        throw "Expected at least $expectedFixtureCount edits, found $firstCount"
    }

    # Negative server-side validation checks must leave the accepted patch set unchanged.
    $staleFixturePath = Join-Path $exportDirectory 'runtime-stale.json'
    $staleFixture = '{"format_version":1,"patches":[{"recipe_id":"' + $smokeRecipeId +
        '","serializer":"minecraft:crafting_shaped","base_fingerprint":"' + ('0' * 64) +
        '","fields":{"output.count":"3"}}]}'
    Set-Content -LiteralPath $staleFixturePath -Value $staleFixture -Encoding ascii
    $staleResponse = Invoke-Rcon $rconStream ([ref] $requestId) 'jeieditor import runtime-stale'
    if ($staleResponse -notmatch '(?i)failed|invalid|stale|denied') {
        throw "Stale fingerprint import was not rejected: $staleResponse"
    }
    [void] (Wait-ForExportMinimum $rconStream ([ref] $requestId) 'runtime-smoke-stale-check' $firstCount $StartupTimeoutSeconds)
    if ((Get-ExportCount (Join-Path $exportDirectory 'runtime-smoke-stale-check.json')) -ne $firstCount) {
        throw 'Stale fingerprint import changed the accepted patch set'
    }

    Set-Content -LiteralPath $policyConfigPath -Value @(
        'min_permission_level=2'
        'allow_namespaces='
        ('deny_namespaces=' + $smokeNamespace)
    ) -Encoding ascii
    $deniedResponse = Invoke-Rcon $rconStream ([ref] $requestId) 'jeieditor import runtime-fixture'
    if ($deniedResponse -notmatch '(?i)failed|invalid|permission|namespace|denied') {
        throw "Denied namespace import was not rejected: $deniedResponse"
    }
    [void] (Wait-ForExportMinimum $rconStream ([ref] $requestId) 'runtime-smoke-policy-check' $firstCount $StartupTimeoutSeconds)
    if ((Get-ExportCount (Join-Path $exportDirectory 'runtime-smoke-policy-check.json')) -ne $firstCount) {
        throw 'Denied namespace import changed the accepted patch set'
    }

    $generatedRecipePath = Join-Path $worldDirectory ('datapacks\jeieditor-generated\data\' +
        $smokeNamespace + '\' + $recipeFolder + '\simple.json')
    if (-not (Test-Path -LiteralPath $generatedRecipePath -PathType Leaf)) {
        throw "Generated datapack recipe was not written to the expected $recipeFolder directory"
    }
    $generatedRecipe = Get-Content -LiteralPath $generatedRecipePath -Raw | ConvertFrom-Json
    if ($generatedRecipe.result.count -ne 2) {
        throw "Generated smoke recipe has unexpected output count"
    }
    foreach ($cooking in $cookingRecipes) {
        $generatedCookingPath = Join-Path $worldDirectory ('datapacks\jeieditor-generated\data\' +
            $smokeNamespace + '\' + $recipeFolder + '\' + $cooking.Name + '.json')
        if (-not (Test-Path -LiteralPath $generatedCookingPath -PathType Leaf)) {
            throw "Generated cooking recipe was not written: $($cooking.Name)"
        }
        $generatedCooking = Get-Content -LiteralPath $generatedCookingPath -Raw | ConvertFrom-Json
        $resultMatches = $generatedCooking.result.count -eq 2
        if (-not $resultMatches -or
            [Math]::Abs([double] $generatedCooking.experience - [double] $cooking.NewExperience) -gt 0.0001 -or
            $generatedCooking.cookingtime -ne [int] $cooking.NewTime) {
            throw "Generated cooking recipe has unexpected values: $($cooking.Name)"
        }
    }

    [void] (Invoke-Rcon $rconStream ([ref] $requestId) 'reload')
    Start-Sleep -Milliseconds 500
    [void] (Wait-ForExportMinimum $rconStream ([ref] $requestId) 'runtime-smoke-reload' $firstCount $StartupTimeoutSeconds)
    $secondCount = Get-ExportCount $secondPath
    if ($firstCount -ne $secondCount) {
        throw "Patch count changed across reload: $firstCount -> $secondCount"
    }

    [void] (Invoke-Rcon $rconStream ([ref] $requestId) 'stop')
    $rconStream.Dispose()
    $rconStream = $null
    $rconClient.Dispose()
    $rconClient = $null
    [void] $serverProcess.WaitForExit(30000)
    if (-not $serverProcess.HasExited) {
        throw "Server process did not exit after restart stop; logs: $logDirectory"
    }
    $serverProcess.Dispose()
    $serverProcess = $null

    $serverProcess = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList @('/d', '/c', "$([char]34)$wrapper$([char]34) $argumentText") `
        -WorkingDirectory $targetDirectory -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath -PassThru
    Wait-RconPort -Port $RconPort -TimeoutSeconds $StartupTimeoutSeconds -Process $serverProcess
    $rconClient = [Net.Sockets.TcpClient]::new('127.0.0.1', $RconPort)
    $rconStream = $rconClient.GetStream()
    $requestId = 2000
    Send-RconPacket $rconStream $requestId 3 $RconPassword
    $auth = Receive-RconPacket $rconStream
    if ($auth.Id -ne $requestId) { throw 'RCON authentication failed after restart' }
    $help = Invoke-Rcon $rconStream ([ref] $requestId) 'help jeieditor'
    if ($help -notmatch 'jeieditor') { throw 'jeieditor command was not registered after restart' }
    [void] (Wait-ForExportMinimum $rconStream ([ref] $requestId) 'runtime-smoke-restart' $firstCount $StartupTimeoutSeconds)
    $restartCount = Get-ExportCount $restartPath
    if ($firstCount -ne $restartCount) {
        throw "Patch count changed across restart: $firstCount -> $restartCount"
    }
    $restartedRecipe = Get-Content -LiteralPath $generatedRecipePath -Raw | ConvertFrom-Json
    if ($restartedRecipe.result.count -ne 2) {
        throw 'Generated smoke recipe lost its edited output after restart'
    }
    foreach ($cooking in $cookingRecipes) {
        $restartedCookingPath = Join-Path $worldDirectory ('datapacks\jeieditor-generated\data\' +
            $smokeNamespace + '\' + $recipeFolder + '\' + $cooking.Name + '.json')
        $restartedCooking = Get-Content -LiteralPath $restartedCookingPath -Raw | ConvertFrom-Json
        $resultMatches = $restartedCooking.result.count -eq 2
        if (-not $resultMatches -or
            [Math]::Abs([double] $restartedCooking.experience - [double] $cooking.NewExperience) -gt 0.0001 -or
            $restartedCooking.cookingtime -ne [int] $cooking.NewTime) {
            throw "Generated cooking recipe lost its edited values after restart: $($cooking.Name)"
        }
    }
    Write-Host "Runtime smoke passed: $Target ($firstCount edits before reload, after reload, and after restart)"
} finally {
    if ($rconStream -ne $null) {
        try {
            [void] (Invoke-Rcon $rconStream ([ref] $requestId) 'stop')
        } catch {
            Write-Warning "Could not stop server through RCON: $($_.Exception.Message)"
        }
    }
    if ($rconStream -ne $null) { $rconStream.Dispose() }
    if ($rconClient -ne $null) { $rconClient.Dispose() }
    if ($serverProcess -ne $null) {
        [void] $serverProcess.WaitForExit(30000)
        if (-not $serverProcess.HasExited) {
            throw "Server process did not exit after RCON stop; logs: $logDirectory"
        }
        $serverProcess.Dispose()
    }
    if (Test-Path -LiteralPath $policyBackupPath -PathType Leaf) {
        Copy-Item -LiteralPath $policyBackupPath -Destination $policyConfigPath -Force
    } elseif (-not $policyWasPresent) {
        Remove-Item -LiteralPath $policyConfigPath -Force -ErrorAction SilentlyContinue
    }
}
exit 0

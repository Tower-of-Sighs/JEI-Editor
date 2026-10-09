# Proof for scripts/jei-rcon.ps1's retry path - the failure that killed a server
# smoke once mid-run, and the guarantee that a transient one no longer aborts a run.
#
# A fake RCON server is started in a background job. It answers the auth packet
# and then, for a configured set of commands, reproduces the two transport
# failures that abort a run today: it closes the socket without answering, and
# it accepts the command but never answers (a silent peer, which the client sees
# as a receive timeout). Everything else is answered normally. The assertions
# are that a transient failure is retried (with a fresh connection, so the
# command is delivered twice), that the retry budget is bounded and reported,
# and that a non-transport error is not retried at all.
#
# It needs no Minecraft server, only PowerShell and a free localhost port:
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jei-rcon-proof.ps1
[CmdletBinding()]
param([int] $Port = 25999)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $repoRoot 'scripts\jei-rcon.ps1')

$logPath = Join-Path $env:TEMP ('rcon-proof-' + [Guid]::NewGuid().ToString('N') + '.log')
New-Item -ItemType File -Path $logPath -Force | Out-Null

$job = Start-Job -ScriptBlock {
    param($Port, $LogPath)
    function Read-Packet($stream) {
        $header = New-Object byte[] 4
        $offset = 0
        while ($offset -lt 4) {
            try { $read = $stream.Read($header, $offset, 4 - $offset) } catch { return $null }
            if ($read -le 0) { return $null }
            $offset += $read
        }
        $length = [BitConverter]::ToInt32($header, 0)
        $data = New-Object byte[] $length
        $offset = 0
        while ($offset -lt $length) {
            try { $read = $stream.Read($data, $offset, $length - $offset) } catch { return $null }
            if ($read -le 0) { return $null }
            $offset += $read
        }
        return [PSCustomObject] @{
            Id = [BitConverter]::ToInt32($data, 0)
            Type = [BitConverter]::ToInt32($data, 4)
            Body = [Text.Encoding]::UTF8.GetString($data, 8, $length - 10)
        }
    }
    function Send-Packet($stream, [int] $id, [int] $type, [string] $body) {
        $payload = [Text.Encoding]::UTF8.GetBytes($body + [char] 0 + [char] 0)
        $length = 4 + 4 + $payload.Length
        $packet = New-Object byte[] ($length + 4)
        [BitConverter]::GetBytes($length).CopyTo($packet, 0)
        [BitConverter]::GetBytes($id).CopyTo($packet, 4)
        [BitConverter]::GetBytes($type).CopyTo($packet, 8)
        $payload.CopyTo($packet, 12)
        $stream.Write($packet, 0, $packet.Length)
        $stream.Flush()
    }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    $listener.Start()
    $dropsLeft = 1
    $silenceLeft = 1
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        if (-not $listener.Pending()) { Start-Sleep -Milliseconds 50; continue }
        $client = $listener.AcceptTcpClient()
        $stream = $client.GetStream()
        $auth = Read-Packet $stream
        if ($null -ne $auth -and $auth.Type -eq 3) { Send-Packet $stream $auth.Id 2 '' }
        while ($true) {
            $packet = Read-Packet $stream
            if ($null -eq $packet) { break }
            Add-Content -LiteralPath $LogPath -Value $packet.Body
            if ($packet.Body -eq 'drop-once' -and $dropsLeft -gt 0) {
                # The observed failure: the server closes the socket mid-run.
                $dropsLeft--
                break
            }
            if ($packet.Body -eq 'silent' -and $silenceLeft -gt 0) {
                # Accepted but never answered: the client's receive timeout fires.
                # Short enough that the retries fit inside the listener's block.
                $silenceLeft--
                Start-Sleep -Seconds 5
                break
            }
            if ($packet.Body -eq 'always-silent') {
                Start-Sleep -Seconds 5
                break
            }
            if ($packet.Body -eq 'wrong-id') {
                Send-Packet $stream ($packet.Id + 1) 2 'ok:wrong-id'
                continue
            }
            Send-Packet $stream $packet.Id 2 ('ok:' + $packet.Body)
        }
        try { $client.Dispose() } catch { }
    }
    $listener.Stop()
} -ArgumentList $Port, $logPath

try {
    Start-Sleep -Milliseconds 800
    $rcon = New-RconConnection -Port $Port -Password 'proof' -TimeoutMilliseconds 2000
    Connect-Rcon $rcon
    Write-Host 'auth: connected and authenticated'

    $requestId = 500
    $body = Invoke-Rcon $rcon ([ref] $requestId) 'hello' -RetryDelayMilliseconds 100
    if ($body -ne 'ok:hello') { throw "unexpected body: $body" }
    Write-Host "plain command: $body"

    # 1) a socket close before the response: the command is re-sent on a new
    #    connection, which the server log shows twice.
    Remove-Item -LiteralPath $logPath -Force
    New-Item -ItemType File -Path $logPath -Force | Out-Null
    $body = Invoke-Rcon $rcon ([ref] $requestId) 'drop-once' -RetryDelayMilliseconds 100
    if ($body -ne 'ok:drop-once') { throw "the retry did not recover: $body" }
    $deliveries = @(Get-Content -LiteralPath $logPath | Where-Object { $_ -eq 'drop-once' })
    if ($deliveries.Count -ne 2) { throw "expected the command to be delivered twice, got $($deliveries.Count)" }
    Write-Host "socket close: recovered, command delivered $($deliveries.Count)x on a fresh connection"

    # 2) a silent peer: the receive timeout is a transient error too.
    Remove-Item -LiteralPath $logPath -Force
    New-Item -ItemType File -Path $logPath -Force | Out-Null
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $body = Invoke-Rcon $rcon ([ref] $requestId) 'silent' -MaxAttempts 5 -RetryDelayMilliseconds 200
    $sw.Stop()
    if ($body -ne 'ok:silent') { throw "the timeout retry did not recover: $body" }
    $deliveries = @(Get-Content -LiteralPath $logPath | Where-Object { $_ -eq 'silent' })
    Write-Host ("silent peer: recovered after {0} ms, command delivered {1}x" -f
        [int] $sw.ElapsedMilliseconds, $deliveries.Count)

    # 3) an exhausted budget reports how many attempts were made.
    Remove-Item -LiteralPath $logPath -Force
    New-Item -ItemType File -Path $logPath -Force | Out-Null
    $failed = $false
    try {
        [void] (Invoke-Rcon $rcon ([ref] $requestId) 'always-silent' -MaxAttempts 2 -RetryDelayMilliseconds 100)
    } catch {
        $failed = $true
        if ($_.Exception.Message -notmatch 'failed after 2 attempt') { throw "unexpected failure text: $($_.Exception.Message)" }
        Write-Host "bounded: $($_.Exception.Message.Split([char] 10)[0])"
    }
    if (-not $failed) { throw 'a permanently silent peer should exhaust the retry budget' }

    # 3b) a closed port is a transport failure too (the server is gone, so a
    #     re-connect is retried rather than aborting the run immediately).
    $closed = New-RconConnection -Port ($Port + 1) -Password 'proof' -TimeoutMilliseconds 2000
    $failed = $false
    try {
        [void] (Invoke-Rcon $closed ([ref] $requestId) 'hello' -MaxAttempts 3 -RetryDelayMilliseconds 100)
    } catch {
        $failed = $true
        if ($_.Exception.Message -notmatch 'failed after 3 attempt') { throw "unexpected failure text: $($_.Exception.Message)" }
        Write-Host "closed port: retried 3 attempts before reporting the failure"
    }
    if (-not $failed) { throw 'a closed port must fail after the bounded retries' }
    Close-Rcon $closed

    # 4) a non-transport error (a mismatched response id) is not retried.
    Remove-Item -LiteralPath $logPath -Force
    New-Item -ItemType File -Path $logPath -Force | Out-Null
    $failed = $false
    try {
        [void] (Invoke-Rcon $rcon ([ref] $requestId) 'wrong-id' -RetryDelayMilliseconds 100)
    } catch {
        $failed = $true
        if ($_.Exception.Message -notmatch 'Unexpected RCON response id') { throw "unexpected failure text: $($_.Exception.Message)" }
        Write-Host "non-transient: $($_.Exception.Message)"
    }
    if (-not $failed) { throw 'a mismatched response id must not be swallowed' }
    $deliveries = @(Get-Content -LiteralPath $logPath | Where-Object { $_ -eq 'wrong-id' })
    if ($deliveries.Count -ne 1) { throw "a non-transient error must not be retried, got $($deliveries.Count) deliveries" }

    Write-Host ("proof passed: transport failures=$($rcon.TransportFailures) retries=$($rcon.Retries)")
    Close-Rcon $rcon
} finally {
    Stop-Job $job -ErrorAction SilentlyContinue
    Remove-Job $job -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $logPath -Force -ErrorAction SilentlyContinue
}

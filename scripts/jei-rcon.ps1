# RCON client shared by the harness scripts, with bounded retry and backoff.
#
# The declared smoke died once mid-run when the server closed its RCON socket
# (no crash report; the machine was loaded). One transient socket close must not
# abort a ten-minute run, so delivery is retried with exponential backoff on a
# fresh connection. Every command the harnesses send is safe to re-issue: the
# importers apply a fixed patch set (writing the same files again), `reload` and
# `stop` are idempotent, and the waits around them re-poll.
#
# A connection object is a PSCustomObject shared by reference:
#   New-RconConnection -Port 25575 -Password 'secret'
#   Connect-Rcon $connection
#   Invoke-Rcon $connection ([ref] $requestId) 'help jeieditor'
#   Close-Rcon $connection

[CmdletBinding()]
param()

function New-RconConnection {
    param(
        [Parameter(Mandatory = $true)][int] $Port,
        [Parameter(Mandatory = $true)][string] $Password,
        # A silent peer must not block forever: a receive timeout surfaces as a
        # transient IO error and is retried like a closed socket.
        [int] $TimeoutMilliseconds = 30000
    )
    return [PSCustomObject] @{
        Port = $Port
        Password = $Password
        TimeoutMilliseconds = $TimeoutMilliseconds
        Client = $null
        Stream = $null
        # Diagnostics: how often the transport failed and had to be rebuilt.
        TransportFailures = 0
        Retries = 0
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
    $Stream.Flush()
}

function Receive-RconPacket {
    param($Stream)
    $header = New-Object byte[] 4
    $offset = 0
    while ($offset -lt 4) {
        $read = $Stream.Read($header, $offset, 4 - $offset)
        if ($read -le 0) { throw 'RCON connection closed while reading a packet' }
        $offset += $read
    }
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

# Whether an exception is a transport failure that a fresh connection may fix.
# Anything else (a bad password, an unexpected response id) is a real error and
# is re-thrown instead of retried.
function Test-RconTransientError {
    param([Parameter(Mandatory = $true)]$ErrorRecord)
    $transientTypes = @(
        'System.IO.IOException',
        'System.Net.Sockets.SocketException',
        'System.TimeoutException',
        'System.ObjectDisposedException',
        'System.InvalidOperationException'
    )
    # A .NET method that throws is reported as a MethodInvocationException that
    # wraps the real one, so the chain is walked - and the message match below is
    # only a fallback because a localized runtime does not always use English.
    $exception = $ErrorRecord.Exception
    for ($depth = 0; $depth -lt 6 -and $null -ne $exception; $depth++) {
        if ($transientTypes -contains $exception.GetType().FullName) { return $true }
        $message = [string] $exception.Message
        if ($message -match 'RCON connection closed|Invalid RCON packet length|Unable to read data|Unable to write data|closed by the remote|forcibly closed|prematurely|connection was closed|.\u5df2\u5c06\u5176\u5173\u95ed|.\u65f6\u95f4\u5185\u6ca1\u6709\u6b63\u786e\u54cd\u5e94') {
            return $true
        }
        $exception = $exception.InnerException
    }
    return $false
}

function Close-Rcon {
    param($Connection)
    if ($null -eq $Connection) { return }
    if ($null -ne $Connection.Stream) {
        try { $Connection.Stream.Dispose() } catch { }
        $Connection.Stream = $null
    }
    if ($null -ne $Connection.Client) {
        try { $Connection.Client.Dispose() } catch { }
        $Connection.Client = $null
    }
}

function Connect-Rcon {
    param($Connection)
    Close-Rcon $Connection
    $client = [Net.Sockets.TcpClient]::new('127.0.0.1', $Connection.Port)
    $client.ReceiveTimeout = $Connection.TimeoutMilliseconds
    $client.SendTimeout = $Connection.TimeoutMilliseconds
    $stream = $client.GetStream()
    $Connection.Client = $client
    $Connection.Stream = $stream
    $authId = 99
    Send-RconPacket $stream $authId 3 $Connection.Password
    $auth = Receive-RconPacket $stream
    if ($auth.Id -ne $authId) {
        # Not a transport problem: the server answered, so retrying is pointless.
        Close-Rcon $Connection
        throw 'RCON authentication failed'
    }
}

# Delivers one command, retrying transient transport failures up to
# -MaxAttempts with exponential backoff on a re-established connection.
#
# -RetryResponseRegex also re-sends while the server answers with that text
# (the "another recipe reload is in progress" answer says nothing about this
# command and is transient by design).
function Invoke-Rcon {
    param(
        $Connection,
        [ref] $RequestId,
        [string] $Command,
        [int] $MaxAttempts = 4,
        [string] $RetryResponseRegex,
        [int] $RetryDelayMilliseconds = 500
    )
    $lastError = $null
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            if ($null -eq $Connection.Stream) {
                if ($attempt -gt 1) { $Connection.TransportFailures++ }
                Connect-Rcon $Connection
            }
            $RequestId.Value++
            $request = $RequestId.Value
            Send-RconPacket $Connection.Stream $request 2 $Command
            $response = Receive-RconPacket $Connection.Stream
            if ($response.Id -ne $request) {
                throw "Unexpected RCON response id $($response.Id), expected $request"
            }
            if ($RetryResponseRegex -and $attempt -lt $MaxAttempts -and $response.Body -match $RetryResponseRegex) {
                $Connection.Retries++
                Start-Sleep -Milliseconds $RetryDelayMilliseconds
                continue
            }
            return $response.Body
        } catch {
            $lastError = $_
            if (-not (Test-RconTransientError -ErrorRecord $_)) { throw }
            Close-Rcon $Connection
            if ($attempt -ge $MaxAttempts) { break }
            $Connection.TransportFailures++
            $Connection.Retries++
            $delay = [int] ($RetryDelayMilliseconds * [Math]::Pow(2, $attempt - 1))
            Write-Warning ("RCON command '{0}' failed ({1}); retrying in {2} ms (attempt {3}/{4})" -f
                $Command, $_.Exception.Message, $delay, ($attempt + 1), $MaxAttempts)
            Start-Sleep -Milliseconds $delay
        }
    }
    throw ("RCON command '{0}' failed after {1} attempt(s) with backoff: {2}" -f
        $Command, $MaxAttempts, $lastError.Exception.Message)
}

# Waits for the RCON port to accept a connection, i.e. for the server to boot.
function Wait-RconPort {
    param([int] $Port, [int] $TimeoutSeconds, $Process, [string] $Hint)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($null -ne $Process -and $Process.HasExited) {
            $suffix = if ($Hint) { "; inspect $Hint" } else { '' }
            throw "Server process exited before RCON became available$suffix"
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

# Polls a condition until it holds or the deadline passes. The condition is
# re-evaluated; a transient error thrown by it is treated as "not yet".
function Wait-ForCondition {
    param(
        $Condition,
        [int] $TimeoutSeconds,
        [int] $IntervalMilliseconds = 500
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ($true) {
        $result = $null
        try {
            $result = & $Condition
        } catch {
            $result = $null
        }
        if ($result) { return $true }
        if ([DateTime]::UtcNow -ge $deadline) { return $false }
        Start-Sleep -Milliseconds $IntervalMilliseconds
    }
}

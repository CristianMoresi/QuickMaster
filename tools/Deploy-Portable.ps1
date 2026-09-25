param(
    [Parameter(Mandatory)][string]$ImagePath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedJarSha256,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$source = (Get-Item -LiteralPath $ImagePath).FullName
$workspace = (Get-Item -LiteralPath (Join-Path $PSScriptRoot '..')).FullName
$resultPath = Join-Path $workspace 'target\deployment-result.json'
$install = 'C:\Program Files\QuickMaster'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stage = "C:\Program Files\QuickMaster-stage-$stamp"
$backup = "C:\Program Files\QuickMaster-backup-$stamp"
$failed = "C:\Program Files\QuickMaster-failed-$stamp"
$appProcess = $null
$oldMoved = $false
$newMoved = $false
$result = [ordered]@{ status = 'STARTED'; source = $source; install = $install; expectedJarSha256 = $ExpectedJarSha256; backup = $null }

function Assert-InstallPath([string]$Path, [string]$Prefix) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if ([IO.Path]::GetDirectoryName($absolute) -ne 'C:\Program Files' -or
            -not [IO.Path]::GetFileName($absolute).StartsWith($Prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unexpected deployment target: $absolute"
    }
    if (Test-Path -LiteralPath $absolute) {
        $item = Get-Item -LiteralPath $absolute -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Linked deployment target: $absolute" }
    }
}

function Read-NewLog([string]$Path, [long]$Offset) {
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        if ($stream.Length -lt $Offset) { throw 'Application log rotated during startup verification.' }
        [void]$stream.Seek($Offset, [IO.SeekOrigin]::Begin)
        $reader = [IO.StreamReader]::new($stream)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $stream.Dispose() }
}

function Close-OwnedApplication {
    if ($null -ne $script:appProcess) {
        $script:appProcess.Refresh()
        if (-not $script:appProcess.HasExited) {
            [void]$script:appProcess.CloseMainWindow()
            if (-not $script:appProcess.WaitForExit(5000)) { $script:appProcess.Kill(); $script:appProcess.WaitForExit() }
        }
    }
}

try {
    foreach ($required in @('QuickMaster.exe', 'app\quickmaster.jar', 'app\.jpackage.xml', 'runtime\lib\modules')) {
        if (-not (Test-Path -LiteralPath (Join-Path $source $required) -PathType Leaf)) { throw "Incomplete portable image: $required" }
    }
    if ($source -eq $install) { throw 'Source image must be separate from the installed application.' }
    if (@(Get-ChildItem -LiteralPath $source -Recurse -Force | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }).Count -ne 0) {
        throw 'Portable source image contains a linked entry.'
    }
    $sourceHash = (Get-FileHash -LiteralPath (Join-Path $source 'app\quickmaster.jar') -Algorithm SHA256).Hash
    if ($sourceHash -ne $ExpectedJarSha256) { throw 'Source JAR does not match the verified build.' }
    $files = @(Get-ChildItem -LiteralPath $source -Recurse -File)
    $result.sourceFiles = $files.Count
    if ($ValidateOnly) {
        $result.status = 'IMAGE_VALIDATED'
    } else {
        $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
        if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
            throw 'Run this deployment script from an administrator PowerShell window.'
        }
        if (@(Get-Process -Name QuickMaster -ErrorAction SilentlyContinue).Count -ne 0) { throw 'Close QuickMaster before deployment.' }
        Assert-InstallPath $install 'QuickMaster'
        Assert-InstallPath $stage 'QuickMaster-stage-'
        Assert-InstallPath $backup 'QuickMaster-backup-'
        Assert-InstallPath $failed 'QuickMaster-failed-'
        foreach ($reserved in @($stage, $backup, $failed)) { if (Test-Path -LiteralPath $reserved) { throw "Reserved path already exists: $reserved" } }

        Copy-Item -LiteralPath $source -Destination $stage -Recurse
        foreach ($file in $files) {
            $relative = $file.FullName.Substring($source.Length + 1)
            if ((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne
                    (Get-FileHash -LiteralPath (Join-Path $stage $relative) -Algorithm SHA256).Hash) {
                throw "Copied image differs: $relative"
            }
        }
        if (Test-Path -LiteralPath $install) {
            Move-Item -LiteralPath $install -Destination $backup
            $oldMoved = $true
            $result.backup = $backup
        }
        Move-Item -LiteralPath $stage -Destination $install
        $newMoved = $true
        $installedHash = (Get-FileHash -LiteralPath (Join-Path $install 'app\quickmaster.jar') -Algorithm SHA256).Hash
        if ($installedHash -ne $ExpectedJarSha256) { throw 'Installed JAR hash differs from the verified build.' }

        $log = Join-Path ([Environment]::GetFolderPath('ApplicationData')) 'QuickMaster\quickmaster.log'
        $offset = if (Test-Path -LiteralPath $log) { (Get-Item -LiteralPath $log).Length } else { 0 }
        $appProcess = Start-Process -FilePath (Join-Path $install 'QuickMaster.exe') -WindowStyle Hidden -PassThru
        $newLog = ''
        for ($attempt = 0; $attempt -lt 15; $attempt++) {
            Start-Sleep -Seconds 1
            $appProcess.Refresh()
            $newLog = Read-NewLog $log $offset
            if ($appProcess.HasExited) { throw 'Installed executable exited during startup.' }
            if ($newLog -match 'Application starting \(JavaFX\)' -and $newLog -match 'Controller initialised\.') { break }
        }
        if ($newLog -notmatch 'Application starting \(JavaFX\)' -or $newLog -notmatch 'Controller initialised\.' -or
                $newLog -match '(?m) (ERROR|SEVERE) ') { throw "Installed startup verification failed: $newLog" }
        Close-OwnedApplication
        $result.installedJarSha256 = $installedHash
        $result.startupLog = $newLog.Trim()
        $result.status = 'DEPLOYED_AND_VERIFIED'
    }
} catch {
    $result.status = 'FAILED'
    $result.error = $_.Exception.Message
    try {
        Close-OwnedApplication
        if ($newMoved) {
            Assert-InstallPath $install 'QuickMaster'
            Assert-InstallPath $failed 'QuickMaster-failed-'
            Move-Item -LiteralPath $install -Destination $failed
            $result.failedImage = $failed
        }
        if ($oldMoved) {
            Assert-InstallPath $backup 'QuickMaster-backup-'
            Assert-InstallPath $install 'QuickMaster'
            Move-Item -LiteralPath $backup -Destination $install
            $result.rollback = 'RESTORED_PREVIOUS_IMAGE'
        }
    } catch { $result.rollbackError = $_.Exception.Message }
} finally {
    $result.completedAt = [DateTimeOffset]::Now.ToString('o')
    New-Item -ItemType Directory -Path (Split-Path -Parent $resultPath) -Force | Out-Null
    $result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resultPath -Encoding UTF8
    $result | ConvertTo-Json -Depth 4 | Write-Output
}
if ($result.status -eq 'FAILED') { exit 1 }

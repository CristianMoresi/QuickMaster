# Checks the backup cleanup of Deploy-Portable.ps1 on a temporary folder. Needs no administrator
# rights and touches nothing under Program Files.
param([string]$Script = (Join-Path $PSScriptRoot 'Deploy-Portable.ps1'))
$ErrorActionPreference = 'Stop'

# The function under test is taken from the deployment script itself.
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Script, [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw "Script does not parse: $($errors[0].Message)" }
$function = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Remove-SupersededBackups' }, $true)
if ($null -eq $function) { throw 'Remove-SupersededBackups not found.' }
. ([scriptblock]::Create($function.Extent.Text))

function New-Tree([string]$Root) {
    if (Test-Path -LiteralPath $Root) { Remove-Item -LiteralPath $Root -Recurse -Force }
    foreach ($name in @('QuickMaster', 'QuickMaster-backup-20260925-063849\app', 'QuickMaster-backup-20260927-024046',
            'QuickMaster-backup-20261124-224205', 'QuickMaster-backup-20261125-181500', 'QuickMaster-backup-old',
            'QuickMaster-backup-20260101-000000-extra', 'QuickMaster-failed-20260101-000000',
            'Other\QuickMaster-backup-20200101-000000', 'Elsewhere')) {
        New-Item -ItemType Directory -Path (Join-Path $Root $name) -Force | Out-Null
    }
    Set-Content -LiteralPath (Join-Path $Root 'QuickMaster-backup-20260925-063849\app\quickmaster.jar') -Value 'old'
    Set-Content -LiteralPath (Join-Path $Root 'QuickMaster\QuickMaster.exe') -Value 'current'
    Set-Content -LiteralPath (Join-Path $Root 'QuickMaster-backup-20250101-000000') -Value 'a file, not a directory'
    Set-Content -LiteralPath (Join-Path $Root 'Elsewhere\keep.txt') -Value 'target of a link'
}
function Names([string]$Root) { @(Get-ChildItem -LiteralPath $Root -Force | Sort-Object Name | ForEach-Object { $_.Name }) -join '|' }
function Check([bool]$Ok, [string]$What) { if (-not $Ok) { throw "FAILED: $What" } else { "ok: $What" } }

$root = Join-Path ([IO.Path]::GetTempPath()) ("qm-backup-cleanup-" + [guid]::NewGuid().ToString('N'))
try {
    New-Tree $root
    $before = Names $root
    $removed = Remove-SupersededBackups $root 2
    Check (($removed -join '|') -eq 'QuickMaster-backup-20260927-024046|QuickMaster-backup-20260925-063849') "the two oldest are removed: $($removed -join ', ')"
    Check ((Names $root) -eq 'Elsewhere|Other|QuickMaster|QuickMaster-backup-20250101-000000|QuickMaster-backup-20260101-000000-extra|QuickMaster-backup-20261124-224205|QuickMaster-backup-20261125-181500|QuickMaster-backup-old|QuickMaster-failed-20260101-000000') "everything else stays: $(Names $root)"
    Check (Test-Path -LiteralPath (Join-Path $root 'Other\QuickMaster-backup-20200101-000000')) 'a backup that is not directly under the root stays'
    Check (Test-Path -LiteralPath (Join-Path $root 'QuickMaster\QuickMaster.exe')) 'the installed application stays'
    Check (@(Remove-SupersededBackups $root 2).Count -eq 0) 'a second pass removes nothing'

    New-Tree $root
    Check (@(Remove-SupersededBackups $root 10000).Count -eq 0 -and (Names $root) -eq $before) 'keeping more than there are removes nothing'
    Check (@(Remove-SupersededBackups $root 1) -join '|' -eq 'QuickMaster-backup-20261124-224205|QuickMaster-backup-20260927-024046|QuickMaster-backup-20260925-063849') 'keeping one leaves the most recent'
    Check (Test-Path -LiteralPath (Join-Path $root 'QuickMaster-backup-20261125-181500')) 'the most recent backup stays'
    $threw = $false; try { Remove-SupersededBackups $root 0 | Out-Null } catch { $threw = $true }
    Check $threw 'keeping none is refused'

    # A backup that is a link is never followed: the cleanup stops and its target is intact.
    New-Tree $root
    New-Item -ItemType Junction -Path (Join-Path $root 'QuickMaster-backup-20200202-020202') -Target (Join-Path $root 'Elsewhere') | Out-Null
    $threw = $false; try { Remove-SupersededBackups $root 2 | Out-Null } catch { $threw = $_.Exception.Message -like 'Linked backup*' }
    Check $threw 'a linked backup stops the cleanup'
    Check (Test-Path -LiteralPath (Join-Path $root 'Elsewhere\keep.txt')) 'the target of the link is intact'
    "BACKUP_CLEANUP_PASS edition=$($PSVersionTable.PSEdition) version=$($PSVersionTable.PSVersion)"
} finally {
    if (Test-Path -LiteralPath $root) {
        $link = Join-Path $root 'QuickMaster-backup-20200202-020202'
        if (Test-Path -LiteralPath $link) { [IO.Directory]::Delete($link) }
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}

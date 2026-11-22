[CmdletBinding(PositionalBinding=$false)]
param(
    [Parameter(Position=0)][ValidateSet('setup','build','prepare','play','command','check','quick','ci','profile')][string]$Action='play',
    [string]$Name,
    [int]$Port=25870,
    [string]$Text,
    [Parameter(ValueFromRemainingArguments=$true)][string[]]$Rest
)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    if ($Action -eq 'setup') {
        & python "$PSScriptRoot/tools/setup.py" @Rest
    } elseif ($Action -in 'check','quick','ci','profile') {
        # CI runs in Workbench: actions.toml's workflow from a clean clone of HEAD (quick: of this
        # working tree, as it would be committed). WB_CLI names another wb launcher.
        $wb = if ($env:WB_CLI) { $env:WB_CLI } else { Join-Path $PSScriptRoot '..\WorkbenchReloaded\bin\wb.cmd' }
        $commit = if ($Action -eq 'quick') { 'worktree' } else { 'HEAD' }
        & $wb run ci.run project=riven "commit=$commit" "workflow=$Action" @Rest
    } else {
        if (-not $Name) { throw "riven.ps1 $Action needs -Name <instance>" }
        $runtimeArgs=@("$PSScriptRoot/tools/runtime.py",$Action,'--name',$Name,'--port',"$Port")
        if ($Text) { $runtimeArgs+=@('--text',$Text) }
        & python @runtimeArgs
    }
    if ($LASTEXITCODE -ne 0) { throw "Riven $Action failed ($LASTEXITCODE)" }
} finally { Pop-Location }

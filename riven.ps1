[CmdletBinding(PositionalBinding=$false)]
param(
    [Parameter(Position=0)][ValidateSet('setup','build','prepare','play','command','check','quick','ci','profile')][string]$Action='play',
    [string]$Name,
    [int]$Port=25870,
    [string]$Text,
    [Parameter(ValueFromRemainingArguments=$true)][string[]]$Rest=@()
)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    # Workbench runs the checks and resolves the launch. WB_CLI names another wb launcher.
    $wb = if ($env:WB_CLI) { $env:WB_CLI } else { Join-Path $PSScriptRoot '..\WorkbenchReloaded\bin\wb.cmd' }
    if ($Action -eq 'setup') {
        & python "$PSScriptRoot/tools/setup.py" @Rest
    } elseif ($Action -in 'check','quick','ci') {
        # project.toml's gate, from this worktree as it would be committed; pure checks whose inputs passed
        # before are not run again.
        & $wb check @Rest
    } elseif ($Action -eq 'profile') {
        # The golden windows resampled and timed; each step's timing lines are in the run's summary.
        & $wb check atlas-golden-profile @Rest
    } elseif ($Action -eq 'build') {
        # Building uses no instance.
        & python "$PSScriptRoot/tools/runtime.py" build
    } else {
        if (-not $Name) { throw "riven.ps1 $Action needs -Name <instance>" }
        $runtimeArgs=@("$PSScriptRoot/tools/runtime.py",$Action,'--name',$Name,'--port',"$Port")
        if ($Action -eq 'prepare') {
            # Every mod an instance loads comes from the launch file, resolved once from the set this worktree
            # builds against: Riven itself from the Store, by the tree CI built.
            & $wb run lab.resolve "folder=$PSScriptRoot"
            if ($LASTEXITCODE -ne 0) { throw "Resolving the launch failed ($LASTEXITCODE)" }
            $runtimeArgs+=@('--launch',"$PSScriptRoot/.local/launch/default.json")
        }
        if ($Text) { $runtimeArgs+=@('--text',$Text) }
        & python @runtimeArgs @Rest
    }
    if ($LASTEXITCODE -ne 0) { throw "Riven $Action failed ($LASTEXITCODE)" }
} finally { Pop-Location }

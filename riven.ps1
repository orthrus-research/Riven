[CmdletBinding()]
param(
    [ValidateSet('setup','build','prepare','play','command','check','ci','full','actions')][string]$Action='play',
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
    } elseif ($Action -in 'check','ci','full') {
        # Local actions: tools/actions.toml defines the workflows.
        & python "$PSScriptRoot/tools/actions.py" $Action @Rest
    } elseif ($Action -eq 'actions') {
        & python "$PSScriptRoot/tools/actions.py" @Rest
    } else {
        if (-not $Name) { throw "riven.ps1 $Action needs -Name <instance>" }
        $runtimeArgs=@("$PSScriptRoot/tools/runtime.py",$Action,'--name',$Name,'--port',"$Port")
        if ($Text) { $runtimeArgs+=@('--text',$Text) }
        & python @runtimeArgs
    }
    if ($LASTEXITCODE -ne 0) { throw "Riven $Action failed ($LASTEXITCODE)" }
} finally { Pop-Location }

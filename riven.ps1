[CmdletBinding(PositionalBinding=$false)]
param(
    [Parameter(Position=0)][ValidateSet('setup','build','prepare','play','command','check','quick','ci','full','profile','actions')][string]$Action='play',
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
    } elseif ($Action -in 'check','quick','ci','full') {
        # Local actions: tools/actions.toml defines the workflows.
        & python "$PSScriptRoot/tools/actions.py" $Action @Rest
    } elseif ($Action -eq 'profile') {
        # ci with timed golden windows, then a Markdown timing report beside the run's summary.
        & python "$PSScriptRoot/tools/actions.py" profile @Rest
        if ($LASTEXITCODE -ne 0) { Write-Warning "profile run failed ($LASTEXITCODE); reporting what ran" }
        & python "$PSScriptRoot/tools/ci_timing.py"
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

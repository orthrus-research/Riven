[CmdletBinding(PositionalBinding=$false)]
param(
    [Parameter(Position=0)][ValidateSet('play','build','check','quick','ci','profile')][string]$Action='play',
    [Parameter(ValueFromRemainingArguments=$true)][string[]]$Rest=@()
)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    # Workbench runs the checks and plays the game. WB_CLI names another wb launcher.
    $wb = if ($env:WB_CLI) { $env:WB_CLI } else { Join-Path $PSScriptRoot '..\WorkbenchReloaded\bin\wb.cmd' }
    if ($Action -eq 'play') {
        # wb play from this worktree: the launch resolved at its .set, the instance built (or reused) from the launch
        # file, and a session under the GPU lease; options such as --tour, --driver and --param pass through.
        & $wb play @Rest
    } elseif ($Action -in 'check','quick','ci') {
        # project.toml's gate, from this worktree as it would be committed; pure checks whose inputs passed
        # before are not run again.
        & $wb check @Rest
    } elseif ($Action -eq 'profile') {
        # The golden windows resampled and timed; each step's timing lines are in the run's summary.
        & $wb check atlas-golden-profile @Rest
    } else {
        # A build here by hand, for the set this worktree's .set names; the JDKs are the Gradle home's (wb setup).
        # Building uses no instance. CI's build is the riven-build check.
        $gradlew = Join-Path $PSScriptRoot $(if ($env:OS -eq 'Windows_NT') { 'gradlew.bat' } else { 'gradlew' })
        & $gradlew assemble @Rest
    }
    if ($LASTEXITCODE -ne 0) { throw "Riven $Action failed ($LASTEXITCODE)" }
} finally { Pop-Location }

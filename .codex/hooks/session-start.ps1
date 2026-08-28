[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

function Invoke-RepoGit {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(
            & git `
                -c "safe.directory=$script:safeRepoRoot" `
                -c 'core.quotepath=false' `
                -C $script:repoRoot `
                @Arguments 2>&1
        )
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    if ($exitCode -ne 0) {
        $message = @($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "git $($Arguments -join ' ') failed with exit code $exitCode.`n$message"
    }

    return @($output | ForEach-Object { $_.ToString() })
}

try {
    $eventJson = [Console]::In.ReadToEnd()
    $source = 'unknown'

    if (-not [string]::IsNullOrWhiteSpace($eventJson)) {
        $eventData = $eventJson | ConvertFrom-Json
        $sourceProperty = $eventData.PSObject.Properties['source']
        if ($null -ne $sourceProperty) {
            $source = [string] $sourceProperty.Value
        }
    }

    $script:repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $script:safeRepoRoot = $script:repoRoot.Replace('\', '/')

    $branch = (@(Invoke-RepoGit -Arguments @('branch', '--show-current')) -join '').Trim()
    if ([string]::IsNullOrWhiteSpace($branch)) {
        $shortHead = (@(Invoke-RepoGit -Arguments @('rev-parse', '--short', 'HEAD')) -join '').Trim()
        $branch = "detached@$shortHead"
    }

    $changes = @(Invoke-RepoGit -Arguments @('status', '--short'))
    $workingTree = if ($changes.Count -eq 0) { 'clean' } else { "$($changes.Count) changed path(s)" }

    Write-Output @"
Pickple backend repository context:
- Session source: $source
- Git root: $script:repoRoot
- Current branch: $branch
- Working tree: $workingTree
- Read and follow AGENTS.md before changing files.
- Preserve existing user changes and keep work inside the requested scope.
- Do not commit, push, open or edit a PR, or deploy unless the user explicitly asks.
- This is a Java 25 / Spring Boot / Gradle project; use the OS-appropriate Gradle Wrapper and report the exact validation scope.
- Use the repository resolve-problem skill for non-obvious bugs, performance issues, or multi-component diagnosis.
- Report the current branch after code work.
"@
    exit 0
}
catch {
    Write-Output 'Pickple session context could not be loaded. Review the local hook script and Git access.'
    exit 0
}

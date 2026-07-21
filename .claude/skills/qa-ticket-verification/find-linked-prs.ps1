<#
.SYNOPSIS
  Find the pull request(s) linked to a Jira issue — the definitive method.

.DESCRIPTION
  Queries Jira's Development-panel data (the internal "dev-status" API) and returns
  exactly what the "Development" panel shows in the Jira browser UI: every PR linked to
  the ticket by the connected source-control app (Azure DevOps via the GitKraken "Git
  Integration for Jira" plugin, in CData's case), including back-port PRs on other
  branches that a branch-name or commit-message search would miss.

  This is the primary PR-discovery path for the qa-ticket-verification skill's Phase 2.
  It supersedes comment-scraping and branch/commit search, which are unreliable.

  REQUIRES a CLASSIC Atlassian API token (used with HTTP Basic auth). OAuth 2.0 / scoped
  tokens are rejected by the dev-status endpoint — do not use those.

  Configuration is read from environment variables (set once in
  .claude/settings.local.json under "env" — see ONBOARDING.md Phase 5):

    JIRA_API_TOKEN   (required)  Classic Atlassian API token
    JIRA_USER_EMAIL  (required)  Email of the account that owns the token
    JIRA_BASE_URL    (optional)  Jira site URL. Default: https://cdatajira.atlassian.net

.PARAMETER IssueKey
  The Jira issue key, e.g. DRIVERS-60403.

.PARAMETER Json
  Emit the PR list as JSON instead of a table (for programmatic consumption).

.EXAMPLE
  pwsh find-linked-prs.ps1 DRIVERS-60403

.EXAMPLE
  pwsh find-linked-prs.ps1 DRIVERS-60403 -Json

.OUTPUTS
  Exit 0 = ran successfully (zero or more PRs found).
  Exit 1 = API / issue-resolution error.
  Exit 2 = missing configuration (token or email not set).
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true, Position = 0)]
  [string]$IssueKey,
  [switch]$Json
)

$ErrorActionPreference = 'Stop'

$email = $env:JIRA_USER_EMAIL
$token = $env:JIRA_API_TOKEN
$base  = if ($env:JIRA_BASE_URL) { ($env:JIRA_BASE_URL).TrimEnd('/') } else { 'https://cdatajira.atlassian.net' }

if ([string]::IsNullOrWhiteSpace($email) -or [string]::IsNullOrWhiteSpace($token)) {
  Write-Error "JIRA_USER_EMAIL and JIRA_API_TOKEN must be set (see ONBOARDING.md Phase 5). Cannot query linked PRs."
  exit 2
}

$b64     = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${email}:${token}"))
$headers = @{ Authorization = "Basic $b64"; Accept = 'application/json' }

# 1) Resolve the numeric issue ID — the dev-status API keys on the ID, not the key.
try {
  $issue = Invoke-RestMethod -Method Get -Headers $headers `
    -Uri "$base/rest/api/3/issue/$IssueKey`?fields=id"
} catch {
  Write-Error "Could not resolve issue '$IssueKey'. Check the key and that the token is valid. $($_.Exception.Message)"
  exit 1
}
$issueId = $issue.id

# 2) Summary — discover which SCM instance type(s) carry PR data for this issue.
#    (Works across instances/tools without hardcoding; for CData this resolves to
#     'oAuth-com.xiplink.jira.git.jira_git_plugin' — GitKraken Git Integration for Jira.)
try {
  $summary = Invoke-RestMethod -Method Get -Headers $headers `
    -Uri "$base/rest/dev-status/latest/issue/summary?issueId=$issueId"
} catch {
  Write-Error "dev-status summary call failed for $IssueKey ($issueId). $($_.Exception.Message)"
  exit 1
}

$instanceTypes = @()
if ($summary.summary.pullrequest.byInstanceType) {
  $instanceTypes = @($summary.summary.pullrequest.byInstanceType.PSObject.Properties.Name)
}

# 3) Detail per instance type — collect the actual PRs.
$prs = @()
foreach ($t in $instanceTypes) {
  $detail = Invoke-RestMethod -Method Get -Headers $headers `
    -Uri "$base/rest/dev-status/latest/issue/detail?issueId=$issueId&applicationType=$t&dataType=pullrequest"
  foreach ($d in $detail.detail) {
    foreach ($pr in $d.pullRequests) {
      $prs += [PSCustomObject]@{
        Id         = $pr.id
        Status     = $pr.status
        Source     = $pr.source.branch
        Target     = ($pr.destination.branch -replace '^refs/heads/', '')
        Title      = $pr.name
        Author     = $pr.author.name
        Url        = $pr.url
        LastUpdate = $pr.lastUpdate
        Repository = $pr.repositoryName
      }
    }
  }
}
$prs = @($prs | Sort-Object Id -Unique)

# 4) Output.
if ($Json) {
  if ($prs.Count -eq 0) { '[]' } else { $prs | ConvertTo-Json -Depth 5 }
  exit 0
}

if ($prs.Count -eq 0) {
  Write-Host "No pull requests linked to $IssueKey ($issueId) in the Jira Development panel."
  Write-Host "The fix may not have a linked PR, or the SCM app hasn't synced it. Fall back to"
  Write-Host "commit search, or ask the engineer for the PR link (see SKILL.md Phase 2)."
  exit 0
}

Write-Host "Pull request(s) linked to $IssueKey (issueId $issueId):`n"
$prs | Format-Table Id, Status, Source, Target, Title, Url -AutoSize -Wrap

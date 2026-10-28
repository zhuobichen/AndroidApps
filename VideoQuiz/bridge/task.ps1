param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('claim', 'complete')]
    [string]$Operation,
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,
    [Parameter(Mandatory = $true)]
    [string]$Id,
    [string]$Text = '',
    [string]$Message = 'Harness 已完成处理',
    [string]$FilesJson = '[]',
    [switch]$IncludeVideoPath
)

$ErrorActionPreference = 'Stop'
$tokenPath = Join-Path $PSScriptRoot 'data\token.txt'
$token = (Get-Content -LiteralPath $tokenPath -Raw -Encoding UTF8).Trim()
if ([string]::IsNullOrWhiteSpace($token)) { throw 'Bridge pairing token is empty.' }
$headers = @{ Authorization = "Bearer $token" }
$payload = @{ id = $Id }

if ($Operation -eq 'claim' -and $IncludeVideoPath) {
    $jobPath = Join-Path $PSScriptRoot "data\jobs\$Id\job.json"
    $job = Get-Content -LiteralPath $jobPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $payload.videoPath = Join-Path (Split-Path -Parent $jobPath) $job.storedName
    $payload.resultsPath = Join-Path (Split-Path -Parent $jobPath) 'results'
}

if ($Operation -eq 'complete') {
    $files = ConvertFrom-Json -InputObject $FilesJson
    $payload.text = $Text
    $payload.message = $Message
    $payload.files = @($files)
}

$body = ConvertTo-Json -InputObject $payload -Depth 8 -Compress
$uri = if ($Operation -eq 'claim') { "$($BaseUrl.TrimEnd('/'))/api/jobs/claim" } else { "$($BaseUrl.TrimEnd('/'))/api/jobs/complete" }
$result = Invoke-RestMethod -Uri $uri -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body
$result | ConvertTo-Json -Depth 8 -Compress

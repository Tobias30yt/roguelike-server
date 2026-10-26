param([string]$Repository = 'Tobias30yt/roguelike-server', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Use OWNER/REPO.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
function Invoke-Checked([scriptblock]$Command) {
  & $Command
  if ($LASTEXITCODE -ne 0) { throw "Command failed: $Command" }
}
Push-Location $projectRoot
try {
  Invoke-Checked { gh auth status }
  [xml]$pom = Get-Content -Raw pom.xml
  $version = $pom.project.version
  $tag = "v$version"
  $notes = "docs/releases/$tag.md"
  if (!(Test-Path -LiteralPath $notes)) { throw "Missing release notes: $notes" }
  # Never replace an already published version or its assets.
  gh release view $tag --repo $Repository 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) { throw "Release $tag exists. Choose a new project version." }
  if (!$SkipBuild) { & "$PSScriptRoot/build.ps1" }
  & "$PSScriptRoot/package.ps1"
  if (!(Test-Path -LiteralPath '.git')) { Invoke-Checked { git init -b main } }
  # Only publish project sources, documentation and scripts; never local worlds or tools.
  Invoke-Checked { git add -- pom.xml README.md .gitignore .gitattributes install.sh roguelike-core/pom.xml roguelike-core/src roguelike-paper/pom.xml roguelike-paper/src docs deploy scripts }
  git diff --cached --quiet
  if ($LASTEXITCODE -eq 1) { Invoke-Checked { git commit -m "Release $tag" } }
  elseif ($LASTEXITCODE -ne 0) { throw 'Could not inspect staged changes.' }
  $remote = git remote get-url origin 2>$null
  if ($LASTEXITCODE -ne 0) {
    gh repo view $Repository --json name 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { Invoke-Checked { git remote add origin "https://github.com/$Repository.git" } }
    else { Invoke-Checked { gh repo create $Repository --public --source . --remote origin --description 'Minecraft Paper roguelike server plugin and Ubuntu installer' } }
  } elseif ($remote -notin @("https://github.com/$Repository.git", "https://github.com/$Repository", "git@github.com:$Repository.git")) {
    throw "origin points somewhere else: $remote"
  }
  Invoke-Checked { git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push -u origin HEAD:main }
  $commit = git rev-parse HEAD
  Invoke-Checked { gh release create $tag "dist/Roguelike-$version.jar" "dist/Roguelike-$version.zip" dist/install.sh dist/SHA256SUMS.txt --repo $Repository --target $commit --prerelease --title "Roguelike $version (Testversion)" --notes-file $notes }
  Invoke-Checked { gh release view $tag --repo $Repository --json url --jq .url }
} finally { Pop-Location }

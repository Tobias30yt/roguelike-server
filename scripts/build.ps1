$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$bundledJdk = Join-Path $projectRoot '.tools/jdk25/jdk-25.0.4.1+1'
if (Test-Path -LiteralPath $bundledJdk) { $env:JAVA_HOME = $bundledJdk }
Push-Location $projectRoot
try { & mvn clean package; if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' } }
finally { Pop-Location }

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
[xml]$pom = Get-Content -Raw (Join-Path $projectRoot 'pom.xml')
$version = $pom.project.version
$jarName = "Roguelike-$version.jar"
$builtJar = Join-Path $projectRoot "roguelike-paper/target/$jarName"
if (!(Test-Path -LiteralPath $builtJar)) { throw 'Run scripts/build.ps1 first.' }
$dist = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$releaseJar = Join-Path $dist $jarName
Copy-Item -LiteralPath $builtJar -Destination $releaseJar -Force
$zipPath = Join-Path $dist "Roguelike-$version.zip"
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath }
$files = [System.Collections.Generic.List[string]]::new()
foreach ($relative in @('pom.xml', 'README.md', '.gitignore', '.gitattributes', 'install.sh', 'roguelike-core/pom.xml', 'roguelike-paper/pom.xml')) {
  $files.Add((Join-Path $projectRoot $relative))
}
foreach ($relative in @('roguelike-core/src', 'roguelike-paper/src', 'docs', 'deploy', 'scripts')) {
  Get-ChildItem -LiteralPath (Join-Path $projectRoot $relative) -Recurse -File | ForEach-Object { $files.Add($_.FullName) }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
try {
  foreach ($file in $files) {
    $relative = $file.Substring($projectRoot.Length + 1).Replace('\', '/')
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file, "Roguelike-$version/$relative", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
  }
  [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $releaseJar, "Roguelike-$version/$jarName", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $zip.Dispose() }
$releaseInstaller = Join-Path $dist 'install.sh'
Copy-Item -LiteralPath (Join-Path $projectRoot 'install.sh') -Destination $releaseInstaller -Force
$hashes = @($releaseJar, $zipPath, $releaseInstaller) | ForEach-Object {
  $hash = Get-FileHash -LiteralPath $_ -Algorithm SHA256
  "$($hash.Hash.ToLowerInvariant())  $([IO.Path]::GetFileName($_))"
}
[IO.File]::WriteAllText((Join-Path $dist 'SHA256SUMS.txt'), ($hashes -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
Get-Item -LiteralPath $releaseJar, $zipPath, $releaseInstaller | Select-Object Name, Length

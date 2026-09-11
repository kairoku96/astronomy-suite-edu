# Build and run the 3D Solar System simulation.
# Usage:  ./run.ps1            (compile + run)
#         ./run.ps1 -BuildOnly (compile only)
param([switch]$BuildOnly)

$ErrorActionPreference = "Stop"
$root  = $PSScriptRoot
$jogl  = Join-Path $root "..\jogl-2.6-b1548-20250831-windows-amd64\jogl-2.6-b1548-20250831-windows-amd64\jar"
$glue  = Join-Path $root "..\gluegen-2.6-b987-20250831-windows-amd64\gluegen-2.6-b987-20250831-windows-amd64\jar"
$flat  = (Get-ChildItem (Join-Path $root "libs") -Filter "flatlaf-*.jar" | Select-Object -First 1).FullName

$cp = @(
    (Join-Path $jogl "jogl-all.jar"),
    (Join-Path $glue "gluegen-rt.jar"),
    (Join-Path $jogl "jogl-all-natives-windows-amd64.jar"),
    (Join-Path $glue "gluegen-rt-natives-windows-amd64.jar"),
    $flat
) -join ";"

$out = Join-Path $root "out"
New-Item -ItemType Directory -Force $out | Out-Null

$sources = Get-ChildItem (Join-Path $root "src\astrosim") -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "Compiling..." -ForegroundColor Cyan
& javac -cp $cp -d $out $sources
if (-not $?) { throw "Compilation failed" }
Write-Host "Build OK" -ForegroundColor Green

if (-not $BuildOnly) {
    Write-Host "Launching..." -ForegroundColor Cyan
    & java -cp "$out;$cp" astrosim.Main
}

# Build and run the Solar System simulation (src/SolarSystemSimulation.java and friends).
#
# The sim depends on JOGL (Tier-2 GPU sphere shader in GLSphereRenderer.java),
# so it is built/run with the jogl-all + gluegen classpath below.
# It also uses `_ ->` unnamed variables (Java 22+), so it needs JDK 24, not the
# system JDK 21.
#
# Usage:  ./run2d.ps1                 build + run (GUI)
#         ./run2d.ps1 -BuildOnly      compile only
#         ./run2d.ps1 -Gpu            run with the GPU sphere shader on from the start
#         ./run2d.ps1 -Shot out\dir   render the head-less showcase PNGs and exit
#         ./run2d.ps1 -Shot out\dir -Gpu    ... with the GPU path (compare vs CPU)
#         ./run2d.ps1 -Bench          time zoomed-Earth paints (add -Gpu for the GPU path)
#         ./run2d.ps1 -Pitch 30       start with the view tilted 30 deg off top-down
#
# In-app view tilt (reveals orbital inclination + out-of-plane oscillation): drag the mouse
# vertically, or press [ / ] to tilt and \ to snap back to top-down.
param([switch]$BuildOnly, [switch]$Gpu, [switch]$Bench, [string]$Shot, [double]$Pitch, [double]$Yaw)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$jdk  = "C:/Users/el960/.jdks/openjdk-24.0.2/bin"
$jogl = Join-Path $root "..\jogl-2.6-b1548-20250831-windows-amd64\jogl-2.6-b1548-20250831-windows-amd64\jar"
$glue = Join-Path $root "..\gluegen-2.6-b987-20250831-windows-amd64\gluegen-2.6-b987-20250831-windows-amd64\jar"
$flat = (Get-ChildItem (Join-Path $root "libs") -Filter "flatlaf-*.jar" | Select-Object -First 1).FullName

$cp = @(
    $flat,
    (Join-Path $jogl "jogl-all.jar"),
    (Join-Path $glue "gluegen-rt.jar"),
    (Join-Path $jogl "jogl-all-natives-windows-amd64.jar"),
    (Join-Path $glue "gluegen-rt-natives-windows-amd64.jar")
) -join ";"

$out = Join-Path $root "out2d"
New-Item -ItemType Directory -Force $out | Out-Null

Write-Host "Compiling the sim..." -ForegroundColor Cyan
$sources = Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$ErrorActionPreference = "Continue"     # javac notes on stderr are not failures; the exit code is
& "$jdk/javac.exe" -encoding UTF-8 -cp $cp -d $out @sources
$ErrorActionPreference = "Stop"
if ($LASTEXITCODE -ne 0) { throw "Compilation failed" }
Write-Host "Build OK" -ForegroundColor Green
if ($BuildOnly) { return }

# Run from the project root so assets/textures/* resolve.
Set-Location $root
$props = @("--enable-native-access=ALL-UNNAMED")
if ($Gpu)   { $props += "-Dsss.gpu=1" }
if ($Bench) { $props += "-Dsss.bench=1" }
if ($Shot)  { $props += "-Dsss.shot=$Shot" }
if ($Pitch) { $props += "-Dsss.pitch=$Pitch" }   # initial view tilt in degrees off top-down
if ($Yaw)   { $props += "-Dsss.yaw=$Yaw" }       # initial view azimuth (orbit angle) in degrees

Write-Host "Launching..." -ForegroundColor Cyan
& "$jdk/java.exe" @props -cp "$out;$cp" SolarSystemSimulation

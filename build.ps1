Write-Host "===================================================" -ForegroundColor Cyan
Write-Host "  Compiling ChainTracker 2.0 Modular Architecture" -ForegroundColor Cyan
Write-Host "===================================================" -ForegroundColor Cyan

if (!(Test-Path "target/classes")) {
    New-Item -ItemType Directory -Force -Path "target/classes" | Out-Null
}

$sources = Get-ChildItem -Path "src/main/java" -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
javac -d target/classes $sources
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Compilation failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Copy-Item -Path "src/main/resources/*" -Destination "target/classes" -Recurse -Force
Write-Host "[SUCCESS] Build completed successfully into target/classes!" -ForegroundColor Green

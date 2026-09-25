@echo off
echo ===================================================
echo   Compiling ChainTracker 2.0 Modular Architecture
echo ===================================================

if not exist target\classes mkdir target\classes

REM Compile all java sources
dir /s /b src\main\java\*.java > sources.txt
javac -d target\classes @sources.txt
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed!
    del sources.txt
    exit /b %ERRORLEVEL%
)
del sources.txt

REM Copy web resources to target classes
xcopy /s /y /q src\main\resources\* target\classes\ >nul

echo [SUCCESS] Build completed successfully into target\classes!

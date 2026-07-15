@echo off
echo ========================================
echo  Cloudstream Extensions Builder
echo ========================================
echo.

set PLUGIN_DIR=C:\Users\yemen\Desktop\cloudstream\SexAlArab
set OUTPUT_DIR=C:\Users\yemen\Desktop\cloudstream\my-extensions-repo\builds

echo [1/3] Building plugin...
cd /d "%PLUGIN_DIR%"
call gradlew.bat build
if %errorlevel% neq 0 (
    echo Error: Build failed!
    pause
    exit /b 1
)

echo.
echo [2/3] Copying plugin file...
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
copy /Y "build\outputs\*.cs3" "%OUTPUT_DIR%\"
if %errorlevel% neq 0 (
    echo Error: Copy failed!
    pause
    exit /b 1
)

echo.
echo [3/3] Done!
echo.
echo Plugin file copied to: %OUTPUT_DIR%
echo.
echo Next steps:
echo 1. Push changes to GitHub: git add . ^&^& git commit -m "Update" ^&^& git push
echo 2. Update index.min.json if needed
echo.
pause

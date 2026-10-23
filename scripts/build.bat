@echo off
cd /d "%~dp0..\neoforge-1.21.1"
call gradlew.bat --console=plain build
exit /b %errorlevel%

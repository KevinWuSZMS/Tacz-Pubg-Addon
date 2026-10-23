@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-client.ps1" %*
exit /b %errorlevel%

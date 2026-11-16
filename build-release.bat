@echo off
rem Convenience wrapper around build-release.ps1 (see that file for details).
rem Keeps a plain double-click / cmd.exe entry point; the logic lives in PS.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-release.ps1" %*
exit /b %ERRORLEVEL%

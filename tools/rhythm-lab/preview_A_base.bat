@echo off
chcp 65001 >/dev/null
cd /d "%~dp0"
REM Chart preview A: current selection (drums win the density budget).
REM Usage: double-click to listen. Pass "rebuild" to regenerate the audio.
set WAV=%~dp0..\..\build\rhythm-lab\preview\preview_A_base.wav
if /i "%~1"=="rebuild" del "%WAV%" >/dev/null 2>&1
if not exist "%WAV%" (
    python "%~dp0experiments\preview_audio.py"
    if errorlevel 1 (
        echo Preview generation failed.
        pause
        exit /b 1
    )
)
start "" "%WAV%"

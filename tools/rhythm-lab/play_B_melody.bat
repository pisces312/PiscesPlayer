@echo off
chcp 65001 >/dev/null
cd /d "%~dp0"
REM A/B playtest, version B: melody-priority selection (3 melody + 1 percussion slots).
REM Needs RhythmChartLab at D:\3rd-party-projects\RhythmChartLab.
REM Usage: double-click to play. Args: "rebuild" regenerates, "noplay" only generates.
set PY=D:\dev\miniconda3\python.exe
"%PY%" "%~dp0experiments\play_ab.py" B %*
if errorlevel 1 pause

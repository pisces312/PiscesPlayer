@echo off
chcp 65001 >/dev/null
cd /d "%~dp0"
REM A/B playtest, version A: current selection (drums win the density budget).
REM Needs RhythmChartLab at D:\3rd-party-projects\RhythmChartLab.
REM Usage: double-click to play. Args: "rebuild" regenerates, "noplay" only generates.
set PY=D:\dev\miniconda3\python.exe
"%PY%" "%~dp0experiments\play_ab.py" A %*
if errorlevel 1 pause

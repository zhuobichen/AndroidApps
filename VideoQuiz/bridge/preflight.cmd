@echo off
rem 双击运行电脑端前置条件自检
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0preflight.ps1"
echo.
pause

@echo off
setlocal EnableDelayedExpansion
net session >nul 2>&1
if errorlevel 1 (
    echo.
    echo   [!] Needs administrator rights.
    echo       Right-click this file and choose "Run as administrator".
    echo.
    pause
    exit /b 1
)

rem Usage:
rem   allow-firewall.cmd                    - allow any source on TCP 8765 (token still required)
rem   allow-firewall.cmd 10.195.86.187      - allow only that one IP
rem   allow-firewall.cmd 10.195.86.0/24     - allow only that subnet

set "SCOPE=%~1"

net session >nul 2>&1
netsh advfirewall firewall delete rule name="VideoQuiz Bridge 8765" >nul 2>&1

if "%SCOPE%"=="" (
    echo Adding rule: TCP 8765, any source, any profile...
    netsh advfirewall firewall add rule name="VideoQuiz Bridge 8765" dir=in action=allow protocol=TCP localport=8765 profile=any
    if errorlevel 1 goto :failed
    echo.
    echo   Done. Any device that can reach this computer may now try port 8765.
    echo   Access still requires the pairing token, so unauthorised calls get 401.
    echo   Want it tighter? Re-run with an address, e.g.:
    echo       allow-firewall.cmd 10.195.86.187
) else (
    echo Adding rule: TCP 8765, only from %SCOPE%...
    netsh advfirewall firewall add rule name="VideoQuiz Bridge 8765" dir=in action=allow protocol=TCP localport=8765 profile=any remoteip=%SCOPE%
    if errorlevel 1 goto :failed
    echo.
    echo   Done. Only %SCOPE% may reach port 8765.
    echo   Note: if the phone's address changes (new Wi-Fi, new DHCP lease),
    echo         re-run this script with the new address.
)

echo.
echo   Verify: in the app, Settings -^> Test connection.
echo.
pause
exit /b 0

:failed
echo.
echo   [!] Failed to add the rule.
pause
exit /b 1

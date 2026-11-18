@echo off
setlocal
title WebTerm - Force Uninstall
echo ============================================
echo   WebTerm  Force Uninstall (fallback tool)
echo   Use this only if "Apps and features" fails.
echo ============================================
echo.

set "ROOT=%LOCALAPPDATA%\WebTerm"
echo [1/5] Closing WebTerm processes under %ROOT% ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($env:LOCALAPPDATA + '\WebTerm') } | Stop-Process -Force -ErrorAction SilentlyContinue"
timeout /t 1 /nobreak >nul

echo [2/5] Running official uninstaller ...
if exist "%ROOT%\Update.exe" (
  start "" /wait "%ROOT%\Update.exe" --uninstall
) else (
  echo      Update.exe not found, skipping.
)

echo [3/5] Removing leftover folder ...
if exist "%ROOT%" rmdir /s /q "%ROOT%"

echo [4/5] Removing uninstall registry key ...
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\WebTerm" /f >nul 2>&1

echo [5/5] Removing shortcuts ...
del /q "%APPDATA%\Microsoft\Windows\Start Menu\Programs\WebTerm-Electron.lnk" >nul 2>&1
del /q "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Cyril Web Terminal.lnk" >nul 2>&1
del /q "%USERPROFILE%\Desktop\WebTerm-Electron.lnk" >nul 2>&1
del /q "%USERPROFILE%\Desktop\Cyril Web Terminal.lnk" >nul 2>&1
del /q "%PUBLIC%\Desktop\WebTerm-Electron.lnk" >nul 2>&1

echo.
if exist "%ROOT%" (
  echo FAILED: folder still exists. Close every WebTerm window, then run this again.
) else (
  echo DONE. WebTerm has been removed.
)
echo.
pause

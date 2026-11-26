@echo off
setlocal
cd /d "%~dp0"
title VR Phone Bridge - Teste da camera

echo ========================================
echo    VR Phone Bridge - Teste da camera
echo ========================================
echo.
echo Este IP e do CELULAR, nao do PC.
set /p PHONE_IP=Digite o IPv4 do Moto G60: 
if "%PHONE_IP%"=="" goto :empty

echo.
echo Testando http://%PHONE_IP%:8080/ ...
curl.exe --connect-timeout 3 --max-time 5 -sS "http://%PHONE_IP%:8080/"
if errorlevel 1 goto :fail

echo.
echo Testando se ja existe um frame da camera...
curl.exe --connect-timeout 3 --max-time 5 -sS -o NUL -w "HTTP %%{http_code}\n" "http://%PHONE_IP%:8080/shot.jpg"
echo.
echo HTTP 200 = camera entregando frame.
echo HTTP 503 = servidor existe, mas ainda nao ha frame ARCore.
echo.
pause
exit /b 0

:empty
echo IP vazio.
pause
exit /b 1

:fail
echo Nao conectou. Abra o cliente VR no celular, conceda a permissao de camera e confira o Wi-Fi.
pause
exit /b 1

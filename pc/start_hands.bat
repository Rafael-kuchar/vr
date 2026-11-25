@echo off
setlocal
cd /d "%~dp0"
title VR Phone Bridge - Hand Tracking
echo ========================================
echo    VR Phone Bridge - Hand Tracking
echo ========================================
echo.
echo Deixe o app VR Phone Bridge aberto no celular e conectado ao mesmo Wi-Fi.
echo O endereco da camera e http://IP_DO_CELULAR:8080/video
set /p PHONE_IP=Digite o IP do celular: 
if "%PHONE_IP%"=="" (
  echo IP vazio. Nada foi iniciado.
  pause
  exit /b 1
)
python "hands__66_ (2).py" "http://%PHONE_IP%:8080/video"
if errorlevel 1 (
  echo.
  echo O rastreador terminou com erro. Confira as dependencias em requirements.txt.
  pause
)

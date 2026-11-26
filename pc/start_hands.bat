@echo off
setlocal
cd /d "%~dp0"
title VR Phone Bridge - Hand Tracking

echo ========================================
echo    VR Phone Bridge - Hand Tracking
echo ========================================
echo.
echo Este IP e do CELULAR, nao do PC.
echo O app do celular precisa estar na tela de streaming e com permissao de camera.
echo Endpoint: http://IP_DO_CELULAR:8080/video
echo.
set /p PHONE_IP=Digite o IPv4 do Moto G60: 
if "%PHONE_IP%"=="" goto :empty

echo.
echo Testando a camera do celular...
curl.exe --fail --connect-timeout 3 --max-time 5 -sS -o NUL "http://%PHONE_IP%:8080/shot.jpg"
if errorlevel 1 (
  echo Nao consegui acessar a camera ainda.
  echo Rode check_phone_camera.bat e confira o app/permissao/rede.
  pause
  exit /b 1
)

echo Iniciando MediaPipe/VMT...
python "hands__66_ (2).py" "http://%PHONE_IP%:8080/video"
if errorlevel 1 (
  echo.
  echo O rastreador terminou com erro. Confira requirements.txt e o modelo MediaPipe.
)
echo.
echo Rastreador encerrado.
pause
exit /b 0

:empty
echo IP vazio. Nada foi iniciado.
pause
exit /b 1

@echo off
REM Inicia o relay; o encoder H.264 e escolhido automaticamente.
cd /d "%~dp0"
python relay.py --stream %*
pause

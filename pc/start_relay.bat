@echo off
REM Starts the relay and streams the screen to the phone.
REM NVIDIA GPU?  use:  python relay.py --stream --encoder h264_nvenc
cd /d "%~dp0"
python relay.py --stream %*
pause

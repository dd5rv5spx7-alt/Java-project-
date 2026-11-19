@echo off
REM Compile and run Agro-CTI (Windows). MySQL must be running (see README.txt).
REM   run.bat              train + evaluate (first time), then open the dashboard at http://localhost:8090
REM   run.bat evaluate     only train and compare the detectors
REM   run.bat selftest     run the 20 unit tests (needs MySQL)
cd /d "%~dp0"
if exist out rmdir /s /q out
dir /s /b src\*.java > sources.txt
javac -d out -cp "lib\*" @sources.txt
if errorlevel 1 exit /b 1
del sources.txt
java -Xmx1g -cp "out;lib\*" com.agrocti.Main %*

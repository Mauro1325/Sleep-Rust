@echo off
setlocal

set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
    echo Java 17 no se encuentra en PATH.
    echo Revisa la instalacion de JDK 17 en: %JAVA_HOME%
    pause
    exit /b 1
)

java -version

echo.
echo Compilando el mod...
call .\gradlew.bat build --no-daemon --console=plain

if errorlevel 1 (
    echo.
    echo La build fallo.
    pause
    exit /b 1
)

echo.
echo Build OK.
echo El jar queda en: build\libs
pause

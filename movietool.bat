@echo off
rem MovieTool launcher (Windows).
rem
rem   movietool.bat              start the graphical interface
rem   movietool.bat <command>    run a command line operation
rem   movietool.bat help         list the commands
rem
rem The jar is built automatically on first use when a JDK is available.
setlocal
cd /d "%~dp0"
set "JAR=%~dp0movietool.jar"

rem ------------------------------------------------------------------ find java
set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA (
    where java >nul 2>nul && set "JAVA=java"
)
if not defined JAVA (
    echo error: no Java runtime found ^(java^).
    echo Install Java 8 or newer from https://adoptium.net or set JAVA_HOME.
    exit /b 1
)

rem ------------------------------------------------------------- build if needed
if not exist "%JAR%" (
    echo movietool.jar not found, trying to build it...
    if exist "%~dp0build.bat" (
        set SKIP_TESTS=1
        call "%~dp0build.bat"
        set "SKIP_TESTS="
    ) else (
        echo error: movietool.jar is missing and no build.bat was found to create it.
        exit /b 1
    )
)

rem No arguments: launch the window without keeping a console open.
if "%~1"=="" (
    start "" "%JAVA%" -Dfile.encoding=UTF-8 -jar "%JAR%"
    endlocal
    exit /b 0
)

"%JAVA%" -Dfile.encoding=UTF-8 -jar "%JAR%" %*
endlocal

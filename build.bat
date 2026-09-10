@echo off
rem Build script for MovieTool (Windows).
rem
rem Produces movietool.jar (a runnable fat jar) in the project root.
rem Requirements: a JDK (Java 8 or newer) on the PATH or in JAVA_HOME.

setlocal enabledelayedexpansion
cd /d "%~dp0"

rem ------------------------------------------------------------- find a JDK
set "JAVAC="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC (
    where javac >nul 2>nul && set "JAVAC=javac"
)
if not defined JAVAC (
    echo error: no JDK found ^(javac^).
    echo Install a JDK from https://adoptium.net or set JAVA_HOME to its folder.
    exit /b 1
)

rem The 'jar' tool lives next to javac; fall back to PATH.
set "JAR_TOOL="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jar.exe" set "JAR_TOOL=%JAVA_HOME%\bin\jar.exe"
if not defined JAR_TOOL (
    where jar >nul 2>nul && set "JAR_TOOL=jar"
)
if not defined JAR_TOOL (
    echo error: the 'jar' tool was not found next to javac.
    exit /b 1
)

echo Using compiler: %JAVAC%

rem ------------------------------------------------------------------ compile
if exist build rmdir /s /q build
mkdir build\classes
dir /s /b src\*.java > build\sources.txt

rem -Xlint:-options silences the harmless bootstrap-classpath note newer JDKs
rem emit for -source 8; the bytecode then runs on Java 8 and newer.
"%JAVAC%" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -d build\classes @build\sources.txt
if errorlevel 1 (
    echo error: compilation failed.
    exit /b 1
)

rem --------------------------------------------------------------------- pack
(
echo Manifest-Version: 1.0
echo Implementation-Title: MovieTool
echo Implementation-Version: 2.4.0
echo Main-Class: movies.Main
echo.
) > build\MANIFEST.MF

"%JAR_TOOL%" cfm movietool.jar build\MANIFEST.MF -C build\classes .
if errorlevel 1 (
    echo error: jar packaging failed.
    exit /b 1
)

rem ------------------------------------------------------------- optional tests
if exist test if /i "%SKIP_TESTS%" neq "1" (
    mkdir build\test-classes
    dir /s /b test\*.java > build\test-sources.txt
    "%JAVAC%" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -cp build\classes -d build\test-classes @build\test-sources.txt
    java -cp build\classes;build\test-classes movies.SelfTest
)

echo.
echo Build finished: %cd%\movietool.jar
echo Run it with:   movietool.bat            (graphical interface)
echo                movietool.bat help       (command line)
endlocal

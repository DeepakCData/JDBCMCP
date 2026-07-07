@ECHO OFF
SETLOCAL

SET "SCRIPT_DIR=%~dp0"
SET "MVN_DIST=%SCRIPT_DIR%.mvn-dist"
SET "MVN_VER=3.9.9"
SET "MVN_HOME=%MVN_DIST%\apache-maven-%MVN_VER%"
SET "MVN_EXE=%MVN_HOME%\bin\mvn.cmd"
SET "MVN_ZIP=%MVN_DIST%\apache-maven-%MVN_VER%-bin.zip"
SET "MVN_URL=https://archive.apache.org/dist/maven/maven-3/%MVN_VER%/binaries/apache-maven-%MVN_VER%-bin.zip"

IF NOT EXIST "%MVN_EXE%" (
    ECHO Maven %MVN_VER% not found. Downloading...
    IF NOT EXIST "%MVN_DIST%" MKDIR "%MVN_DIST%"
    powershell -Command "Invoke-WebRequest -Uri '%MVN_URL%' -OutFile '%MVN_ZIP%'"
    IF NOT %ERRORLEVEL%==0 (
        ECHO Download failed. Check your internet connection.
        EXIT /B 1
    )
    powershell -Command "Expand-Archive -Path '%MVN_ZIP%' -DestinationPath '%MVN_DIST%' -Force"
    DEL "%MVN_ZIP%"
    ECHO Maven downloaded to %MVN_HOME%
)

"%MVN_EXE%" %*

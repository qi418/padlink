@rem
@rem Copyright 2015 the original author or authors (Gradle wrapper, simplified).
@rem
@rem  Gradle startup script for Windows (simplified for PadLink).
@rem  Bootstrap: gradle/wrapper/gradle-wrapper.jar is intentionally not committed;
@rem  if missing, this script downloads the official Gradle 8.11.1 wrapper jar
@rem  (via curl, or PowerShell fallback) before running Gradle.
@rem  See README.md「gradle-wrapper.jar 自举说明」.
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem ---- PadLink bootstrap: download gradle-wrapper.jar when missing ----
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
if exist "%CLASSPATH%" goto findJavaFromJavaHomeStart

echo gradle-wrapper.jar 不存在，正在下载官方 Gradle 8.11.1 wrapper jar（首次构建需要联网）...
if not exist "%APP_HOME%\gradle\wrapper" mkdir "%APP_HOME%\gradle\wrapper"
set JAR_URL=https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar

where curl >nul 2>nul
if %ERRORLEVEL% EQU 0 goto downloadWithCurl
where powershell >nul 2>nul
if %ERRORLEVEL% EQU 0 goto downloadWithPowerShell
echo ERROR: gradle-wrapper.jar 缺失且本机没有 curl/powershell，请手动下载:
echo %JAR_URL%
echo 放置到 gradle\wrapper\gradle-wrapper.jar
goto fail

:downloadWithCurl
curl -fsSL "%JAR_URL%" -o "%CLASSPATH%"
if not exist "%CLASSPATH%" goto downloadFailed
goto bootstrapDone

:downloadWithPowerShell
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-WebRequest -UseBasicParsing -Uri '%JAR_URL%' -OutFile '%CLASSPATH%' } catch { exit 1 }"
if not exist "%CLASSPATH%" goto downloadFailed
goto bootstrapDone

:downloadFailed
echo ERROR: 下载失败 %JAR_URL%（网络不可用？）
goto fail

:bootstrapDone
echo 已下载 gradle-wrapper.jar

:findJavaFromJavaHomeStart
@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto execute

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation. (需要 JDK 17)
echo.
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.
echo.
goto fail

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem End local scope for the variables with windows NT shell
if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if not "%GRADLE_EXIT_CONSOLE%"=="" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega

@rem
@echo off
@if "%DEBUG%"=="" @echo on
@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" (
    setlocal
)

@rem Set the display message
set DISPLAY_MSG=Gradle wrapper

@rem Resolve the project home directory
set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_HOME=%DIRNAME%

@rem The following code is used to try to find a Java executable
@rem in the same directory as the gradlew.bat file or in the PATH

set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

if defined JAVA_EXE goto execute
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

goto noJavaFound

:findJavaFromJavaHome
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

goto noJavaFound

:noJavaFound
@rem If we reach here, no Java executable was found
@echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
@echo Please set the JAVA_HOME variable in your environment to match the
@echo location of your Java installation.

goto end

:execute
@rem Setup the command line
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem End local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" (
    endlocal
)

@rem
@echo off
@if "%DEBUG%"=="" @echo on
@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" (
    setlocal
)

@rem Set the display message
set DISPLAY_MSG=Gradle wrapper

@rem The following code is used to try to find a Java executable
@rem in the same directory as the gradlew.bat file or in the PATH

set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

set JAVA_EXE=..\bin\java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

set JAVA_EXE=..\..\bin\java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

set JAVA_EXE=..\..\..\bin\java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

set JAVA_EXE=..\..\..\..\bin\java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

:findJavaFromPath
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

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

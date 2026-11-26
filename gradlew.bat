@echo off
setlocal EnableExtensions
set "APP_HOME=%~dp0"
set "WRAPPER=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
if not exist "%WRAPPER%" (
    powershell -NoProfile -Command "$ErrorActionPreference = 'Stop'; $url = 'https://services.gradle.org/distributions/gradle-8.13-wrapper.jar'; $file = '%WRAPPER%'; $tmp = $file + '.tmp'; Invoke-WebRequest -Uri $url -OutFile $tmp; if ((Get-FileHash -Algorithm SHA256 -Path $tmp).Hash.ToLowerInvariant() -ne '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f') { Remove-Item -Force $tmp; throw 'Gradle Wrapper checksum mismatch' }; Move-Item -Force $tmp $file"
    if errorlevel 1 exit /b 1
)
if defined JAVA_HOME (
    "%JAVA_HOME%\bin\java.exe" -cp "%WRAPPER%" org.gradle.wrapper.GradleWrapperMain %*
) else (
    java -cp "%WRAPPER%" org.gradle.wrapper.GradleWrapperMain %*
)
exit /b %errorlevel%

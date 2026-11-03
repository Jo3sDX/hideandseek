@echo off
rem Builds the mod on Windows. Needs only a JDK 25 (not a JRE) and internet. Gradle is downloaded automatically.
setlocal
set GV=9.7.1
if exist gradlew.bat if exist gradle\wrapper\gradle-wrapper.jar goto build
if not exist .bootstrap mkdir .bootstrap
if not exist .bootstrap\gradle-%GV%\bin\gradle.bat powershell -NoProfile -Command "Invoke-WebRequest https://services.gradle.org/distributions/gradle-%GV%-bin.zip -OutFile .bootstrap\gradle.zip; Expand-Archive -Force .bootstrap\gradle.zip .bootstrap"
call .bootstrap\gradle-%GV%\bin\gradle.bat wrapper --gradle-version %GV%
:build
call gradlew.bat build
echo.
echo If the build succeeded, your mod is: build\libs\hideandseek-1.1.0.jar
pause

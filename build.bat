@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-17.0.9
"%JAVA_HOME%\bin\java" -version
gradlew.bat remapJar
pause
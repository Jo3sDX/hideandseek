#!/usr/bin/env bash
# Builds the mod on Linux/macOS. Needs only a JDK 25 and internet. Gradle is downloaded automatically.
set -e
GV=9.7.1
if [ ! -f gradle/wrapper/gradle-wrapper.jar ] || [ ! -f gradlew ]; then
  mkdir -p .bootstrap
  if [ ! -x ".bootstrap/gradle-$GV/bin/gradle" ]; then
    curl -fL "https://services.gradle.org/distributions/gradle-$GV-bin.zip" -o .bootstrap/gradle.zip
    unzip -qo .bootstrap/gradle.zip -d .bootstrap
  fi
  ".bootstrap/gradle-$GV/bin/gradle" wrapper --gradle-version "$GV"
fi
chmod +x gradlew
./gradlew build
echo "If the build succeeded, your mod is: build/libs/hideandseek-1.1.0.jar"

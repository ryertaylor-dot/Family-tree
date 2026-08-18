#!/bin/sh
# 精简版 Gradle Wrapper（macOS / Linux）
DIRNAME=$(dirname "$0")
if [ -n "$JAVA_HOME" ]; then
  exec "$JAVA_HOME/bin/java" -classpath "$DIRNAME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
else
  exec java -classpath "$DIRNAME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi

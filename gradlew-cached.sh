#!/bin/bash
# Use cached Gradle 8.8 instead of downloading
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.2"
GRADLE_HOME="/c/Users/ctrl-/.gradle/wrapper/dists/gradle-8.8-bin/4u0rgm4geyrm56fyhoco0c9in/gradle-8.8"
exec "$GRADLE_HOME/bin/gradle" "$@"

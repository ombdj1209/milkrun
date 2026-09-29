#!/usr/bin/env sh
# Zero-dependency build: needs only a JDK 21. Usage: ./build.sh [test|run|serve]
set -e
cd "$(dirname "$0")"
if command -v javac >/dev/null 2>&1; then JAVAC="javac"; else JAVAC="java --module jdk.compiler/com.sun.tools.javac.Main"; fi
rm -rf out && mkdir -p out/main out/test
$JAVAC -d out/main $(find src/main/java -name '*.java')
case "${1:-run}" in
  test)  $JAVAC -cp out/main -d out/test $(find src/test/java -name '*.java')
         java -cp out/main:out/test nl.milkrun.Tests ;;
  run)   java -cp out/main nl.milkrun.App ;;
  serve) java -cp out/main nl.milkrun.App --serve ;;
  *)     echo "usage: $0 [test|run|serve]"; exit 2 ;;
esac

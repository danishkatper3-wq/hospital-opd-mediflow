#!/bin/sh
cd "$(dirname "$0")"
mkdir -p out
echo "Compiling..."
javac -encoding UTF-8 -cp "lib/h2.jar" -d out src/opd/*.java src/opd/ds/*.java src/opd/model/*.java src/opd/db/*.java src/opd/service/*.java src/opd/web/*.java || { echo "Compile failed. Install JDK 11 or newer."; exit 1; }
echo "Starting MediFlow on http://localhost:8080"
java -cp "out:lib/h2.jar" opd.Main

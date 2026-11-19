#!/bin/sh
# Compile and run Agro-CTI (macOS / Linux). MySQL must be running (see README.txt).
#   ./run.sh              train + evaluate (first time), then open the dashboard at http://localhost:8090
#   ./run.sh evaluate     only train and compare the detectors
#   ./run.sh selftest     run the 20 unit tests (needs MySQL)
cd "$(dirname "$0")"
rm -rf out
javac -d out -cp "lib/*" $(find src -name "*.java") || exit 1
java -Xmx1g -cp "out:lib/*" com.agrocti.Main "$@"

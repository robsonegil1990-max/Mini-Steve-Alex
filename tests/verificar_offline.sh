#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
python3 ./VERIFICAR_PROJETO.py
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
javac -source 8 -target 8 -d "$TEMP" src/main/java/br/com/minicompanheiros/Regras.java src/main/java/br/com/minicompanheiros/Cerebro.java tests/TestarRegras.java tests/TestarCerebro.java tests/TestarSintaxe.java
java -cp "$TEMP" TestarRegras
java -cp "$TEMP" TestarCerebro
java -cp "$TEMP" TestarSintaxe src/main/java


#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

python3 ./VERIFICAR_PROJETO.py

TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT

JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
TOOLS_JAR="$JDK_HOME/lib/tools.jar"

javac -cp "$TOOLS_JAR" -source 8 -target 8 -d "$TEMP" src/main/java/br/com/minicompanheiros/Regras.java src/main/java/br/com/minicompanheiros/Cerebro.java tests/TestarRegras.java tests/TestarCerebro.java tests/TestarSintaxe.java

java -cp "$TEMP:$TOOLS_JAR" TestarRegras
java -cp "$TEMP:$TOOLS_JAR" TestarCerebro
java -cp "$TEMP:$TOOLS_JAR" TestarSintaxe src/main/java

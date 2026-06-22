#!/bin/bash
# build.sh — compila e empacota o SRTP

set -e

echo "=== Compilando SRTP ==="
rm -rf out
mkdir -p out

javac -encoding UTF-8 -d out src/srtp/*.java
echo "Compilação OK"

jar cfm srtp.jar MANIFEST.MF -C out .
echo "JAR gerado: srtp.jar ($(du -sh srtp.jar | cut -f1))"
echo ""
echo "Execute com:"
echo "  Receiver: java -jar srtp.jar --listen --port 6000 --output recebido.bin"
echo "  Sender:   java -jar srtp.jar --host <ip> --port 6000 --file <arquivo>"

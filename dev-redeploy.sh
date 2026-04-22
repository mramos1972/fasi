#!/bin/bash
# ══════════════════════════════════════════════
#  FASI — Redeploy rápido sin Jenkins
#  Uso: ./dev-redeploy.sh
# ══════════════════════════════════════════════

set -e  # Para si hay error

CONTAINER_NAME="fasi-desa"
NETWORK="desa_desa_fasi_net"
IMAGE="fasi-local:dev"

echo "🔨 [1/4] Compilando..."
./mvnw package -DskipTests -q
echo "✅ Compilación OK"

echo "🐳 [2/4] Construyendo imagen Docker..."
docker build -t $IMAGE . -q
echo "✅ Imagen construida"

echo "🛑 [3/4] Parando contenedor actual..."
docker stop $CONTAINER_NAME 2>/dev/null || true
docker rm   $CONTAINER_NAME 2>/dev/null || true
echo "✅ Contenedor parado"

echo "🚀 [4/4] Arrancando nuevo contenedor..."
docker run -d \
  --name $CONTAINER_NAME \
  --network $NETWORK \
  -p 8091:8080 \
  -e UPLOAD_DIR=/uploads \
  -e DB_HOST=pg-fasi-desa \
  -e DB_PORT=5432 \
  -e DB_NAME=fasi_desa \
  -e DB_USER=fasi \
  -e DB_PASS=fasi_desa_pass \
  -e SPRING_PROFILE=dev \
  -e OLLAMA_MODEL=gemma3:4b \
  -e OLLAMA_URL=http://172.20.77.35:11434 \
  $IMAGE

echo ""
echo "✅ ¡Listo! FASI corriendo en http://localhost:8091"
echo "📋 Logs: docker logs -f $CONTAINER_NAME"

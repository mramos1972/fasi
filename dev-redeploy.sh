#!/bin/bash
# ══════════════════════════════════════════════
#  FASI — Redeploy rápido con Ollama integrado
#  Uso: ./dev-redeploy.sh
# ══════════════════════════════════════════════

set -e
export DOCKER_BUILDKIT=0

CONTAINER_NAME="fasi-desa"
NETWORK="desa_desa_fasi_net"
IMAGE="fasi-local:dev"
OLLAMA_CONTAINER="ollama"
OLLAMA_MODEL="qwen2.5:0.5b"  # 🚀 Modelo LIGERO (10x más rápido)

# ══════════════════════════════════════════════
# 🔍 VERIFICAR OLLAMA
# ══════════════════════════════════════════════
echo "🔍 [0/5] Verificando Ollama..."

if ! docker ps | grep -q $OLLAMA_CONTAINER; then
  echo "⚠️  Ollama no está corriendo. Iniciando..."
  
  # Intentar arrancar contenedor existente
  if docker ps -a | grep -q $OLLAMA_CONTAINER; then
    docker start $OLLAMA_CONTAINER
  else
    # Crear nuevo contenedor
    docker run -d \
      --name $OLLAMA_CONTAINER \
      --restart unless-stopped \
      --memory="4g" \
      --cpus="2" \
      -v ollama_data:/root/.ollama \
      -p 11434:11434 \
      ollama/ollama
  fi
  
  echo "⏳ Esperando a que Ollama esté listo..."
  sleep 10
  
  # Descargar modelo si no existe
  if ! docker exec $OLLAMA_CONTAINER ollama list | grep -q $OLLAMA_MODEL; then
    echo "📥 Descargando modelo $OLLAMA_MODEL (esto puede tardar)..."
    docker exec $OLLAMA_CONTAINER ollama pull $OLLAMA_MODEL
  fi
fi

# Verificar que Ollama responde
if curl -s http://localhost:11434/api/tags > /dev/null 2>&1; then
  echo "✅ Ollama OK (modelo: $OLLAMA_MODEL)"
else
  echo "❌ Ollama no responde. Verifica: docker logs ollama"
  exit 1
fi

# ══════════════════════════════════════════════
# 🔨 COMPILAR Y DESPLEGAR FASI
# ══════════════════════════════════════════════
echo "🔨 [1/5] Compilando..."
./mvnw package -DskipTests -q
echo "✅ Compilación OK"

echo "🐳 [2/5] Construyendo imagen Docker..."
docker build -t $IMAGE . -q
echo "✅ Imagen construida"

echo "🛑 [3/5] Parando contenedor actual..."
docker stop $CONTAINER_NAME 2>/dev/null || true
docker rm   $CONTAINER_NAME 2>/dev/null || true
echo "✅ Contenedor parado"

echo "🚀 [4/5] Arrancando nuevo contenedor..."
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
  -e OLLAMA_MODEL=$OLLAMA_MODEL \
  -e OLLAMA_URL=http://172.20.77.35:11434 \
  $IMAGE

echo "⏳ [5/5] Esperando a que FASI arranque..."
sleep 5

# Verificar que FASI responde
if curl -s http://localhost:8091/actuator/health > /dev/null 2>&1 || \
   curl -s http://localhost:8091/ > /dev/null 2>&1; then
  echo "✅ FASI arrancado correctamente"
else
  echo "⚠️  FASI puede no haber arrancado bien. Verifica logs."
fi

echo ""
echo "════════════════════════════════════════════"
echo "✅ ¡Listo! FASI corriendo en http://localhost:8091"
echo "📋 Logs FASI:   docker logs -f $CONTAINER_NAME"
echo "📋 Logs Ollama: docker logs -f $OLLAMA_CONTAINER"
echo "🔧 Modelo IA:   $OLLAMA_MODEL"
echo "════════════════════════════════════════════"

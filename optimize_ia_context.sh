#!/usr/bin/env bash
# ============================================================
#  FASI — Optimización contexto IA (Capas 1, 2 y 3)
# ============================================================
set -uo pipefail

STAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="backups/ia_context_$STAMP"
JAVA_BASE="src/main/java"

mkdir -p "$BACKUP_DIR"

echo "🔍 FASI — Optimización del contexto IA"
echo "═══════════════════════════════════════════════════"

# ── 1. Localizar archivos clave ──────────────────────────
echo ""
echo "📂 Localizando ficheros relevantes..."

EVENTO_IA=$(find $JAVA_BASE -name "EventoIaService.java" 2>/dev/null | head -1)
IA_CTX=$(find $JAVA_BASE -name "IaContextBuilder.java" 2>/dev/null | head -1)
OLLAMA=$(find $JAVA_BASE -name "OllamaClient.java" 2>/dev/null | head -1)
IA_SVC=$(find $JAVA_BASE -name "IaService.java" 2>/dev/null | head -1)

for f in EVENTO_IA IA_CTX OLLAMA IA_SVC; do
  v="${!f}"
  if [[ -n "$v" && -f "$v" ]]; then
    echo "  ✅ $f → $v"
  else
    echo "  ❌ $f → NO encontrado"
  fi
done

# ── 2. Mostrar el prompt actual de extracción ────────────
echo ""
echo "─────────────────────────────────────────────"
echo "📋 Prompt ACTUAL en EventoIaService.java:"
echo "─────────────────────────────────────────────"
if [[ -f "$EVENTO_IA" ]]; then
  # Mostramos las primeras 80 líneas alrededor de "prompt"
  grep -n -A 40 -i "prompt\|sistema\|system" "$EVENTO_IA" | head -80
fi

echo ""
echo "─────────────────────────────────────────────"
echo "📋 IaContextBuilder ACTUAL:"
echo "─────────────────────────────────────────────"
if [[ -f "$IA_CTX" ]]; then
  cat "$IA_CTX"
fi

echo ""
echo "─────────────────────────────────────────────"
echo "📋 Parámetros que pasas a Ollama (OllamaClient):"
echo "─────────────────────────────────────────────"
if [[ -f "$OLLAMA" ]]; then
  grep -n -B 2 -A 30 -i "request\|body\|options\|temperature\|num_predict" "$OLLAMA" | head -80
fi

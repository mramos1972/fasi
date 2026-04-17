#!/bin/bash
# ============================================================
# FASI — Generador de contexto para IA
# Uso: ./get-context.sh
# Genera: CONTEXT_IA_YYYYMMDD_HHMM.txt + git commit automático
# ============================================================

TIMESTAMP=$(date '+%Y%m%d_%H%M')
OUTPUT="CONTEXT_IA_${TIMESTAMP}.txt"

echo "======================================================"
echo "  FASI — CONTEXTO COMPLETO PARA IA"
echo "  Generado: $(date '+%Y-%m-%d %H:%M')"
echo "======================================================"

generate_context() {
  echo "======================================================"
  echo "  FASI — CONTEXTO COMPLETO PARA IA"
  echo "  Generado: $(date '+%Y-%m-%d %H:%M')"
  echo "======================================================"

  echo ""
  echo "══ ESTRUCTURA DEL PROYECTO ══"
  tree -L 5 --dirsfirst 2>/dev/null || find . -not -path '*/target/*' -not -path '*/.git/*' | sort

  echo ""
  echo "══ MIGRACIONES FLYWAY (estado BD) ══"
  ls -1 src/main/resources/db/migration/*.sql 2>/dev/null
  echo "--- Última migración:"
  ls -1 src/main/resources/db/migration/*.sql | tail -1

  echo ""
  echo "══ FICHEROS DE CONFIGURACIÓN ══"
  for f in \
    pom.xml \
    Dockerfile \
    Jenkinsfile \
    src/main/resources/application.properties \
    src/main/resources/application-dev.properties \
    src/main/resources/application-prod.properties
  do
    echo ""
    echo "--- $f ---"
    cat "$f" 2>/dev/null || echo "[NO EXISTE]"
  done

  echo ""
  echo "══ JAVA — TODOS LOS FICHEROS FUENTE ══"
  find src/main/java -name "*.java" | sort | while read f; do
    echo ""
    echo "--- $f ---"
    cat "$f"
  done

  echo ""
  echo "══ TEMPLATES THYMELEAF ══"
  find src/main/resources/templates -name "*.html" | sort | while read f; do
    echo ""
    echo "--- $f ---"
    cat "$f"
  done

  echo ""
  echo "══ MIGRACIONES SQL — CONTENIDO COMPLETO ══"
  find src/main/resources/db/migration -name "*.sql" | sort | while read f; do
    echo ""
    echo "--- $f ---"
    cat "$f"
  done

  echo ""
  echo "══ FIN DEL CONTEXTO ══"
}

# ── Generar fichero ───────────────────────────────────────────
echo "📄 Generando $OUTPUT ..."
generate_context > "$OUTPUT"

if [ $? -ne 0 ]; then
  echo "❌ Error generando el contexto. Abortando."
  exit 1
fi

echo "✅ Fichero generado: $OUTPUT"

# ── Git commit automático ─────────────────────────────────────
if git rev-parse --git-dir > /dev/null 2>&1; then
  echo "📦 Haciendo git add y commit..."
  git add .
  git commit -m "context: snapshot $TIMESTAMP"
  if [ $? -eq 0 ]; then
    echo "✅ Commit realizado: context: snapshot $TIMESTAMP"
  else
    echo "⚠️  git commit falló (puede que no haya cambios que commitear)"
  fi
else
  echo "⚠️  No se detectó repositorio git. Saltando commit."
fi

echo ""
echo "🎯 Listo. Adjunta $OUTPUT a la conversación con la IA."

#!/bin/bash
# ============================================================
# FASI — Generador de contexto para IA
# Uso: ./get-context.sh > CONTEXT_IA.txt
# ============================================================

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

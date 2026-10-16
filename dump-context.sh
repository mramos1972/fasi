#!/usr/bin/env bash
set -e

OUTPUT_FILE="fasi_context.txt"
echo "Generando volcado de contexto en $OUTPUT_FILE..."

# Limpiar salida previa si existe
> "$OUTPUT_FILE"

# 1. Cabecera inicial con estructura de carpetas relevante
echo "================================================================================" >> "$OUTPUT_FILE"
echo "PROYECTO: FASI - CONTEXTO TÉCNICO" >> "$OUTPUT_FILE"
echo "FECHA: $(date)" >> "$OUTPUT_FILE"
echo "================================================================================" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

echo "### ESTRUCTURA DE CÓDIGO FUENTE (src/):" >> "$OUTPUT_FILE"
tree src/ -I "*.png|*.svg|*.bak*|target|classes" >> "$OUTPUT_FILE" 2>/dev/null || true
echo "" >> "$OUTPUT_FILE"

# 2. Configuración y compilación raíz
for file in pom.xml Dockerfile; do
  if [ -f "$file" ]; then
    echo "================================================================================" >> "$OUTPUT_FILE"
    echo "FICHERO: $file" >> "$OUTPUT_FILE"
    echo "================================================================================" >> "$OUTPUT_FILE"
    cat "$file" >> "$OUTPUT_FILE"
    echo -e "\n\n" >> "$OUTPUT_FILE"
  fi
done

# 3. Código Java, SQL de migraciones, Properties y Plantillas HTML
find src/main/ -type f \( \
    -name "*.java" -o \
    -name "*.properties" -o \
    -name "*.yml" -o \
    -name "*.yaml" -o \
    -name "*.sql" -o \
    -name "*.html" -o \
    -name "manifest.json" -o \
    -name "sw.js" \
  \) \
  ! -name "*.bak*" \
  ! -name "*.backup" \
  ! -path "*/target/*" \
  ! -path "*/backups/*" \
  | sort | while read -r filepath; do
    echo "================================================================================" >> "$OUTPUT_FILE"
    echo "FICHERO: $filepath" >> "$OUTPUT_FILE"
    echo "================================================================================" >> "$OUTPUT_FILE"
    cat "$filepath" >> "$OUTPUT_FILE"
    echo -e "\n\n" >> "$OUTPUT_FILE"
done

echo "Volcado completado con éxito en: $OUTPUT_FILE"
echo "Tamaño del archivo resultante: $(du -h "$OUTPUT_FILE" | cut -f1)"

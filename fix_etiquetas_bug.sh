#!/bin/bash
# ════════════════════════════════════════════════════════════
#  Fix etiquetas bug — EventoService no copiaba las etiquetas
# ════════════════════════════════════════════════════════════

FILE="src/main/java/com/miempresa/fasi/calendario/EventoService.java"

# Backup
cp "$FILE" "${FILE}.bak.$(date +%Y%m%d_%H%M%S)"

# Buscar el método update() y añadir la línea que falta
sed -i '/e\.setDatosExtra(datos\.getDatosExtra());/a\        e.setEtiquetas(datos.getEtiquetas());' "$FILE"

echo "✅ Bug arreglado en EventoService.java"
echo "📝 Línea añadida: e.setEtiquetas(datos.getEtiquetas());"

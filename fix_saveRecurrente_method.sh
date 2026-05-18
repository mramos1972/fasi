#!/bin/bash
# ════════════════════════════════════════════════════════════
#  Fix método saveRecurrente - Corregir getTodoDia() → isTodoDia()
# ════════════════════════════════════════════════════════════

echo "🔧 Corrigiendo método saveRecurrente()..."

# Backup
cp src/main/java/com/miempresa/fasi/calendario/EventoService.java \
   src/main/java/com/miempresa/fasi/calendario/EventoService.java.bak

# Reemplazar getTodoDia() por isTodoDia()
sed -i 's/plantilla\.getTodoDia()/plantilla.isTodoDia()/g' \
  src/main/java/com/miempresa/fasi/calendario/EventoService.java

echo "✅ Corregido: getTodoDia() → isTodoDia()"
echo ""
echo "Verificando cambio..."
grep -n "isTodoDia" src/main/java/com/miempresa/fasi/calendario/EventoService.java || echo "No encontrado"

#!/usr/bin/env bash
# ============================================================
#  FASI — Dump quirúrgico de ficheros IA para parche
# ============================================================
OUT="ficheros_ia_$(date +%Y%m%d_%H%M%S).txt"

{
  echo "═══════════════════════════════════════════════════"
  echo "  DUMP FICHEROS IA - $(date)"
  echo "═══════════════════════════════════════════════════"
  
  for f in \
    "src/main/java/com/miempresa/fasi/ia/OllamaClient.java" \
    "src/main/java/com/miempresa/fasi/ia/OllamaRequest.java" \
    "src/main/java/com/miempresa/fasi/ia/OllamaResponse.java" \
    "src/main/java/com/miempresa/fasi/ia/IaContextBuilder.java" \
    "src/main/java/com/miempresa/fasi/ia/IaService.java" \
    "src/main/java/com/miempresa/fasi/calendario/EventoIaService.java"
  do
    if [[ -f "$f" ]]; then
      echo ""
      echo "────────────────────────────────────────────────"
      echo "📄 $f"
      echo "────────────────────────────────────────────────"
      cat "$f"
    else
      echo ""
      echo "❌ NO ENCONTRADO: $f"
    fi
  done
} > "$OUT"

echo "✅ Generado: $OUT"
echo "   Tamaño: $(du -h $OUT | cut -f1)"
echo "   Líneas: $(wc -l < $OUT)"

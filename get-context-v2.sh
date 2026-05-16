#!/usr/bin/env bash
# ============================================================
# FASI — Generador de contexto MODULAR para IA  (v2.1)
# Genera ficheros temáticos pequeños evitando el resumen Monica.
# Uso:
#   ./get-context-v2.sh              → genera todos
#   ./get-context-v2.sh resumen      → solo resumen
#   ./get-context-v2.sh dominio      → resumen + dominio
#   ./get-context-v2.sh ia           → resumen + módulo IA
#   ./get-context-v2.sh templates    → resumen + templates (split en 3)
#   ./get-context-v2.sh sql          → resumen + SQL
# ============================================================
set -euo pipefail

STAMP=$(date '+%Y%m%d_%H%M')
OUTDIR="context_${STAMP}"
mkdir -p "$OUTDIR"

MODULO="${1:-all}"

# ── Helpers ────────────────────────────────────────────────
header() {
  local title="$1" file="$2"
  cat > "$file" <<EOF
══════════════════════════════════════════════════════════════
  FASI — CONTEXTO IA · $title
  Generado: $(date '+%Y-%m-%d %H:%M')
  Stack: Spring Boot 3.2.5, Java 17, PostgreSQL, Thymeleaf, Tailwind, HTMX, Ollama
══════════════════════════════════════════════════════════════

EOF
}

dump_file() {
  local f="$1" out="$2"
  if [[ -f "$f" ]]; then
    {
      echo ""
      echo "────────────────────────────────────────────────"
      echo "📄 $f ($(wc -l <"$f") líneas)"
      echo "────────────────────────────────────────────────"
      cat "$f"
    } >> "$out"
  fi
}

# ════════════════════════════════════════════════════════════
#  001 · RESUMEN
# ════════════════════════════════════════════════════════════
gen_resumen() {
  local F="$OUTDIR/001_resumen.txt"
  header "RESUMEN GENERAL" "$F"

  echo "══ ESTRUCTURA (3 niveles) ══"             >> "$F"
  tree -L 3 --dirsfirst -I 'target|backups|node_modules|.git' \
        src 2>/dev/null                            >> "$F"

  echo ""                                          >> "$F"
  echo "══ MIGRACIONES FLYWAY ══"                  >> "$F"
  ls -1 src/main/resources/db/migration/*.sql      >> "$F" 2>/dev/null

  echo ""                                          >> "$F"
  echo "══ ENUMS Y CONSTANTES CLAVE ══"           >> "$F"
  grep -rn "public enum" src/main/java --include="*.java" \
       2>/dev/null                                 >> "$F" || true

  for cfg in pom.xml Dockerfile \
             src/main/resources/application.properties \
             src/main/resources/application-dev.properties \
             src/main/resources/application-prod.properties; do
    dump_file "$cfg" "$F"
  done

  echo "✅ $F"
}

# ════════════════════════════════════════════════════════════
#  002 · DOMINIO
# ════════════════════════════════════════════════════════════
gen_dominio() {
  local F="$OUTDIR/002_dominio.txt"
  header "DOMINIO (entidades, repos, services)" "$F"

  find src/main/java -type f \( \
       -name "*Repository.java" -o \
       -name "*Service.java" -o \
       -name "Evento.java" -o \
       -name "ContextoFamiliar.java" -o \
       -name "IaMensaje.java" -o \
       -name "BaseEntity.java" -o \
       -name "Tarea.java" -o \
       -name "Movimiento.java" -o \
       -name "Documento.java" -o \
       -name "Contacto.java" \
    \) | sort | while read -r f; do
      dump_file "$f" "$F"
  done

  echo "✅ $F"
}

# ════════════════════════════════════════════════════════════
#  003 · CONTROLLERS Y DTOs
# ════════════════════════════════════════════════════════════
gen_controllers() {
  local F="$OUTDIR/003_controllers.txt"
  header "CONTROLLERS Y DTOs" "$F"

  find src/main/java -type f \( \
       -name "*Controller.java" -o \
       -name "*Request.java" -o \
       -name "*Response.java" -o \
       -name "ApiResponse.java" -o \
       -name "GlobalExceptionHandler.java" -o \
       -name "SecurityConfig.java" \
    \) | sort | while read -r f; do
      dump_file "$f" "$F"
  done

  echo "✅ $F"
}

# ════════════════════════════════════════════════════════════
#  004 · MÓDULO IA
# ════════════════════════════════════════════════════════════
gen_ia() {
  local F="$OUTDIR/004_ia.txt"
  header "MÓDULO IA (Ollama, prompts, contexto)" "$F"

  if [[ -d src/main/java/com/miempresa/fasi/ia ]]; then
    find src/main/java/com/miempresa/fasi/ia -type f -name "*.java" \
         | sort | while read -r f; do dump_file "$f" "$F"; done
  fi

  for f in \
    src/main/java/com/miempresa/fasi/calendario/EventoIaService.java \
    src/main/java/com/miempresa/fasi/calendario/EventoIaRequest.java \
    src/main/java/com/miempresa/fasi/calendario/EventoIaResponse.java; do
      dump_file "$f" "$F"
  done

  echo "✅ $F"
}

# ════════════════════════════════════════════════════════════
#  005a · TEMPLATES — Calendario (lo crítico para esta tarea)
# ════════════════════════════════════════════════════════════
gen_templates_calendario() {
  local F="$OUTDIR/005a_templates_calendario.txt"
  header "TEMPLATES — Calendario" "$F"

  if [[ -d src/main/resources/templates/calendario ]]; then
    for f in src/main/resources/templates/calendario/*.html; do
      dump_file "$f" "$F"
    done
  fi

  echo "✅ $F"
}

# ════════════════════════════════════════════════════════════
#  005b · TEMPLATES — Resto (layout, login, ia, contexto…)
# ════════════════════════════════════════════════════════════
gen_templates_resto() {
  local F="$OUTDIR/005b_templates_resto.txt"
  header "TEMPLATES — Layout, IA, login, resto" "$F"

  find src/main/resources/templates \
       -type f -name "*.html" \
       -not -path "*/calendario/*" \
       2>/dev/null | sort | while read -r f; do
    dump_file "$f" "$F"
  done

  echo "✅ $F"
}

# ════════════════════════════════════════════════════════════
#  005c · STATIC — JS / Service Worker / Manifest
# ════════════════════════════════════════════════════════════
gen_static() {
  local F="$OUTDIR/005c_static.txt"
  header "STATIC — JS / Service Worker / Manifest" "$F"

  if [[ -d src/main/resources/static ]]; then
    find src/main/resources/static -type f \
         \( -name "*.js" -o -name "*.json" -o -name "*.css" \) \
         2>/dev/null | sort | while read -r f; do
      dump_file "$f" "$F"
    done
  fi

  echo "✅ $F"
}

# ════════════════════════════════════════════════════════════
#  006 · MIGRACIONES SQL
# ════════════════════════════════════════════════════════════
gen_sql() {
  local F="$OUTDIR/006_sql.txt"
  header "MIGRACIONES FLYWAY" "$F"

  for sql in src/main/resources/db/migration/*.sql; do
    dump_file "$sql" "$F"
  done

  echo "✅ $F"
}

# ── Dispatch ────────────────────────────────────────────────
case "$MODULO" in
  resumen|001)
    gen_resumen ;;
  dominio|002)
    gen_resumen; gen_dominio ;;
  controllers|003)
    gen_resumen; gen_controllers ;;
  ia|004)
    gen_resumen; gen_ia ;;
  templates|005)
    gen_resumen
    gen_templates_calendario
    gen_templates_resto
    gen_static
    ;;
  sql|006)
    gen_resumen; gen_sql ;;
  all)
    gen_resumen
    gen_dominio
    gen_controllers
    gen_ia
    gen_templates_calendario
    gen_templates_resto
    gen_static
    gen_sql
    ;;
  *)
    echo "❌ Módulo desconocido: $MODULO"
    echo "   Opciones: resumen | dominio | controllers | ia | templates | sql | all"
    exit 1
    ;;
esac

echo ""
echo "═══════════════════════════════════════════════════"
echo "📂 Ficheros generados en: $OUTDIR/"
ls -lh "$OUTDIR/" | tail -n +2
echo ""
echo "💡 Tamaño máximo recomendable por fichero: ~70 KB"
echo "   (Monica resume automáticamente >80 KB)"
echo ""
echo "🎯 Para 'migración tipos': sube 001, 002, 004 y 005a"

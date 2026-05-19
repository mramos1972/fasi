#!/bin/bash
# ══════════════════════════════════════════════════════════
#  FASI — Fix: Vista previa de fechas en eventos recurrentes
#  Fecha: 2026-05-19 09:16
# ══════════════════════════════════════════════════════════

set -e

PROYECTO_ROOT=~/cicd/proyectos/fasi
CONTROLLER="$PROYECTO_ROOT/src/main/java/com/miempresa/fasi/calendario/CalendarioController.java"
INDEX_HTML="$PROYECTO_ROOT/src/main/resources/templates/calendario/index.html"
BACKUP_DIR="$PROYECTO_ROOT/backups/preview_fechas_$(date +%Y%m%d_%H%M%S)"

echo "🔧 Aplicando fix: Vista previa de fechas"
echo "════════════════════════════════════════════════════════"

# ── Crear backup ─────────────────────────────────────────
echo "📦 [1/4] Creando backup..."
mkdir -p "$BACKUP_DIR"
cp "$CONTROLLER" "$BACKUP_DIR/"
cp "$INDEX_HTML" "$BACKUP_DIR/"
echo "✅ Backup en: $BACKUP_DIR"

# ── Parche 1: CalendarioController.java ──────────────────
echo "🔨 [2/4] Parcheando CalendarioController.java..."

cat > /tmp/generarDescripcionPreview.java << 'EOF'
    private String generarDescripcionPreview(List<LocalDate> fechas) {
        if (fechas.isEmpty()) return "No se generarán eventos";
        
        StringBuilder sb = new StringBuilder();
        sb.append("Se crearán ").append(fechas.size()).append(" evento");
        if (fechas.size() > 1) sb.append("s");
        sb.append(":\n\n");
        
        int limite = Math.min(10, fechas.size());
        for (int i = 0; i < limite; i++) {
            LocalDate fecha = fechas.get(i);
            String diaSemana = switch (fecha.getDayOfWeek()) {
                case MONDAY -> "Lun";
                case TUESDAY -> "Mar";
                case WEDNESDAY -> "Mié";
                case THURSDAY -> "Jue";
                case FRIDAY -> "Vie";
                case SATURDAY -> "Sáb";
                case SUNDAY -> "Dom";
            };
            
            // Formato: "Mié 04/06/2026"
            sb.append(String.format("%-3s %02d/%02d/%d\n", 
                diaSemana, 
                fecha.getDayOfMonth(), 
                fecha.getMonthValue(), 
                fecha.getYear()
            ));
        }
        
        if (fechas.size() > 10) {
            sb.append("\n... y ").append(fechas.size() - 10).append(" más");
        }
        
        return sb.toString();
    }
}
EOF

# Reemplazar el método (desde "private String generarDescripcionPreview" hasta el siguiente "}")
sed -i '/private String generarDescripcionPreview/,/^    }$/c\
    private String generarDescripcionPreview(List<LocalDate> fechas) {\
        if (fechas.isEmpty()) return "No se generarán eventos";\
        \
        StringBuilder sb = new StringBuilder();\
        sb.append("Se crearán ").append(fechas.size()).append(" evento");\
        if (fechas.size() > 1) sb.append("s");\
        sb.append(":\\n\\n");\
        \
        int limite = Math.min(10, fechas.size());\
        for (int i = 0; i < limite; i++) {\
            LocalDate fecha = fechas.get(i);\
            String diaSemana = switch (fecha.getDayOfWeek()) {\
                case MONDAY -> "Lun";\
                case TUESDAY -> "Mar";\
                case WEDNESDAY -> "Mié";\
                case THURSDAY -> "Jue";\
                case FRIDAY -> "Vie";\
                case SATURDAY -> "Sáb";\
                case SUNDAY -> "Dom";\
            };\
            \
            sb.append(String.format("%-3s %02d/%02d/%d\\n", \
                diaSemana, \
                fecha.getDayOfMonth(), \
                fecha.getMonthValue(), \
                fecha.getYear()\
            ));\
        }\
        \
        if (fechas.size() > 10) {\
            sb.append("\\n... y ").append(fechas.size() - 10).append(" más");\
        }\
        \
        return sb.toString();\
    }\
}' "$CONTROLLER"

echo "✅ CalendarioController.java parcheado"

# ── Parche 2: index.html (función mostrarPreview) ────────
echo "🔨 [3/4] Parcheando index.html..."

# Crear archivo temporal con la nueva función
cat > /tmp/mostrarPreview.js << 'JSEOF'
    function mostrarPreview(evento, dataExtra) {
      var c = document.getElementById('preview-contenido');
      var tipoLabel = (evento.tipo && evento.tipo.label) ? evento.tipo.label : (evento.tipo || '');
      var tipoEmoji = (evento.tipo && evento.tipo.emoji) ? evento.tipo.emoji : '';
      var esRec = evento.datosExtra && evento.datosExtra.recurrente === true;

      var html = '<div class="space-y-2">';
      html += '<p><strong>Tipo:</strong> ' + tipoEmoji + ' ' + tipoLabel + '</p>';
      html += '<p><strong>Título:</strong> ' + (evento.titulo || '-') + '</p>';

      if (evento.horaInicio) {
        html += '<p><strong>Hora:</strong> ' + evento.horaInicio.substring(0,5);
        if (evento.horaFin) html += ' – ' + evento.horaFin.substring(0,5);
        html += '</p>';
      }

      if (evento.lugar) html += '<p><strong>Lugar:</strong> ' + evento.lugar + '</p>';
      if (evento.personasRelacionadas) html += '<p><strong>Personas:</strong> ' + evento.personasRelacionadas + '</p>';
      if (evento.descripcion) html += '<p><strong>Notas:</strong> ' + evento.descripcion + '</p>';

      if (esRec && dataExtra.descripcionPreview) {
        html += '<div class="mt-3 p-3 bg-indigo-50 dark:bg-indigo-900/30 rounded-lg border border-indigo-200 dark:border-indigo-700">';
        html += '<p class="text-xs font-semibold text-indigo-700 dark:text-indigo-300 mb-2">🔄 Eventos recurrentes:</p>';
        html += '<pre class="text-xs text-gray-700 dark:text-gray-200 whitespace-pre-wrap font-mono leading-relaxed">' + escapeHtml(dataExtra.descripcionPreview) + '</pre>';
        html += '</div>';
      } else {
        var fechaStr = evento.fechaInicio || '-';
        if (evento.fechaFin && evento.fechaFin !== evento.fechaInicio) {
          var d1 = new Date(evento.fechaInicio + 'T00:00:00');
          var d2 = new Date(evento.fechaFin + 'T00:00:00');
          var dias = Math.round((d2 - d1) / 86400000) + 1;
          fechaStr += ' al ' + evento.fechaFin + ' (' + dias + ' día' + (dias > 1 ? 's' : '') + ')';
        }
        html += '<p><strong>Fecha:</strong> ' + fechaStr + '</p>';
      }

      if (!evento.horaInicio && !evento.todoDia) {
        html += '<div class="mt-2 p-2 bg-yellow-50 dark:bg-yellow-900/30 border border-yellow-300 rounded text-xs text-yellow-700 dark:text-yellow-300">';
        html += '⚠️ No se especificó hora — se guardará como <strong>pendiente de hora</strong>';
        html += '</div>';
      }

      html += '</div>';
      c.innerHTML = html;

      var btnConf = document.getElementById('btn-confirmar');
      if (btnConf) {
        if (esRec && dataExtra.totalEventos) {
          btnConf.textContent = '✅ Confirmar ' + dataExtra.totalEventos + ' evento' + (dataExtra.totalEventos > 1 ? 's' : '');
          btnConf.className = 'flex-1 bg-indigo-600 hover:bg-indigo-700 text-white py-2 rounded-lg font-semibold transition text-sm';
        } else {
          btnConf.textContent = '✅ Confirmar';
          btnConf.className = 'flex-1 bg-green-600 hover:bg-green-700 text-white py-2 rounded-lg font-semibold transition text-sm';
        }
      }

      document.getElementById('preview-evento').style.display = 'block';
    }
JSEOF

# Reemplazar función mostrarPreview en index.html
# (Buscar desde "function mostrarPreview" hasta el cierre de la función)
python3 << 'PYEOF'
import re

with open('/home/mramos/cicd/proyectos/fasi/src/main/resources/templates/calendario/index.html', 'r') as f:

    content = f.read()

	# Leer nueva función
with open('/tmp/mostrarPreview.js', 'r') as f:
    nueva_funcion = f.read()

# Reemplazar función (buscar desde "function mostrarPreview" hasta el siguiente "function" o cierre de script)
pattern = r'(function mostrarPreview\(evento, dataExtra\) \{.*?\n    \})'
replacement = nueva_funcion.strip()

content_new = re.sub(pattern, replacement, content, flags=re.DOTALL)

with open('/home/mramos/cicd/proyectos/fasi/src/main/resources/templates/calendario/index.html', 'w') as f:

    f.write(content_new)

print("✅ index.html parcheado")
PYEOF

echo "✅ index.html parcheado"

# ── Recompilar ───────────────────────────────────────────
echo "🔨 [4/4] Recompilando proyecto..."
cd "$PROYECTO_ROOT"
./mvnw package -DskipTests -q

echo ""
echo "════════════════════════════════════════════════════════"
echo "✅ Fix aplicado correctamente"
echo ""
echo "📋 Cambios realizados:"
echo "  1. CalendarioController.generarDescripcionPreview() → formato mejorado"
echo "  2. index.html.mostrarPreview() → muestra lista de fechas"
echo ""
echo "🚀 Siguiente paso:"
echo "   ./dev-redeploy.sh"
echo ""
echo "🔙 Para revertir:"
echo "   cp $BACKUP_DIR/* (rutas originales)"
echo "════════════════════════════════════════════════════════"

#!/bin/bash
set -e

HTML_FILE=~/cicd/proyectos/fasi/src/main/resources/templates/calendario/index.html

echo "🔧 Aplicando parche de vista previa..."

# 1. Backup
cp "$HTML_FILE" "${HTML_FILE}.bak_$(date +%Y%m%d_%H%M%S)"
echo "✅ Backup creado"

# 2. Cambiar endpoint de /api/interpretar a /api/interpretar-preview
sed -i "s|'/calendario/api/interpretar'|'/calendario/api/interpretar-preview'|g" "$HTML_FILE"
echo "✅ Endpoint cambiado a /api/interpretar-preview"

# 3. Aplicar parche con Python
cat > /tmp/patch_index.py << 'EOFPYTHON'
#!/usr/bin/env python3
import re

html_file = 'HTML_FILE_PLACEHOLDER'

with open(html_file, 'r', encoding='utf-8') as f:
    content = f.read()

# ══════════════════════════════════════════════════════════
# PASO 1: Modificar interpretarTexto() para usar nueva API
# ══════════════════════════════════════════════════════════

# Reemplazar el bloque completo de interpretarTexto
old_interpretar = r'''async function interpretarTexto\(\) \{
      var texto = document\.getElementById\('voz-texto'\)\.value\.trim\(\);
      if \(!texto\) \{ alert\('Escribe o dicta algo primero\.'\); return; \}
      document\.getElementById\('ia-spinner'\)\.style\.display     = 'flex';
      document\.getElementById\('preview-evento'\)\.style\.display = 'none';
      try \{
        var resp = await fetch\('/calendario/api/interpretar-preview', \{
          method: 'POST',
          headers: \{ 'Content-Type': 'application/json' \},
          body: JSON\.stringify\(\{ texto: texto, fechaContexto: HOY \}\)
        \}\);
        var data = await resp\.json\(\);'''

new_interpretar = '''async function interpretarTexto() {
      var texto = document.getElementById('voz-texto').value.trim();
      if (!texto) { alert('Escribe o dicta algo primero.'); return; }
      document.getElementById('ia-spinner').style.display     = 'flex';
      document.getElementById('preview-evento').style.display = 'none';
      try {
        var resp = await fetch('/calendario/api/interpretar-preview', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ texto: texto })
        });
        var data = await resp.json();
        
        // Nueva API devuelve: { evento, esRecurrente, totalEventos, primerasFechas, descripcionPreview }
        if (data.evento) {
          eventoActual = data.evento;
          eventoActual.esRecurrente = data.esRecurrente;
          eventoActual.descripcionPreview = data.descripcionPreview;
          mostrarPreview(eventoActual, data);
        } else {
          alert('La IA no pudo interpretar el texto.');
        }
      } catch (err) {
        alert('Error: ' + err.message);
      } finally {
        document.getElementById('ia-spinner').style.display = 'none';
      }
    }

    // NUEVA FUNCIÓN: mostrarPreview actualizada
    function mostrarPreview(evento, dataExtra) {'''

# Buscar y reemplazar
content = re.sub(
    r'async function interpretarTexto\(\).*?function mostrarPreview\(evento\)',
    new_interpretar,
    content,
    flags=re.DOTALL
)

# ══════════════════════════════════════════════════════════
# PASO 2: Reemplazar mostrarPreview completa
# ══════════════════════════════════════════════════════════

old_mostrar_preview = r'function mostrarPreview\(evento\) \{.*?document\.getElementById\(\'preview-evento\'\)\.style\.display = \'block\';'

new_mostrar_preview = '''function mostrarPreview(evento, dataExtra) {
      dataExtra = dataExtra || {};
      var avisoHora = (!evento.horaInicio && !evento.todoDia)
        ? '<div class="mt-2 p-2 bg-yellow-50 dark:bg-yellow-900/30 border border-yellow-300 rounded text-xs text-yellow-700 dark:text-yellow-300">⚠️ No se especificó hora — se guardará como <strong>pendiente de hora</strong></div>'
        : '';
      var c         = document.getElementById('preview-contenido');
      var tipoLabel = (evento.tipo && evento.tipo.label) ? evento.tipo.label : (evento.tipo || '');
      var tipoEmoji = (evento.tipo && evento.tipo.emoji) ? evento.tipo.emoji : '';
      var esRec     = evento.datosExtra && evento.datosExtra.recurrente === true;
      var patron    = esRec ? evento.datosExtra.patron : null;

      var fechaStr = evento.fechaInicio || '-';
      if (esRec && evento.fechaFin) {
        var fi = evento.fechaInicio.split('-').reverse().slice(0,2).join('/');
        var ff = evento.fechaFin.split('-').reverse().slice(0,2).join('/');
        fechaStr = 'del ' + fi + ' al ' + ff;
      } else if (evento.fechaFin && evento.fechaFin !== evento.fechaInicio) {
        fechaStr += ' al ' + evento.fechaFin.split('-').reverse().slice(0,2).join('/');
      }
      if (evento.horaInicio) fechaStr += ' a las ' + evento.horaInicio.substring(0,5);

      var html = '<p><strong>Tipo:</strong> ' + tipoEmoji + ' ' + tipoLabel + '</p>'
               + '<p><strong>Titulo:</strong> ' + (evento.titulo || '-') + '</p>'
               + '<p><strong>Fecha:</strong> ' + fechaStr + '</p>';
      if (evento.lugar)                html += '<p><strong>Lugar:</strong> ' + evento.lugar + '</p>';
      if (evento.personasRelacionadas) html += '<p><strong>Personas:</strong> ' + evento.personasRelacionadas + '</p>';
      if (evento.descripcion)          html += '<p><strong>Notas:</strong> ' + evento.descripcion + '</p>';
      
      if (esRec) {
        html += '<p class="mt-2 text-indigo-600 font-semibold text-xs">🔄 Recurrente (' + (patron || 'periodico') + ')</p>';
        
        // NUEVO: Mostrar lista de fechas
        if (dataExtra.descripcionPreview) {
          html += '<div class="mt-3 p-3 bg-blue-50 dark:bg-blue-900/20 rounded-lg text-sm whitespace-pre-line">';
          html += '<strong>📅 Eventos a crear:</strong><br>';
          html += dataExtra.descripcionPreview.replace(/\n/g, '<br>');
          html += '</div>';
        }
      }
      
      c.innerHTML = html;

      var btnConf = document.getElementById('btn-confirmar');
      if (btnConf) {
        btnConf.textContent = esRec ? '✅ Confirmar todos' : '✅ Confirmar';
        btnConf.className = btnConf.className
          .replace('bg-green-600','').replace('hover:bg-green-700','')
          .replace('bg-indigo-600','').replace('hover:bg-indigo-700','');
        btnConf.className += esRec ? ' bg-indigo-600 hover:bg-indigo-700' : ' bg-green-600 hover:bg-green-700';
      }
      document.getElementById('preview-evento').style.display = 'block';'''

content = re.sub(
    old_mostrar_preview,
    new_mostrar_preview,
    content,
    flags=re.DOTALL
)

with open(html_file, 'w', encoding='utf-8') as f:
    f.write(content)

print("✅ Parche aplicado correctamente")
EOFPYTHON

# Reemplazar placeholder con ruta real
sed "s|HTML_FILE_PLACEHOLDER|$HTML_FILE|g" /tmp/patch_index.py > /tmp/patch_index_final.py

# Ejecutar parche
python3 /tmp/patch_index_final.py

echo ""
echo "🎉 Parche completado. Ahora despliega:"
echo "   cd ~/cicd/proyectos/fasi && ./dev-redeploy.sh"

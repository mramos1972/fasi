#!/bin/bash
set -e
TMPL="src/main/resources/templates"
BASE_HTML="$TMPL/layout/base.html"

echo "🔧 Aplicando correcciones al módulo IA..."

# ════════════════════════════════════════════════════════════
# FIX 1 — Limpiar duplicados en el menú
# Regenerar base.html con los enlaces correctos (1 vez cada uno)
# ════════════════════════════════════════════════════════════

# Eliminar todas las líneas duplicadas de Asistente IA que metió el sed
# y dejar solo una entrada limpia en cada menú (desktop + móvil)

# Sidebar DESKTOP — quitar todas las líneas de IA y poner solo una
python3 - << 'PYEOF'
import re

with open("src/main/resources/templates/layout/base.html", "r") as f:
    content = f.read()

# Eliminar TODAS las líneas que contengan /ia del menú
ia_line_pattern = r'\n\s*<a th:href="@\{/ia\}"[^>]*>.*?</a>'
content_clean = re.sub(ia_line_pattern, '', content)

# Insertar UNA línea en sidebar desktop (después del enlace /desarrollo en el aside)
desktop_anchor = '<a th:href="@{/desarrollo}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🛠️ <span>Desarrollo</span></a>'
desktop_replacement = desktop_anchor + '\n        <a th:href="@{/ia}"            class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🤖 <span>Asistente IA</span></a>'

# Solo reemplazar la PRIMERA ocurrencia (sidebar desktop dentro de <aside>)
content_fixed = content_clean.replace(desktop_anchor, desktop_replacement, 1)

# Insertar UNA línea en menú móvil (segunda ocurrencia de /desarrollo)
mobile_anchor = '<a th:href="@{/desarrollo}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🛠️ <span>Desarrollo</span></a>'
parts = content_fixed.split(mobile_anchor)
if len(parts) >= 3:
    mobile_ia = '\n      <a th:href="@{/ia}"            class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🤖 <span>Asistente IA</span></a>'
    content_fixed = parts[0] + mobile_anchor + mobile_ia + mobile_anchor.join(parts[1:]) if len(parts) == 2 else \
                    mobile_anchor.join(parts[:2]) + mobile_ia + mobile_anchor + mobile_anchor.join(parts[2:])

with open("src/main/resources/templates/layout/base.html", "w") as f:
    f.write(content_fixed)

print("✅ Menú corregido — una sola entrada Asistente IA")
PYEOF

# ════════════════════════════════════════════════════════════
# FIX 2 — Reescribir chat.html con HTMX corregido
# El problema: hx-swap="beforeend" con fragmento :: no funciona bien juntos
# Solución: separar el chat en dos partes — lista de mensajes + form
# ════════════════════════════════════════════════════════════

cat > "$TMPL/ia/chat.html" << 'EOF'
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/base :: layout(~{::title}, ~{::section})}">
<head><title>Asistente IA — FASI</title></head>
<body>
<section>

  <div class="flex justify-between items-center mb-4">
    <div>
      <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100">🤖 Asistente IA</h2>
      <p class="text-sm text-gray-500 dark:text-gray-400 mt-1">
        Powered by <span class="font-semibold text-indigo-500">Gemma 3 4B</span>
        · Ollama local · Acceso completo a tus datos
      </p>
    </div>
  </div>

  <!-- ── Selector de módulo ── -->
  <div class="flex flex-wrap gap-2 mb-4">
    <button type="button" onclick="setModulo(this,'general')"
            class="modulo-btn activo px-3 py-1 rounded-full text-xs font-semibold
                   bg-indigo-600 text-white">
      🌐 General
    </button>
    <button type="button" onclick="setModulo(this,'contabilidad')"
            class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold
                   bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
      💰 Contabilidad
    </button>
    <button type="button" onclick="setModulo(this,'tareas')"
            class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold
                   bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
      ✅ Tareas
    </button>
    <button type="button" onclick="setModulo(this,'agenda')"
            class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold
                   bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
      📇 Agenda
    </button>
    <button type="button" onclick="setModulo(this,'documentos')"
            class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold
                   bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
      📄 Documentos
    </button>
  </div>

  <!-- ── Área de mensajes (solo lectura, se recarga por HTMX) ── -->
  <div id="chat-mensajes"
       class="bg-white dark:bg-gray-800 rounded-xl shadow
              min-h-80 max-h-[55vh] overflow-y-auto p-6 mb-4 flex flex-col gap-4">

    <!-- Bienvenida -->
    <div class="flex gap-3 items-start">
      <span class="text-2xl">🤖</span>
      <div class="bg-indigo-50 dark:bg-indigo-900/30 rounded-xl px-4 py-3 max-w-2xl">
        <p class="text-sm text-gray-700 dark:text-gray-200">
          ¡Hola! Soy tu asistente IA integrado en FASI. Tengo acceso a todos tus datos.<br/><br/>
          Puedes preguntarme cosas como:<br/>
          · <em>"¿Cuál es mi saldo actual y en qué gasto más?"</em><br/>
          · <em>"¿Qué tareas tengo pendientes de alta prioridad?"</em><br/>
          · <em>"Dame un informe de mis ingresos del último mes"</em><br/>
          · <em>"¿Tengo contactos sin email registrado?"</em>
        </p>
      </div>
    </div>

    <!-- Historial cargado desde BD -->
    <th:block th:each="msg : ${historial}">
      <div class="flex gap-3 items-start justify-end">
        <div class="bg-gray-100 dark:bg-gray-700 rounded-xl px-4 py-3 max-w-2xl">
          <p class="text-sm text-gray-800 dark:text-gray-100" th:text="${msg.pregunta}"></p>
        </div>
        <span class="text-2xl">👤</span>
      </div>
      <div class="flex gap-3 items-start">
        <span class="text-2xl">🤖</span>
        <div class="bg-indigo-50 dark:bg-indigo-900/30 rounded-xl px-4 py-3 max-w-2xl">
          <p class="text-sm text-gray-700 dark:text-gray-200 whitespace-pre-wrap"
             th:text="${msg.respuesta}"></p>
          <p class="text-xs text-gray-400 mt-2"
             th:text="'⏱ ' + ${msg.tiempoMs} + 'ms · ' + ${msg.modulo}"></p>
        </div>
      </div>
    </th:block>

  </div>

  <!-- ── Indicador de carga (visible mientras HTMX espera) ── -->
  <div id="ia-loading"
       class="hidden mb-3 flex gap-3 items-center px-4 py-3
              bg-indigo-50 dark:bg-indigo-900/30 rounded-xl max-w-xs">
    <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style="animation-delay:0ms"></div>
    <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style="animation-delay:150ms"></div>
    <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style="animation-delay:300ms"></div>
    <span class="text-xs text-gray-400 ml-1">Gemma está pensando...</span>
  </div>

  <!-- ── Formulario ── -->
  <form id="ia-form" class="flex gap-3">
    <input type="hidden" name="modulo" id="modulo-input" value="general"/>
    <textarea name="mensaje"
              id="mensaje-input"
              rows="2"
              placeholder="Escribe tu pregunta... (Enter envía, Shift+Enter = nueva línea)"
              required
              class="flex-1 border border-gray-300 dark:border-gray-600 rounded-xl
                     px-4 py-3 bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                     focus:outline-none focus:ring-2 focus:ring-indigo-500
                     resize-none text-sm"></textarea>
    <button type="submit" id="btn-enviar"
            class="bg-indigo-600 hover:bg-indigo-700 text-white px-6 py-3
                   rounded-xl font-semibold transition self-end">
      Enviar →
    </button>
  </form>

  <!-- ── Sugerencias rápidas ── -->
  <div class="mt-3 flex flex-wrap gap-2">
    <span class="text-xs text-gray-400 self-center">Sugerencias:</span>
    <button type="button"
            onclick="setSugerencia('Dame un resumen financiero completo con recomendaciones')"
            class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100
                   text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
      📊 Resumen financiero
    </button>
    <button type="button"
            onclick="setSugerencia('¿Qué tareas de alta prioridad tengo pendientes?')"
            class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100
                   text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
      🔥 Tareas urgentes
    </button>
    <button type="button"
            onclick="setSugerencia('Analiza mis gastos y dime en qué categorías gasto más')"
            class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100
                   text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
      💸 Análisis de gastos
    </button>
    <button type="button"
            onclick="setSugerencia('Dame consejos para mejorar mi productividad según mis tareas actuales')"
            class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100
                   text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
      💡 Consejos productividad
    </button>
  </div>

</section>

<script>
// ── Selector de módulo ─────────────────────────────────────
function setModulo(btn, modulo) {
  document.getElementById('modulo-input').value = modulo;
  document.querySelectorAll('.modulo-btn').forEach(b => {
    b.classList.remove('bg-indigo-600','text-white');
    b.classList.add('bg-gray-200','dark:bg-gray-700','text-gray-700','dark:text-gray-200');
  });
  btn.classList.add('bg-indigo-600','text-white');
  btn.classList.remove('bg-gray-200','dark:bg-gray-700','text-gray-700','dark:text-gray-200');
}

// ── Sugerencias ────────────────────────────────────────────
function setSugerencia(texto) {
  document.getElementById('mensaje-input').value = texto;
  document.getElementById('mensaje-input').focus();
}

// ── Enter para enviar ──────────────────────────────────────
document.getElementById('mensaje-input').addEventListener('keydown', function(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    enviarMensaje();
  }
});

document.getElementById('ia-form').addEventListener('submit', function(e) {
  e.preventDefault();
  enviarMensaje();
});

// ── Lógica principal de envío (fetch puro, sin HTMX) ──────
async function enviarMensaje() {
  const mensajeInput = document.getElementById('mensaje-input');
  const moduloInput  = document.getElementById('modulo-input');
  const chatArea     = document.getElementById('chat-mensajes');
  const loading      = document.getElementById('ia-loading');
  const btnEnviar    = document.getElementById('btn-enviar');

  const mensaje = mensajeInput.value.trim();
  if (!mensaje) return;

  // Mostrar pregunta del usuario inmediatamente
  chatArea.insertAdjacentHTML('beforeend', `
    <div class="flex gap-3 items-start justify-end">
      <div class="bg-gray-100 dark:bg-gray-700 rounded-xl px-4 py-3 max-w-2xl">
        <p class="text-sm text-gray-800 dark:text-gray-100">${escapeHtml(mensaje)}</p>
      </div>
      <span class="text-2xl">👤</span>
    </div>
  `);

  // Limpiar input y bloquear botón
  mensajeInput.value = '';
  btnEnviar.disabled = true;
  btnEnviar.textContent = '⏳';
  loading.classList.remove('hidden');
  chatArea.scrollTop = chatArea.scrollHeight;

  try {
    const formData = new FormData();
    formData.append('mensaje', mensaje);
    formData.append('modulo',  moduloInput.value);

    const resp = await fetch('/ia/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json',
                 'Accept':       'application/json' },
      body: JSON.stringify({
        mensaje: mensaje,
        modulo:  moduloInput.value
      })
    });

    if (!resp.ok) throw new Error('HTTP ' + resp.status);

    const data = await resp.json();

    // Insertar respuesta IA
    chatArea.insertAdjacentHTML('beforeend', `
      <div class="flex gap-3 items-start">
        <span class="text-2xl">🤖</span>
        <div class="bg-indigo-50 dark:bg-indigo-900/30 rounded-xl px-4 py-3 max-w-2xl">
          <p class="text-sm text-gray-700 dark:text-gray-200 whitespace-pre-wrap">${escapeHtml(data.respuesta)}</p>
          <p class="text-xs text-gray-400 mt-2">⏱ ${data.tiempoMs}ms · ${data.modulo || 'general'}</p>
        </div>
      </div>
    `);

  } catch (err) {
    chatArea.insertAdjacentHTML('beforeend', `
      <div class="flex gap-3 items-start">
        <span class="text-2xl">🤖</span>
        <div class="bg-red-50 dark:bg-red-900/30 rounded-xl px-4 py-3 max-w-2xl">
          <p class="text-sm text-red-600 dark:text-red-300">
            ⚠️ Error al conectar con el asistente: ${err.message}<br/>
            Verifica que Ollama está corriendo: <code>ollama serve</code>
          </p>
        </div>
      </div>
    `);
  } finally {
    loading.classList.add('hidden');
    btnEnviar.disabled = false;
    btnEnviar.textContent = 'Enviar →';
    chatArea.scrollTop = chatArea.scrollHeight;
    mensajeInput.focus();
  }
}

// ── Escape HTML para evitar XSS ───────────────────────────
function escapeHtml(text) {
  const div = document.createElement('div');
  div.appendChild(document.createTextNode(text));
  return div.innerHTML;
}

// ── Scroll inicial al fondo ────────────────────────────────
window.addEventListener('load', () => {
  const c = document.getElementById('chat-mensajes');
  c.scrollTop = c.scrollHeight;
});
</script>

</body>
</html>
EOF

echo ""
echo "════════════════════════════════════════════"
echo "✅ Correcciones aplicadas"
echo "════════════════════════════════════════════"
echo "  Fix 1: Menú con entrada duplicada → corregido"
echo "  Fix 2: HTMX reemplazado por fetch() nativo"
echo "         (más robusto para respuestas lentas de IA)"
echo ""
echo "🚀 Reinicia la app:"
echo "   ./mvnw spring-boot:run"
echo "════════════════════════════════════════════"

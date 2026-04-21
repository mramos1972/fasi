#!/bin/bash
set -e
TMPL="src/main/resources/templates/ia"

echo "🔧 Corrigiendo formulario IA..."

cat > "$TMPL/chat.html" << 'EOF'
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

  <!-- ── Área de mensajes ── -->
  <div id="chat-mensajes"
       class="bg-white dark:bg-gray-800 rounded-xl shadow
              min-h-80 max-h-[55vh] overflow-y-auto p-6 mb-4 flex flex-col gap-4">

    <!-- Bienvenida -->
    <div class="flex gap-3 items-start">
      <span class="text-2xl">🤖</span>
      <div class="bg-indigo-50 dark:bg-indigo-900/30 rounded-xl px-4 py-3 max-w-2xl">
        <p class="text-sm text-gray-700 dark:text-gray-200">
          ¡Hola! Soy tu asistente IA integrado en FASI.<br/><br/>
          Puedes preguntarme cosas como:<br/>
          · <em>"¿Cuál es mi saldo actual y en qué gasto más?"</em><br/>
          · <em>"¿Qué tareas tengo pendientes de alta prioridad?"</em><br/>
          · <em>"Dame un informe de mis ingresos del último mes"</em><br/>
          · <em>"¿Tengo contactos sin email registrado?"</em>
        </p>
      </div>
    </div>

    <!-- Historial desde BD -->
    <th:block th:each="msg : ${historial}">
      <div class="flex gap-3 items-start justify-end">
        <div class="bg-gray-100 dark:bg-gray-700 rounded-xl px-4 py-3 max-w-2xl">
          <p class="text-sm text-gray-800 dark:text-gray-100"
             th:text="${msg.pregunta}"></p>
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

  <!-- ── Spinner de carga ── -->
  <div id="ia-loading"
       style="display:none;"
       class="mb-3 flex gap-3 items-center px-4 py-3
              bg-indigo-50 dark:bg-indigo-900/30 rounded-xl w-fit">
    <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce"
         style="animation-delay:0ms"></div>
    <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce"
         style="animation-delay:150ms"></div>
    <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce"
         style="animation-delay:300ms"></div>
    <span class="text-xs text-gray-400 ml-1">Gemma está pensando...</span>
  </div>

  <!-- ══ FORMULARIO — SIN action NI method para evitar submit nativo ══ -->
  <div class="flex gap-3">
    <input type="hidden" id="modulo-input" value="general"/>
    <textarea id="mensaje-input"
              rows="2"
              placeholder="Escribe tu pregunta... (Enter envía · Shift+Enter = nueva línea)"
              class="flex-1 border border-gray-300 dark:border-gray-600 rounded-xl
                     px-4 py-3 bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                     focus:outline-none focus:ring-2 focus:ring-indigo-500
                     resize-none text-sm"></textarea>
    <button type="button"
            id="btn-enviar"
            onclick="enviarMensaje()"
            class="bg-indigo-600 hover:bg-indigo-700 text-white px-6 py-3
                   rounded-xl font-semibold transition self-end">
      Enviar →
    </button>
  </div>

  <!-- ── Sugerencias ── -->
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
// ── Módulo activo ──────────────────────────────────────────
function setModulo(btn, modulo) {
  document.getElementById('modulo-input').value = modulo;
  document.querySelectorAll('.modulo-btn').forEach(b => {
    b.classList.remove('bg-indigo-600','text-white');
    b.classList.add('bg-gray-200','text-gray-700');
  });
  btn.classList.add('bg-indigo-600','text-white');
  btn.classList.remove('bg-gray-200','text-gray-700');
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

// ── Envío principal con fetch ──────────────────────────────
async function enviarMensaje() {
  const mensajeInput = document.getElementById('mensaje-input');
  const moduloInput  = document.getElementById('modulo-input');
  const chatArea     = document.getElementById('chat-mensajes');
  const loading      = document.getElementById('ia-loading');
  const btnEnviar    = document.getElementById('btn-enviar');

  const mensaje = mensajeInput.value.trim();
  if (!mensaje) return;

  // 1. Mostrar pregunta del usuario al instante
  chatArea.insertAdjacentHTML('beforeend', `
    <div class="flex gap-3 items-start justify-end">
      <div class="bg-gray-100 dark:bg-gray-700 rounded-xl px-4 py-3 max-w-2xl">
        <p class="text-sm text-gray-800 dark:text-gray-100">${escapeHtml(mensaje)}</p>
      </div>
      <span class="text-2xl">👤</span>
    </div>
  `);

  // 2. Limpiar input y mostrar spinner
  mensajeInput.value    = '';
  btnEnviar.disabled    = true;
  btnEnviar.textContent = '⏳';
  loading.style.display = 'flex';
  chatArea.scrollTop    = chatArea.scrollHeight;

  try {
    // 3. Llamada JSON al endpoint REST
    const resp = await fetch('/ia/api/chat', {
      method:  'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept':       'application/json'
      },
      body: JSON.stringify({
        mensaje: mensaje,
        modulo:  moduloInput.value || 'general'
      })
    });

    if (!resp.ok) {
      const txt = await resp.text();
      throw new Error('HTTP ' + resp.status + ' — ' + txt);
    }

    const data = await resp.json();

    // 4. Mostrar respuesta de Gemma
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
    // 5. Mostrar error detallado
    chatArea.insertAdjacentHTML('beforeend', `
      <div class="flex gap-3 items-start">
        <span class="text-2xl">🤖</span>
        <div class="bg-red-50 dark:bg-red-900/30 border border-red-200 rounded-xl px-4 py-3 max-w-2xl">
          <p class="text-sm text-red-600 dark:text-red-300 font-semibold">⚠️ Error al conectar con Gemma</p>
          <p class="text-xs text-red-500 mt-1">${escapeHtml(err.message)}</p>
          <p class="text-xs text-gray-400 mt-2">
            Verifica en WSL: <code class="bg-gray-100 px-1 rounded">ollama serve</code>
          </p>
        </div>
      </div>
    `);
  } finally {
    // 6. Restaurar UI
    loading.style.display = 'none';
    btnEnviar.disabled    = false;
    btnEnviar.textContent = 'Enviar →';
    chatArea.scrollTop    = chatArea.scrollHeight;
    mensajeInput.focus();
  }
}

// ── Escape XSS ────────────────────────────────────────────
function escapeHtml(text) {
  const d = document.createElement('div');
  d.appendChild(document.createTextNode(String(text)));
  return d.innerHTML;
}

// ── Scroll inicial al fondo ────────────────────────────────
window.addEventListener('load', () => {
  const c = document.getElementById('chat-mensajes');
  if (c) c.scrollTop = c.scrollHeight;
});
</script>

</body>
</html>
EOF

echo "✅ chat.html corregido — formulario sin <form> nativo"
echo ""
echo "🚀 Reinicia: ./mvnw spring-boot:run"
echo "🌐 Prueba:   http://localhost:8091/ia"

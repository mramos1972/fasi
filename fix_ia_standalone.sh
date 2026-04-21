#!/bin/bash
set -e
echo "🔧 Reescribiendo chat.html como página standalone..."

cat > src/main/resources/templates/ia/chat.html << 'HTMLEOF'
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>Asistente IA — FASI</title>
  <script th:src="@{/js/tailwind.min.js}"></script>
  <script>tailwind.config = { darkMode: 'class' };</script>
  <style>
    *, *::before, *::after {
      transition: background-color 0.2s ease, color 0.2s ease, border-color 0.2s ease;
    }
  </style>
</head>
<body class="bg-gray-100 dark:bg-gray-950 min-h-screen">

  <!-- TOPBAR MÓVIL -->
  <header class="lg:hidden bg-gray-900 dark:bg-gray-800 text-white sticky top-0 z-50 relative">
    <div class="flex items-center justify-between px-4 py-3">
      <span class="text-xl font-bold">⚡ FASI</span>
      <div class="flex items-center gap-3">
        <button onclick="toggleDark()" class="text-xl focus:outline-none">
          <span id="dark-icon">🌙</span>
        </button>
        <button id="menu-btn" class="text-white text-2xl focus:outline-none">☰</button>
      </div>
    </div>
    <nav id="mobile-menu"
         class="hidden absolute left-0 right-0 bg-gray-800 dark:bg-gray-900 px-4 pb-4 space-y-1 shadow-xl z-50">
      <a th:href="@{/}"             class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🏠 <span>Dashboard</span></a>
      <a th:href="@{/agenda}"       class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">📇 <span>Agenda</span></a>
      <a th:href="@{/tareas}"       class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">✅ <span>Tareas</span></a>
      <a th:href="@{/contabilidad}" class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">💰 <span>Contabilidad</span></a>
      <a th:href="@{/documentos}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">📄 <span>Documentos</span></a>
      <a th:href="@{/fotos}"        class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🖼️ <span>Fotos</span></a>
      <a th:href="@{/desarrollo}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🛠️ <span>Desarrollo</span></a>
      <a th:href="@{/ia}"           class="flex items-center gap-3 px-3 py-2 rounded-lg bg-indigo-700 transition">🤖 <span>Asistente IA</span></a>
      <div class="border-t border-gray-700 pt-2 mt-2 text-sm text-gray-400"
           xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
        <span sec:authentication="name">usuario</span>
        <form th:action="@{/logout}" method="post" class="inline ml-2">
          <button type="submit" class="text-red-400 hover:text-red-300">salir</button>
        </form>
      </div>
    </nav>
  </header>

  <div id="menu-overlay" class="hidden fixed inset-0 z-40 lg:hidden" onclick="cerrarMenu()"></div>

  <div class="flex">
    <!-- SIDEBAR DESKTOP -->
    <aside class="hidden lg:flex lg:flex-col w-64 bg-gray-900 dark:bg-gray-800 text-white min-h-screen fixed top-0 left-0 z-40">
      <div class="p-6 border-b border-gray-700 flex items-center justify-between">
        <div>
          <span class="text-2xl font-bold tracking-wide">⚡ FASI</span>
          <p class="text-xs text-gray-400 mt-1">Full Assistant</p>
        </div>
        <button onclick="toggleDark()" class="text-2xl focus:outline-none hover:scale-110 transition-transform ml-2">
          <span id="dark-icon-desktop">🌙</span>
        </button>
      </div>
      <nav class="flex-1 p-4 space-y-1">
        <a th:href="@{/}"             class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🏠 <span>Dashboard</span></a>
        <a th:href="@{/agenda}"       class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">📇 <span>Agenda</span></a>
        <a th:href="@{/tareas}"       class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">✅ <span>Tareas</span></a>
        <a th:href="@{/contabilidad}" class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">💰 <span>Contabilidad</span></a>
        <a th:href="@{/documentos}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">📄 <span>Documentos</span></a>
        <a th:href="@{/fotos}"        class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🖼️ <span>Fotos</span></a>
        <a th:href="@{/desarrollo}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🛠️ <span>Desarrollo</span></a>
        <a th:href="@{/ia}"           class="flex items-center gap-3 px-3 py-2 rounded-lg bg-indigo-700 transition">🤖 <span>Asistente IA</span></a>
      </nav>
      <div class="p-4 border-t border-gray-700 text-sm text-gray-400"
           xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
        <span sec:authentication="name">usuario</span>
        <form th:action="@{/logout}" method="post" class="inline ml-2">
          <button type="submit" class="text-red-400 hover:text-red-300">salir</button>
        </form>
      </div>
    </aside>

    <!-- CONTENIDO PRINCIPAL -->
    <main class="flex-1 lg:ml-64 p-4 lg:p-8 w-full">

      <div class="mb-4">
        <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100">🤖 Asistente IA</h2>
        <p class="text-sm text-gray-500 dark:text-gray-400 mt-1">
          Powered by <span class="font-semibold text-indigo-500">Gemma 3 4B</span>
          · Ollama local · Acceso completo a tus datos
        </p>
      </div>

      <!-- Selector de módulo -->
      <div class="flex flex-wrap gap-2 mb-4">
        <button type="button" onclick="setModulo(this,'general')"
                class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold bg-indigo-600 text-white">
          🌐 General
        </button>
        <button type="button" onclick="setModulo(this,'contabilidad')"
                class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
          💰 Contabilidad
        </button>
        <button type="button" onclick="setModulo(this,'tareas')"
                class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
          ✅ Tareas
        </button>
        <button type="button" onclick="setModulo(this,'agenda')"
                class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
          📇 Agenda
        </button>
        <button type="button" onclick="setModulo(this,'documentos')"
                class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
          📄 Documentos
        </button>
      </div>

      <!-- Área de mensajes -->
      <div id="chat-mensajes"
           class="bg-white dark:bg-gray-800 rounded-xl shadow min-h-80 max-h-[55vh] overflow-y-auto p-6 mb-4 flex flex-col gap-4">
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

      <!-- Spinner -->
      <div id="ia-loading" style="display:none;"
           class="mb-3 flex gap-2 items-center px-4 py-3 bg-indigo-50 dark:bg-indigo-900/30 rounded-xl w-fit">
        <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style="animation-delay:0ms"></div>
        <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style="animation-delay:150ms"></div>
        <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style="animation-delay:300ms"></div>
        <span class="text-xs text-gray-400 ml-1">Gemma está pensando...</span>
      </div>

      <!-- Input -->
      <div class="flex gap-3">
        <input type="hidden" id="modulo-input" value="general"/>
        <textarea id="mensaje-input" rows="2"
                  placeholder="Escribe tu pregunta... (Enter envía · Shift+Enter = nueva línea)"
                  class="flex-1 border border-gray-300 dark:border-gray-600 rounded-xl px-4 py-3
                         bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                         focus:outline-none focus:ring-2 focus:ring-indigo-500 resize-none text-sm"></textarea>
        <button type="button" id="btn-enviar"
                onclick="enviarMensaje()"
                class="bg-indigo-600 hover:bg-indigo-700 text-white px-6 py-3
                       rounded-xl font-semibold transition self-end">
          Enviar →
        </button>
      </div>

      <!-- Sugerencias -->
      <div class="mt-3 flex flex-wrap gap-2">
        <span class="text-xs text-gray-400 self-center">Sugerencias:</span>
        <button type="button" onclick="setSugerencia('Dame un resumen financiero completo con recomendaciones')"
                class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100 text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
          📊 Resumen financiero
        </button>
        <button type="button" onclick="setSugerencia('¿Qué tareas de alta prioridad tengo pendientes?')"
                class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100 text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
          🔥 Tareas urgentes
        </button>
        <button type="button" onclick="setSugerencia('Analiza mis gastos y dime en qué categorías gasto más')"
                class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100 text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
          💸 Análisis de gastos
        </button>
        <button type="button" onclick="setSugerencia('Dame consejos para mejorar mi productividad según mis tareas actuales')"
                class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100 text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
          💡 Consejos productividad
        </button>
      </div>

    </main>
  </div>

<!-- SCRIPTS AL FINAL DEL BODY — accesibles globalmente -->
<script>
  // Menú móvil
  const menuBtn    = document.getElementById('menu-btn');
  const mobileMenu = document.getElementById('mobile-menu');
  const overlay    = document.getElementById('menu-overlay');
  function abrirMenu()  { mobileMenu.classList.remove('hidden'); overlay.classList.remove('hidden'); }
  function cerrarMenu() { mobileMenu.classList.add('hidden');    overlay.classList.add('hidden'); }
  if (menuBtn) menuBtn.addEventListener('click', e => { e.stopPropagation(); mobileMenu.classList.contains('hidden') ? abrirMenu() : cerrarMenu(); });

  // Dark mode
  const htmlEl      = document.documentElement;
  const iconMobile  = document.getElementById('dark-icon');
  const iconDesktop = document.getElementById('dark-icon-desktop');
  function applyDark(isDark) {
    isDark ? htmlEl.classList.add('dark') : htmlEl.classList.remove('dark');
    const icon = isDark ? '☀️' : '🌙';
    if (iconMobile)  iconMobile.textContent  = icon;
    if (iconDesktop) iconDesktop.textContent = icon;
  }
  function toggleDark() {
    const isDark = !htmlEl.classList.contains('dark');
    localStorage.setItem('fasi-dark', isDark ? '1' : '0');
    applyDark(isDark);
  }
  (function(){
    const saved = localStorage.getItem('fasi-dark');
    applyDark(saved !== null ? saved === '1' : window.matchMedia('(prefers-color-scheme: dark)').matches);
  })();

  // Módulo activo
  function setModulo(btn, modulo) {
    document.getElementById('modulo-input').value = modulo;
    document.querySelectorAll('.modulo-btn').forEach(b => {
      b.classList.remove('bg-indigo-600','text-white');
      b.classList.add('bg-gray-200','text-gray-700');
    });
    btn.classList.add('bg-indigo-600','text-white');
    btn.classList.remove('bg-gray-200','text-gray-700');
  }

  // Sugerencias
  function setSugerencia(texto) {
    document.getElementById('mensaje-input').value = texto;
    document.getElementById('mensaje-input').focus();
  }

  // Enter para enviar
  document.getElementById('mensaje-input').addEventListener('keydown', function(e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); enviarMensaje(); }
  });

  // Envío principal
  async function enviarMensaje() {
    const mensajeInput = document.getElementById('mensaje-input');
    const moduloInput  = document.getElementById('modulo-input');
    const chatArea     = document.getElementById('chat-mensajes');
    const loading      = document.getElementById('ia-loading');
    const btnEnviar    = document.getElementById('btn-enviar');

    const mensaje = mensajeInput.value.trim();
    if (!mensaje) return;

    chatArea.insertAdjacentHTML('beforeend', `
      <div class="flex gap-3 items-start justify-end">
        <div class="bg-gray-100 dark:bg-gray-700 rounded-xl px-4 py-3 max-w-2xl">
          <p class="text-sm text-gray-800 dark:text-gray-100">${escapeHtml(mensaje)}</p>
        </div>
        <span class="text-2xl">👤</span>
      </div>`);

    mensajeInput.value    = '';
    btnEnviar.disabled    = true;
    btnEnviar.textContent = '⏳';
    loading.style.display = 'flex';
    chatArea.scrollTop    = chatArea.scrollHeight;

    try {
      const resp = await fetch('/ia/api/chat', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body:    JSON.stringify({ mensaje: mensaje, modulo: moduloInput.value || 'general' })
      });

      if (!resp.ok) throw new Error('HTTP ' + resp.status + ' — ' + await resp.text());
      const data = await resp.json();

      chatArea.insertAdjacentHTML('beforeend', `
        <div class="flex gap-3 items-start">
          <span class="text-2xl">🤖</span>
          <div class="bg-indigo-50 dark:bg-indigo-900/30 rounded-xl px-4 py-3 max-w-2xl">
            <p class="text-sm text-gray-700 dark:text-gray-200 whitespace-pre-wrap">${escapeHtml(data.respuesta)}</p>
            <p class="text-xs text-gray-400 mt-2">⏱ ${data.tiempoMs}ms · ${data.modulo || 'general'}</p>
          </div>
        </div>`);

    } catch (err) {
      chatArea.insertAdjacentHTML('beforeend', `
        <div class="flex gap-3 items-start">
          <span class="text-2xl">🤖</span>
          <div class="bg-red-50 dark:bg-red-900/30 border border-red-200 rounded-xl px-4 py-3 max-w-2xl">
            <p class="text-sm text-red-600 font-semibold">⚠️ Error al conectar con Gemma</p>
            <p class="text-xs text-red-500 mt-1">${escapeHtml(err.message)}</p>
            <p class="text-xs text-gray-400 mt-2">Verifica en WSL: <code>ollama serve</code></p>
          </div>
        </div>`);
    } finally {
      loading.style.display = 'none';
      btnEnviar.disabled    = false;
      btnEnviar.textContent = 'Enviar →';
      chatArea.scrollTop    = chatArea.scrollHeight;
      mensajeInput.focus();
    }
  }

  // Escape XSS
  function escapeHtml(text) {
    const d = document.createElement('div');
    d.appendChild(document.createTextNode(String(text)));
    return d.innerHTML;
  }

  // Scroll inicial
  window.addEventListener('load', () => {
    const c = document.getElementById('chat-mensajes');
    if (c) c.scrollTop = c.scrollHeight;
  });
</script>

</body>
</html>
HTMLEOF

echo ""
echo "════════════════════════════════════════"
echo "✅ fix_ia_standalone.sh aplicado"
echo "🚀 Reinicia: ./mvnw spring-boot:run"
echo "🌐 Prueba:   http://localhost:8091/ia"
echo "════════════════════════════════════════"

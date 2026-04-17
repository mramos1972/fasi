#!/bin/bash
# ============================================================
# FASI — apply-changes: Dark Mode Toggle
# Añade botón de modo oscuro fijo en topbar + sidebar
# Uso: chmod +x apply-changes.sh && ./apply-changes.sh
# ============================================================

set -e

BASEFILE="src/main/resources/templates/layout/base.html"

echo "🌙 Aplicando Dark Mode a $BASEFILE..."

cat > "$BASEFILE" << 'ENDHTML'
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security"
      th:fragment="layout(title, content)">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title th:replace="${title}">FASI</title>
  <script th:src="@{/js/tailwind.min.js}"></script>
  <script th:src="@{/js/htmx.min.js}"></script>
  <script>
    // Tailwind dark mode config — debe ir ANTES de que Tailwind procese clases
    tailwind.config = { darkMode: 'class' };
  </script>
  <style>
    /* Transición suave al cambiar de modo */
    *, *::before, *::after {
      transition: background-color 0.2s ease, color 0.2s ease, border-color 0.2s ease;
    }
  </style>
</head>
<body class="bg-gray-100 dark:bg-gray-950 min-h-screen">

  <!-- ═══ TOPBAR MÓVIL ═══ -->
  <header class="lg:hidden bg-gray-900 dark:bg-gray-800 text-white sticky top-0 z-50 relative">
    <div class="flex items-center justify-between px-4 py-3">
      <span class="text-xl font-bold">⚡ FASI</span>
      <div class="flex items-center gap-3">

        <!-- 🌙 DARK MODE TOGGLE -->
        <button id="dark-toggle"
                onclick="toggleDark()"
                title="Cambiar modo oscuro/claro"
                class="text-xl focus:outline-none hover:scale-110 transition-transform">
          <span id="dark-icon">🌙</span>
        </button>

        <button id="menu-btn"
                class="text-white text-2xl focus:outline-none">☰</button>
      </div>
    </div>

    <!-- Menú flotante móvil -->
    <nav id="mobile-menu"
         class="hidden absolute left-0 right-0 bg-gray-800 dark:bg-gray-900 px-4 pb-4 space-y-1 shadow-xl z-50">
      <a th:href="@{/}"             class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🏠 <span>Dashboard</span></a>
      <a th:href="@{/agenda}"       class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">📇 <span>Agenda</span></a>
      <a th:href="@{/tareas}"       class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">✅ <span>Tareas</span></a>
      <a th:href="@{/contabilidad}" class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">💰 <span>Contabilidad</span></a>
      <a th:href="@{/documentos}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">📄 <span>Documentos</span></a>
      <a th:href="@{/fotos}"        class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🖼️ <span>Fotos</span></a>
      <a th:href="@{/desarrollo}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🛠️ <span>Desarrollo</span></a>
      <div class="border-t border-gray-700 pt-2 mt-2 text-sm text-gray-400">
        <span sec:authentication="name">usuario</span>
        <form th:action="@{/logout}" method="post" class="inline ml-2">
          <button type="submit" class="text-red-400 hover:text-red-300">salir</button>
        </form>
      </div>
    </nav>
  </header>

  <!-- Overlay para cerrar menú móvil -->
  <div id="menu-overlay"
       class="hidden fixed inset-0 z-40 lg:hidden"
       onclick="cerrarMenu()"></div>

  <div class="flex">

    <!-- ═══ SIDEBAR DESKTOP ═══ -->
    <aside class="hidden lg:flex lg:flex-col w-64 bg-gray-900 dark:bg-gray-800 text-white min-h-screen fixed top-0 left-0 z-40">
      <div class="p-6 border-b border-gray-700 flex items-center justify-between">
        <div>
          <span class="text-2xl font-bold tracking-wide">⚡ FASI</span>
          <p class="text-xs text-gray-400 mt-1">Full Assistant</p>
        </div>
        <!-- 🌙 DARK MODE TOGGLE DESKTOP -->
        <button onclick="toggleDark()"
                title="Cambiar modo oscuro/claro"
                class="text-2xl focus:outline-none hover:scale-110 transition-transform ml-2">
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
      </nav>
      <div class="p-4 border-t border-gray-700 text-sm text-gray-400">
        <span sec:authentication="name">usuario</span>
        <form th:action="@{/logout}" method="post" class="inline ml-2">
          <button type="submit" class="text-red-400 hover:text-red-300">salir</button>
        </form>
      </div>
    </aside>

    <!-- ═══ CONTENIDO PRINCIPAL ═══ -->
    <main class="flex-1 lg:ml-64 p-4 lg:p-8 w-full">
      <th:block th:replace="${content}"/>
    </main>

  </div>

  <script>
    // ── Menú móvil ──────────────────────────────────────────
    const menuBtn    = document.getElementById('menu-btn');
    const mobileMenu = document.getElementById('mobile-menu');
    const overlay    = document.getElementById('menu-overlay');

    function abrirMenu() {
      mobileMenu.classList.remove('hidden');
      overlay.classList.remove('hidden');
    }

    function cerrarMenu() {
      mobileMenu.classList.add('hidden');
      overlay.classList.add('hidden');
    }

    menuBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      mobileMenu.classList.contains('hidden') ? abrirMenu() : cerrarMenu();
    });

    // ── Dark Mode ────────────────────────────────────────────
    const htmlEl        = document.documentElement;
    const iconMobile    = document.getElementById('dark-icon');
    const iconDesktop   = document.getElementById('dark-icon-desktop');

    function applyDark(isDark) {
      if (isDark) {
        htmlEl.classList.add('dark');
        if (iconMobile)  iconMobile.textContent  = '☀️';
        if (iconDesktop) iconDesktop.textContent = '☀️';
      } else {
        htmlEl.classList.remove('dark');
        if (iconMobile)  iconMobile.textContent  = '🌙';
        if (iconDesktop) iconDesktop.textContent = '🌙';
      }
    }

    function toggleDark() {
      const isDark = !htmlEl.classList.contains('dark');
      localStorage.setItem('fasi-dark', isDark ? '1' : '0');
      applyDark(isDark);
    }

    // Aplicar preferencia guardada (o preferencia del sistema si no hay nada guardado)
    (function () {
      const saved = localStorage.getItem('fasi-dark');
      if (saved !== null) {
        applyDark(saved === '1');
      } else {
        applyDark(window.matchMedia('(prefers-color-scheme: dark)').matches);
      }
    })();
  </script>

</body>
</html>
ENDHTML

echo "✅ base.html actualizado con Dark Mode."

# ── Dark mode en templates de contenido ──────────────────────
echo "🎨 Aplicando clases dark: a las vistas de contenido..."

# index.html — cards del dashboard
sed -i \
  's/class="bg-white rounded-xl shadow/class="bg-white dark:bg-gray-800 rounded-xl shadow/g' \
  src/main/resources/templates/index.html

sed -i \
  's/class="font-semibold text-gray-800"/class="font-semibold text-gray-800 dark:text-gray-100"/g' \
  src/main/resources/templates/index.html

sed -i \
  's/class="text-sm text-gray-500"/class="text-sm text-gray-500 dark:text-gray-400"/g' \
  src/main/resources/templates/index.html

sed -i \
  's/class="text-2xl font-bold text-gray-800 mb-6">Dashboard/class="text-2xl font-bold text-gray-800 dark:text-gray-100 mb-6">Dashboard/g' \
  src/main/resources/templates/index.html

echo "✅ index.html actualizado."

# Tablas y formularios — todos los módulos
for f in \
  src/main/resources/templates/agenda/list.html \
  src/main/resources/templates/agenda/form.html \
  src/main/resources/templates/tareas/list.html \
  src/main/resources/templates/tareas/form.html \
  src/main/resources/templates/contabilidad/list.html \
  src/main/resources/templates/contabilidad/form.html \
  src/main/resources/templates/documentos/list.html \
  src/main/resources/templates/documentos/form.html \
  src/main/resources/templates/desarrollo/list.html \
  src/main/resources/templates/desarrollo/form.html \
  src/main/resources/templates/fotos/stub.html \
  src/main/resources/templates/error/404.html \
  src/main/resources/templates/error/500.html
do
  # Fondo blanco de cards/tablas → oscuro
  sed -i 's/class="bg-white rounded-xl shadow/class="bg-white dark:bg-gray-800 rounded-xl shadow/g' "$f"
  sed -i 's/class="bg-white rounded-2xl shadow/class="bg-white dark:bg-gray-800 rounded-2xl shadow/g' "$f"

  # Cabeceras de tabla
  sed -i 's/class="bg-gray-50 text-gray-600 uppercase text-xs"/class="bg-gray-50 dark:bg-gray-700 text-gray-600 dark:text-gray-300 uppercase text-xs"/g' "$f"

  # Filas hover
  sed -i 's/class="hover:bg-gray-50"/class="hover:bg-gray-50 dark:hover:bg-gray-700"/g' "$f"

  # Divisores
  sed -i 's/class="divide-y divide-gray-100"/class="divide-y divide-gray-100 dark:divide-gray-700"/g' "$f"

  # Títulos h2
  sed -i 's/class="text-2xl font-bold text-gray-800/class="text-2xl font-bold text-gray-800 dark:text-gray-100/g' "$f"
  sed -i 's/class="text-3xl font-bold text-gray-800/class="text-3xl font-bold text-gray-800 dark:text-gray-100/g' "$f"

  # Texto secundario
  sed -i 's/class="text-gray-500"/class="text-gray-500 dark:text-gray-400"/g' "$f"

  # Labels de formulario
  sed -i 's/class="block text-sm font-medium text-gray-700 mb-1"/class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1"/g' "$f"

  # Inputs y textareas
  sed -i 's/class="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-gray-800"/class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2 bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-gray-400"/g' "$f"

  # Select
  sed -i 's/class="w-full border border-gray-300 rounded-lg px-3 py-2"/class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2 bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"/g' "$f"

  echo "  ✔ $f"
done

# Fondo general de páginas (body bg-gray-100)
# Ya cubierto en base.html con dark:bg-gray-950

echo ""
echo "✅ Dark Mode implantado correctamente en toda la app."
echo "   → Haz git push y Jenkins desplegará los cambios."

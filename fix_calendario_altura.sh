#!/bin/bash
# fix_calendario_altura.sh
set -e

FILE="src/main/resources/templates/calendario/index.html"

# 1. El grid usa height calculado para ocupar el espacio disponible
sed -i 's|class="grid grid-cols-7 border border-gray-200 dark:border-gray-700 rounded-xl overflow-hidden"|class="grid grid-cols-7 border border-gray-200 dark:border-gray-700 rounded-xl overflow-hidden" style="grid-auto-rows: 1fr;"|' "$FILE"

# 2. Las celdas vacías también se estiran
sed -i "s|vacia.className = 'min-h-14 sm:min-h-20 border-b border-r border-gray-100 dark:border-gray-700 bg-gray-50/50 dark:bg-gray-900/20';|vacia.className = 'border-b border-r border-gray-100 dark:border-gray-700 bg-gray-50/50 dark:bg-gray-900/20';|" "$FILE"

# 3. Las celdas de días también se estiran
sed -i "s|celda.className = 'min-h-14 sm:min-h-20 border-b border-r border-gray-100 dark:border-gray-700 p-1 cursor-pointer hover:bg-indigo-50 dark:hover:bg-indigo-900/20 transition'|celda.className = 'border-b border-r border-gray-100 dark:border-gray-700 p-1 cursor-pointer hover:bg-indigo-50 dark:hover:bg-indigo-900/20 transition'|" "$FILE"

# 4. La sección principal ocupa todo el alto disponible
sed -i 's|<section class="pb-2">|<section class="pb-2 flex flex-col h-full">|' "$FILE"

echo "✅ Altura del calendario corregida"
echo ""
echo "══════════════════════════════════"
echo "  Ahora ejecuta: ./dev-redeploy.sh"
echo "══════════════════════════════════"

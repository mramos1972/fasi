#!/bin/bash
# ════════════════════════════════════════════════════════════
#  Fix N+1 queries en etiquetas — Optimizar con JOIN FETCH
# ════════════════════════════════════════════════════════════

echo "🔧 Optimizando consultas de etiquetas..."

# Backup
cp src/main/java/com/miempresa/fasi/calendario/EventoRepository.java \
   src/main/java/com/miempresa/fasi/calendario/EventoRepository.java.bak

# Verificar si ya existe el método optimizado
if grep -q "JOIN FETCH e.etiquetas" src/main/java/com/miempresa/fasi/calendario/EventoRepository.java; then
  echo "✅ Optimización ya existe"
  exit 0
fi

# Añadir método con JOIN FETCH antes del último }
cat >> src/main/java/com/miempresa/fasi/calendario/EventoRepository.java << 'EOFJAVA'

    // ── Consultas optimizadas con JOIN FETCH para evitar N+1 ──
    @Query("SELECT DISTINCT e FROM Evento e LEFT JOIN FETCH e.etiquetas " +
           "WHERE e.fechaInicio <= :fin AND (e.fechaFin IS NULL OR e.fechaFin >= :inicio) " +
           "ORDER BY e.fechaInicio, e.horaInicio")
    List<Evento> findByRangoOptimizado(@Param("inicio") LocalDate inicio, 
                                        @Param("fin") LocalDate fin);

    @Query("SELECT DISTINCT e FROM Evento e LEFT JOIN FETCH e.etiquetas " +
           "WHERE e.fechaInicio >= :desde " +
           "ORDER BY e.fechaInicio, e.horaInicio")
    List<Evento> findProximosOptimizado(@Param("desde") LocalDate desde);

    @Query("SELECT DISTINCT e FROM Evento e LEFT JOIN FETCH e.etiquetas " +
           "ORDER BY e.fechaInicio, e.horaInicio")
    List<Evento> findAllOptimizado();
EOFJAVA

echo "✅ Métodos optimizados añadidos al repository"

# Ahora actualizar el Service para usar los métodos optimizados
echo "🔧 Actualizando EventoService para usar queries optimizadas..."

cp src/main/java/com/miempresa/fasi/calendario/EventoService.java \
   src/main/java/com/miempresa/fasi/calendario/EventoService.java.bak

# Reemplazar findByRango por findByRangoOptimizado
sed -i 's/repo\.findByRango(/repo.findByRangoOptimizado(/g' \
  src/main/java/com/miempresa/fasi/calendario/EventoService.java

# Reemplazar findProximos por findProximosOptimizado
sed -i 's/repo\.findProximos(/repo.findProximosOptimizado(/g' \
  src/main/java/com/miempresa/fasi/calendario/EventoService.java

# Para getAllEtiquetas, usar findAllOptimizado
sed -i 's/repo\.findAll()\.stream()/repo.findAllOptimizado().stream()/g' \
  src/main/java/com/miempresa/fasi/calendario/EventoService.java

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✅ OPTIMIZACIÓN N+1 APLICADA"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "🎯 ANTES:"
echo "   ❌ 1 query para eventos + N queries para etiquetas"
echo "   ❌ Si tienes 50 eventos = 51 queries"
echo ""
echo "🎯 AHORA:"
echo "   ✅ 1 query con JOIN FETCH"
echo "   ✅ Todas las etiquetas se cargan de golpe"
echo ""
echo "🚀 Ejecuta: ./dev-redeploy.sh"

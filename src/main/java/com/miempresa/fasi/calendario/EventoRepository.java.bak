package com.miempresa.fasi.calendario;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface EventoRepository extends JpaRepository<Evento, UUID> {

    // ── Consultas por rango de fechas (para la vista mensual/semanal) ──
    // Incluye eventos que EMPIEZAN en el mes O cuyo rango SOLAPA con el mes
    @Query("""
        SELECT e FROM Evento e
        WHERE e.fechaInicio <= :hasta
          AND (e.fechaFin IS NULL OR e.fechaFin >= :desde)
        ORDER BY e.fechaInicio ASC, e.horaInicio ASC
        """)
    List<Evento> findByMesConRango(@Param("desde") LocalDate desde,
                                   @Param("hasta") LocalDate hasta);

    // Mantener el original para compatibilidad
    List<Evento> findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(
            LocalDate desde, LocalDate hasta);

    // ── Consultas por tipo ──────────────────────────────────
    List<Evento> findByTipoOrderByFechaInicioAsc(Evento.TipoEvento tipo);

    // ── Búsqueda full-text con PostgreSQL tsvector ──────────
    @Query(value = """
        SELECT * FROM eventos
        WHERE texto_busqueda @@ plainto_tsquery('spanish', unaccent(:query))
        ORDER BY fecha_inicio DESC
        LIMIT 50
        """, nativeQuery = true)
    List<Evento> buscarFullText(@Param("query") String query);

    // ── Próximos eventos (para widget de dashboard) ─────────
    @Query("SELECT e FROM Evento e WHERE e.fechaInicio >= :hoy ORDER BY e.fechaInicio ASC, e.horaInicio ASC")
    List<Evento> findProximos(@Param("hoy") LocalDate hoy);

    // ── Eventos de un día concreto ──────────────────────────
    List<Evento> findByFechaInicioOrderByHoraInicioAsc(LocalDate fecha);

    // Eventos con datos pendientes (sin hora, sin lugar, etc.)
    @Query(value = "SELECT * FROM eventos WHERE datos_extra @> '{\"pendienteHora\": true}'::jsonb ORDER BY fecha_inicio ASC", nativeQuery = true)
    List<Evento> findEventosPendientesHora();

    @Query(value = "SELECT * FROM eventos WHERE datos_extra @> '{\"pendienteHora\": true}'::jsonb OR datos_extra @> '{\"pendienteLugar\": true}'::jsonb ORDER BY fecha_inicio ASC", nativeQuery = true)
    List<Evento> findEventosPendientesDatos();

}
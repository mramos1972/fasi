package com.miempresa.fasi.calendario;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventoRepository extends JpaRepository<Evento, UUID> {

    @Query("""
        SELECT DISTINCT e FROM Evento e LEFT JOIN FETCH e.etiquetas
        WHERE e.usuario.id = :usuarioId
          AND e.fechaInicio <= :hasta
          AND (e.fechaFin IS NULL OR e.fechaFin >= :desde)
        ORDER BY e.fechaInicio ASC, e.horaInicio ASC
        """)
    List<Evento> findByMesConRango(
            @Param("usuarioId") UUID usuarioId,
            @Param("desde") LocalDate desde,
            @Param("hasta") LocalDate hasta);

    @Query("""
        SELECT DISTINCT e FROM Evento e LEFT JOIN FETCH e.etiquetas
        WHERE e.usuario.id = :usuarioId
          AND e.fechaInicio BETWEEN :desde AND :hasta
        ORDER BY e.fechaInicio ASC, e.horaInicio ASC
        """)
    List<Evento> findByRango(
            @Param("usuarioId") UUID usuarioId,
            @Param("desde") LocalDate desde,
            @Param("hasta") LocalDate hasta);

    @Query("""
        SELECT DISTINCT e FROM Evento e LEFT JOIN FETCH e.etiquetas
        WHERE e.usuario.id = :usuarioId AND e.tipo = :tipo
        ORDER BY e.fechaInicio ASC
        """)
    List<Evento> findByUsuarioIdAndTipoOrderByFechaInicioAsc(
            @Param("usuarioId") UUID usuarioId,
            @Param("tipo") Evento.TipoEvento tipo);

    @Query(value = """
        SELECT * FROM eventos
        WHERE usuario_id = :usuarioId
          AND texto_busqueda @@ plainto_tsquery('spanish', unaccent(:query))
        ORDER BY fecha_inicio DESC
        LIMIT 50
        """, nativeQuery = true)
    List<Evento> buscarFullText(@Param("usuarioId") UUID usuarioId, @Param("query") String query);

    @Query("""
        SELECT DISTINCT e FROM Evento e LEFT JOIN FETCH e.etiquetas
        WHERE e.usuario.id = :usuarioId AND e.fechaInicio = :fecha
        ORDER BY e.horaInicio ASC
        """)
    List<Evento> findByUsuarioIdAndFecha(@Param("usuarioId") UUID usuarioId, @Param("fecha") LocalDate fecha);

    @Query(value = """
        SELECT * FROM eventos
        WHERE usuario_id = :usuarioId
          AND (datos_extra @> '{"pendienteHora": true}'::jsonb OR datos_extra @> '{"pendienteLugar": true}'::jsonb)
        ORDER BY fecha_inicio ASC
        """, nativeQuery = true)
    List<Evento> findEventosPendientesDatos(@Param("usuarioId") UUID usuarioId);

    @Query("""
        SELECT DISTINCT e FROM Evento e LEFT JOIN FETCH e.etiquetas
        WHERE e.usuario.id = :usuarioId AND e.fechaInicio >= :desde
        ORDER BY e.fechaInicio ASC, e.horaInicio ASC
        """)
    List<Evento> findProximosOptimizado(@Param("usuarioId") UUID usuarioId, @Param("desde") LocalDate desde);

    @Query("""
        SELECT DISTINCT e FROM Evento e LEFT JOIN FETCH e.etiquetas
        WHERE e.usuario.id = :usuarioId
        ORDER BY e.fechaInicio, e.horaInicio
        """)
    List<Evento> findAllOptimizado(@Param("usuarioId") UUID usuarioId);

    Optional<Evento> findByIdAndUsuarioId(UUID id, UUID usuarioId);

    void deleteAllByUsuarioId(UUID usuarioId);
}

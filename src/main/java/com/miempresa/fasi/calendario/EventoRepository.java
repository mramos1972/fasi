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

    // ══════════════════════════════════════════════════════════
    //  MÉTODOS MULTIUSUARIO (NUEVOS)
    // ══════════════════════════════════════════════════════════

    @Query("""
        SELECT DISTINCT e FROM Evento e 
        LEFT JOIN FETCH e.etiquetas 
        JOIN FETCH e.usuario u
        WHERE e.usuario.id IN :usuarioIds
          AND e.fechaInicio <= :hasta
          AND (e.fechaFin IS NULL OR e.fechaFin >= :desde)
        ORDER BY e.fechaInicio ASC, e.horaInicio ASC
        """)
    List<Evento> findByMesConRangoMulti(
            @Param("usuarioIds") List<UUID> usuarioIds,
            @Param("desde") LocalDate desde,
            @Param("hasta") LocalDate hasta);

    @Query("""
        SELECT DISTINCT e FROM Evento e 
        LEFT JOIN FETCH e.etiquetas 
        JOIN FETCH e.usuario u
        WHERE e.usuario.id IN :usuarioIds
          AND e.fechaInicio BETWEEN :desde AND :hasta
        ORDER BY e.fechaInicio ASC, e.horaInicio ASC
        """)
    List<Evento> findByRangoMulti(
            @Param("usuarioIds") List<UUID> usuarioIds,
            @Param("desde") LocalDate desde,
            @Param("hasta") LocalDate hasta);

    @Query("""
        SELECT DISTINCT e FROM Evento e 
        LEFT JOIN FETCH e.etiquetas 
        JOIN FETCH e.usuario u
        WHERE e.usuario.id IN :usuarioIds 
          AND e.fechaInicio = :fecha
        ORDER BY e.horaInicio ASC
        """)
    List<Evento> findByFechaMulti(
            @Param("usuarioIds") List<UUID> usuarioIds, 
            @Param("fecha") LocalDate fecha);

    @Query("""
        SELECT DISTINCT e FROM Evento e 
        LEFT JOIN FETCH e.etiquetas 
        JOIN FETCH e.usuario u
        WHERE e.usuario.id IN :usuarioIds 
          AND e.fechaInicio >= :desde
        ORDER BY e.fechaInicio ASC, e.horaInicio ASC
        """)
    List<Evento> findProximosMulti(
            @Param("usuarioIds") List<UUID> usuarioIds, 
            @Param("desde") LocalDate desde);

    @Query(value = """
        SELECT * FROM eventos
        WHERE usuario_id IN :usuarioIds
          AND texto_busqueda @@ plainto_tsquery('spanish', unaccent(:query))
        ORDER BY fecha_inicio DESC
        LIMIT 50
        """, nativeQuery = true)
    List<Evento> buscarFullTextMulti(@Param("usuarioIds") List<UUID> usuarioIds, @Param("query") String query);

    @Query("""
        SELECT e FROM Evento e 
        JOIN FETCH e.usuario u
        WHERE e.id = :id AND e.usuario.id IN :usuarioIds
        """)
    Optional<Evento> findByIdAndPermitidos(@Param("id") UUID id, @Param("usuarioIds") List<UUID> usuarioIds);

    // ══════════════════════════════════════════════════════════
    //  MÉTODOS MONOUSUARIO (COMPATIBILIDAD CON IA Y AGENTE)
    // ══════════════════════════════════════════════════════════

    default List<Evento> findByRango(UUID usuarioId, LocalDate desde, LocalDate hasta) {
        return findByRangoMulti(List.of(usuarioId), desde, hasta);
    }

    @Query("""
        SELECT DISTINCT e FROM Evento e 
        LEFT JOIN FETCH e.etiquetas 
        JOIN FETCH e.usuario u
        WHERE e.usuario.id = :usuarioId AND e.tipo = :tipo
        ORDER BY e.fechaInicio ASC
        """)
    List<Evento> findByUsuarioIdAndTipoOrderByFechaInicioAsc(
            @Param("usuarioId") UUID usuarioId,
            @Param("tipo") Evento.TipoEvento tipo);

    @Query("""
        SELECT DISTINCT e FROM Evento e 
        LEFT JOIN FETCH e.etiquetas 
        JOIN FETCH e.usuario u
        WHERE e.usuario.id = :usuarioId
        ORDER BY e.fechaInicio, e.horaInicio
        """)
    List<Evento> findAllOptimizado(@Param("usuarioId") UUID usuarioId);

    @Query(value = """
        SELECT * FROM eventos
        WHERE usuario_id = :usuarioId
          AND texto_busqueda @@ plainto_tsquery('spanish', unaccent(:query))
        ORDER BY fecha_inicio DESC
        LIMIT 50
        """, nativeQuery = true)
    List<Evento> buscarFullText(@Param("usuarioId") UUID usuarioId, @Param("query") String query);

    void deleteAllByUsuarioId(UUID usuarioId);
}

package com.miempresa.fasi.compra.comparador;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PrecioCacheRepository extends JpaRepository<PrecioCache, UUID> {

    @Query("""
        SELECT pc FROM PrecioCache pc
        WHERE pc.supermercado.id = :supermercadoId
          AND (pc.terminoBusqueda = :termino OR LOWER(pc.productoNombre) = LOWER(:termino))
          AND pc.actualizadoEl >= :limite
        ORDER BY pc.actualizadoEl DESC
    """)
    List<PrecioCache> buscarPreciosRecientes(
            @Param("supermercadoId") UUID supermercadoId,
            @Param("termino") String termino,
            @Param("limite") LocalDateTime limite
    );

    @Query("""
        SELECT pc FROM PrecioCache pc
        WHERE pc.supermercado.id = :supermercadoId
          AND pc.actualizadoEl >= :limite
        ORDER BY pc.precio ASC
    """)
    List<PrecioCache> buscarCandidatos(
            @Param("supermercadoId") UUID supermercadoId,
            @Param("limite") LocalDateTime limite
    );
}

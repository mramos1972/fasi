package com.miempresa.fasi.compra.comparador;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PrecioCacheRepository extends JpaRepository<PrecioCache, UUID> {

    @Query("""
        SELECT p FROM PrecioCache p 
        WHERE p.supermercado.id = :supermercadoId 
          AND LOWER(p.terminoBusqueda) = LOWER(:termino)
          AND p.actualizadoEl >= :validoDesde
        ORDER BY p.actualizadoEl DESC
        LIMIT 1
    """)
    Optional<PrecioCache> buscarPrecioReciente(
            @Param("supermercadoId") UUID supermercadoId,
            @Param("termino") String termino,
            @Param("validoDesde") LocalDateTime validoDesde
    );
}

package com.miempresa.fasi.compra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ItemCompraRepository extends JpaRepository<ItemCompra, UUID> {

    List<ItemCompra> findByUsuarioIdOrderByCompradoAscCreatedAtDesc(UUID usuarioId);

    @Query("SELECT i FROM ItemCompra i WHERE i.usuario.id = :usuarioId AND LOWER(i.nombre) = LOWER(:nombre)")
    Optional<ItemCompra> findByUsuarioIdAndNombreIgnoreCase(
            @Param("usuarioId") UUID usuarioId, 
            @Param("nombre") String nombre
    );

    @Query("SELECT i FROM ItemCompra i WHERE i.usuario.id = :usuarioId AND LOWER(i.nombre) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<ItemCompra> buscarCoincidencias(
            @Param("usuarioId") UUID usuarioId, 
            @Param("query") String query
    );
}

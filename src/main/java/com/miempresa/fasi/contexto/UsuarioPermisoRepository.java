package com.miempresa.fasi.contexto;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UsuarioPermisoRepository extends JpaRepository<UsuarioPermiso, UsuarioPermiso.UsuarioPermisoId> {

    @Query("SELECT p.propietarioId FROM UsuarioPermiso p WHERE p.lectorId = :lectorId")
    List<UUID> findPropietariosVisiblesPorLector(@Param("lectorId") UUID lectorId);

    @Modifying
    @Query("DELETE FROM UsuarioPermiso p WHERE p.lectorId = :lectorId")
    void deleteByLectorId(@Param("lectorId") UUID lectorId);
}

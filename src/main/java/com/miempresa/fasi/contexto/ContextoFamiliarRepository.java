package com.miempresa.fasi.contexto;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContextoFamiliarRepository extends JpaRepository<ContextoFamiliar, UUID> {
    Optional<ContextoFamiliar> findByClave(String clave);
    List<ContextoFamiliar> findAllByOrderByClaveAsc();
}

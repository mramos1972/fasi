package com.miempresa.fasi.compra.comparador;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SupermercadoRepository extends JpaRepository<Supermercado, UUID> {
    List<Supermercado> findByActivoTrue();
    Optional<Supermercado> findByCodigoInterno(String codigoInterno);
}

package com.miempresa.fasi.documentos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentoRepository extends JpaRepository<Documento, UUID> {
    List<Documento> findAllByOrderByCreatedAtDesc();
    List<Documento> findByCategoriaIgnoreCaseOrderByCreatedAtDesc(String categoria);
}

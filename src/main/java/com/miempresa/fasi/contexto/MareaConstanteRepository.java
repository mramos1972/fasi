package com.miempresa.fasi.contexto;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MareaConstanteRepository extends JpaRepository<MareaConstante, Long> {
    List<MareaConstante> findByActivoTrue();
}

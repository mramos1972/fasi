package com.miempresa.fasi.contabilidad;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovimientoService {

    private final MovimientoRepository repo;

    public List<Movimiento> findAll() { return repo.findAllByOrderByFechaDesc(); }

    public List<Movimiento> findByRango(LocalDate desde, LocalDate hasta) {
        return repo.findByFechaBetweenOrderByFechaDesc(desde, hasta);
    }

    public Movimiento findById(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Movimiento no encontrado: " + id));
    }

    public Map<String, BigDecimal> resumen() {
        BigDecimal ingresos = repo.sumByTipo(Movimiento.TipoMovimiento.INGRESO);
        BigDecimal gastos   = repo.sumByTipo(Movimiento.TipoMovimiento.GASTO);
        return Map.of(
            "ingresos", ingresos,
            "gastos",   gastos,
            "saldo",    ingresos.subtract(gastos)
        );
    }

    @Transactional
    public Movimiento save(Movimiento m) { return repo.save(m); }

    @Transactional
    public Movimiento update(UUID id, Movimiento datos) {
        Movimiento m = findById(id);
        m.setConcepto(datos.getConcepto());
        m.setImporte(datos.getImporte());
        m.setTipo(datos.getTipo());
        m.setCategoria(datos.getCategoria());
        m.setFecha(datos.getFecha());
        m.setNotas(datos.getNotas());
        return repo.save(m);
    }

    @Transactional
    public void delete(UUID id) { repo.deleteById(id); }
}

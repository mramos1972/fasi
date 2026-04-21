package com.miempresa.fasi.ia;

import com.miempresa.fasi.agenda.ContactoRepository;
import com.miempresa.fasi.contabilidad.MovimientoRepository;
import com.miempresa.fasi.contabilidad.Movimiento;
import com.miempresa.fasi.tareas.TareaRepository;
import com.miempresa.fasi.tareas.Tarea;
import com.miempresa.fasi.documentos.DocumentoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class IaContextBuilder {

    private final ContactoRepository     contactoRepo;
    private final MovimientoRepository   movimientoRepo;
    private final TareaRepository        tareaRepo;
    private final DocumentoRepository    documentoRepo;

    /**
     * Construye el prompt completo con contexto de BD según el módulo solicitado.
     */
    public String buildPrompt(String preguntaUsuario, String modulo) {
        StringBuilder sb = new StringBuilder();

        sb.append("Eres FASI-IA, el asistente inteligente de la aplicación FASI. ");
        sb.append("Eres experto en análisis de datos, contabilidad, gestión de tareas y productividad. ");
        sb.append("Responde siempre en español, de forma clara, estructurada y útil. ");
        sb.append("Usa emojis con moderación para mejorar la legibilidad.\n\n");

        // Contexto según módulo
        if (modulo == null || modulo.isBlank() || modulo.equals("general")) {
            sb.append(buildContextoGeneral());
        } else {
            switch (modulo) {
                case "contabilidad" -> sb.append(buildContextoContabilidad());
                case "tareas"       -> sb.append(buildContextoTareas());
                case "agenda"       -> sb.append(buildContextoAgenda());
                case "documentos"   -> sb.append(buildContextoDocumentos());
                default             -> sb.append(buildContextoGeneral());
            }
        }

        sb.append("\n\n--- PREGUNTA DEL USUARIO ---\n");
        sb.append(preguntaUsuario);
        sb.append("\n\n--- TU RESPUESTA ---\n");

        return sb.toString();
    }

    // ── Contextos específicos ────────────────────────────────

    private String buildContextoGeneral() {
        StringBuilder ctx = new StringBuilder("=== RESUMEN GENERAL DE FASI ===\n");

        // Contabilidad
        BigDecimal ingresos = movimientoRepo.sumByTipo(Movimiento.TipoMovimiento.INGRESO);
        BigDecimal gastos   = movimientoRepo.sumByTipo(Movimiento.TipoMovimiento.GASTO);
        ctx.append(String.format("💰 CONTABILIDAD: Ingresos=%.2f€, Gastos=%.2f€, Saldo=%.2f€\n",
                ingresos, gastos, ingresos.subtract(gastos)));

        // Tareas
        long pendientes = tareaRepo.findAllByOrderByPrioridadAscFechaVencimientoAsc()
                .stream().filter(t -> t.getEstado() == Tarea.EstadoTarea.PENDIENTE).count();
        long enCurso    = tareaRepo.findAllByOrderByPrioridadAscFechaVencimientoAsc()
                .stream().filter(t -> t.getEstado() == Tarea.EstadoTarea.EN_CURSO).count();
        ctx.append(String.format("✅ TAREAS: %d pendientes, %d en curso\n", pendientes, enCurso));

        // Agenda
        long contactos = contactoRepo.count();
        ctx.append(String.format("📇 AGENDA: %d contactos\n", contactos));

        // Documentos
        long docs = documentoRepo.count();
        ctx.append(String.format("📄 DOCUMENTOS: %d ficheros\n", docs));

        return ctx.toString();
    }

    private String buildContextoContabilidad() {
        StringBuilder ctx = new StringBuilder("=== DATOS DE CONTABILIDAD ===\n");

        BigDecimal ingresos = movimientoRepo.sumByTipo(Movimiento.TipoMovimiento.INGRESO);
        BigDecimal gastos   = movimientoRepo.sumByTipo(Movimiento.TipoMovimiento.GASTO);
        ctx.append(String.format("Total ingresos: %.2f€\nTotal gastos: %.2f€\nSaldo: %.2f€\n\n",
                ingresos, gastos, ingresos.subtract(gastos)));

        ctx.append("Últimos 30 movimientos:\n");
        movimientoRepo.findAllByOrderByFechaDesc()
                .stream().limit(30)
                .forEach(m -> ctx.append(String.format(
                        "  [%s] %s | %s | %.2f€ | cat: %s\n",
                        m.getFecha(), m.getTipo(), m.getConcepto(),
                        m.getImporte(), m.getCategoria() != null ? m.getCategoria() : "sin categoría")));

        return ctx.toString();
    }

    private String buildContextoTareas() {
        StringBuilder ctx = new StringBuilder("=== DATOS DE TAREAS ===\n");

        tareaRepo.findAllByOrderByPrioridadAscFechaVencimientoAsc()
                .forEach(t -> ctx.append(String.format(
                        "  [%s][%s] %s | vence: %s\n",
                        t.getEstado(), t.getPrioridad(), t.getTitulo(),
                        t.getFechaVencimiento() != null ? t.getFechaVencimiento() : "sin fecha")));

        return ctx.toString();
    }

    private String buildContextoAgenda() {
        StringBuilder ctx = new StringBuilder("=== DATOS DE AGENDA ===\n");

        contactoRepo.findAllByOrderByApellidosAscNombreAsc()
                .forEach(c -> ctx.append(String.format(
                        "  %s %s | email: %s | tel: %s\n",
                        c.getNombre(),
                        c.getApellidos() != null ? c.getApellidos() : "",
                        c.getEmail() != null ? c.getEmail() : "-",
                        c.getTelefono() != null ? c.getTelefono() : "-")));

        return ctx.toString();
    }

    private String buildContextoDocumentos() {
        StringBuilder ctx = new StringBuilder("=== DATOS DE DOCUMENTOS ===\n");

        documentoRepo.findAllByOrderByCreatedAtDesc()
                .forEach(d -> ctx.append(String.format(
                        "  %s | cat: %s | tipo: %s | %.1f KB\n",
                        d.getNombre(),
                        d.getCategoria() != null ? d.getCategoria() : "sin categoría",
                        d.getTipo() != null ? d.getTipo() : "-",
                        d.getTamanioBytes() != null ? d.getTamanioBytes() / 1024.0 : 0)));

        return ctx.toString();
    }
}

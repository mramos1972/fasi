#!/bin/bash
# fix_ia_context.sh — Corrige IaContextBuilder.java
# Fix 1: Contactos completos en contexto general
# Fix 2: Instrucciones estrictas para no inventar datos

set -e

FILE="src/main/java/com/miempresa/fasi/ia/IaContextBuilder.java"

if [ ! -f "$FILE" ]; then
  echo "❌ No se encuentra $FILE — ¿estás en la raíz del proyecto?"
  exit 1
fi

echo "📝 Aplicando fixes en $FILE ..."

cat > "$FILE" << 'JAVA_EOF'
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

        // ── FIX 2: Instrucciones estrictas — prohibir inventar datos ──
        sb.append("Eres FASI-IA, el asistente inteligente de la aplicación FASI.\n");
        sb.append("Tienes acceso a los datos REALES del sistema que se muestran a continuación.\n\n");
        sb.append("REGLAS ESTRICTAS — DEBES CUMPLIRLAS SIEMPRE:\n");
        sb.append("1. Responde ÚNICAMENTE con los datos que aparecen en el contexto proporcionado.\n");
        sb.append("2. Si un dato no está en el contexto, responde exactamente: 'No tengo ese dato registrado en el sistema.'\n");
        sb.append("3. NUNCA inventes, supongas ni completes información que no esté en el contexto.\n");
        sb.append("4. Responde siempre en español, de forma clara y estructurada.\n");
        sb.append("5. Usa emojis con moderación para mejorar la legibilidad.\n\n");

        // ── Contexto según módulo ──
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
        sb.append("\n\n--- TU RESPUESTA (solo con datos del contexto) ---\n");

        return sb.toString();
    }

    // ── Contextos específicos ────────────────────────────────

    private String buildContextoGeneral() {
        StringBuilder ctx = new StringBuilder("=== RESUMEN GENERAL DE FASI ===\n\n");

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

        // FIX 1: Agenda con datos completos en lugar de solo el conteo
        ctx.append("\n📇 AGENDA — LISTADO COMPLETO DE CONTACTOS:\n");
        var contactos = contactoRepo.findAllByOrderByApellidosAscNombreAsc();
        if (contactos.isEmpty()) {
            ctx.append("  (sin contactos registrados)\n");
        } else {
            contactos.forEach(c -> ctx.append(String.format(
                    "  - %s %s | email: %s | tel: %s\n",
                    c.getNombre(),
                    c.getApellidos() != null ? c.getApellidos() : "",
                    c.getEmail()     != null ? c.getEmail()     : "-",
                    c.getTelefono()  != null ? c.getTelefono()  : "-")));
        }

        // Documentos
        long docs = documentoRepo.count();
        ctx.append(String.format("\n📄 DOCUMENTOS: %d ficheros\n", docs));

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

        var contactos = contactoRepo.findAllByOrderByApellidosAscNombreAsc();
        if (contactos.isEmpty()) {
            ctx.append("  (sin contactos registrados)\n");
        } else {
            contactos.forEach(c -> ctx.append(String.format(
                    "  - %s %s | email: %s | tel: %s\n",
                    c.getNombre(),
                    c.getApellidos() != null ? c.getApellidos() : "",
                    c.getEmail()     != null ? c.getEmail()     : "-",
                    c.getTelefono()  != null ? c.getTelefono()  : "-")));
        }

        return ctx.toString();
    }

    private String buildContextoDocumentos() {
        StringBuilder ctx = new StringBuilder("=== DATOS DE DOCUMENTOS ===\n");

        documentoRepo.findAllByOrderByCreatedAtDesc()
                .forEach(d -> ctx.append(String.format(
                        "  %s | cat: %s | tipo: %s | %.1f KB\n",
                        d.getNombre(),
                        d.getCategoria()    != null ? d.getCategoria()    : "sin categoría",
                        d.getTipo()         != null ? d.getTipo()         : "-",
                        d.getTamanioBytes() != null ? d.getTamanioBytes() / 1024.0 : 0)));

        return ctx.toString();
    }
}
JAVA_EOF

echo "✅ $FILE actualizado correctamente."
echo ""
echo "🚀 Ahora redespliega con:"
echo "   ./dev-redeploy.sh"

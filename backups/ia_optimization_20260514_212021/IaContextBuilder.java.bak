package com.miempresa.fasi.ia;

import com.miempresa.fasi.calendario.Evento;
import com.miempresa.fasi.calendario.EventoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class IaContextBuilder {

    private final EventoRepository eventoRepo;

    public String buildPrompt(String preguntaUsuario, String modulo) {
        StringBuilder sb = new StringBuilder();

        sb.append("Eres FASI-IA, el asistente inteligente del calendario FASI.\n");
        sb.append("Tienes acceso a los datos REALES del sistema que se muestran a continuación.\n\n");
        sb.append("REGLAS ESTRICTAS:\n");
        sb.append("1. Responde ÚNICAMENTE con los datos del contexto proporcionado.\n");
        sb.append("2. Si un dato no está, responde: 'No tengo ese dato registrado.'\n");
        sb.append("3. NUNCA inventes información.\n");
        sb.append("4. Responde siempre en español, de forma clara y estructurada.\n");
        sb.append("5. Usa emojis con moderación.\n\n");

        sb.append(buildContextoCalendario());

        sb.append("\n\n--- PREGUNTA DEL USUARIO ---\n");
        sb.append(preguntaUsuario);
        sb.append("\n\n--- TU RESPUESTA ---\n");

        return sb.toString();
    }

    private String buildContextoCalendario() {
        StringBuilder ctx = new StringBuilder("=== CALENDARIO FASI ===\n\n");

        LocalDate hoy = LocalDate.now();
        LocalDate fin = hoy.plusMonths(3);

        List<Evento> proximos = eventoRepo
                .findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(hoy, fin);

        ctx.append("Fecha de hoy: ").append(hoy).append("\n\n");

        if (proximos.isEmpty()) {
            ctx.append("(No hay eventos próximos registrados)\n");
        } else {
            ctx.append("Próximos eventos (3 meses):\n");
            proximos.forEach(e -> ctx.append(String.format(
                    "  [%s] %s %s%s%s%s\n",
                    e.getFechaInicio(),
                    e.getTipo().getEmoji(),
                    e.getTitulo(),
                    e.getHoraInicio() != null ? " a las " + e.getHoraInicio() : "",
                    e.getLugar() != null ? " en " + e.getLugar() : "",
                    e.getPersonasRelacionadas() != null ? " · " + e.getPersonasRelacionadas() : ""
            )));
        }

        // Resumen histórico por tipo
        ctx.append("\nTotal eventos por tipo:\n");
        for (Evento.TipoEvento tipo : Evento.TipoEvento.values()) {
            long count = eventoRepo.findByTipoOrderByFechaInicioAsc(tipo).size();
            if (count > 0) {
                ctx.append(String.format("  %s %s: %d\n",
                        tipo.getEmoji(), tipo.getLabel(), count));
            }
        }

        return ctx.toString();
    }
}

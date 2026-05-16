package com.miempresa.fasi.ia;

import com.miempresa.fasi.calendario.Evento;
import com.miempresa.fasi.calendario.EventoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class IaContextBuilder {

    private final EventoRepository eventoRepo;

    private static final int MAX_EVENTOS_CTX  = 15;
    private static final int RANGO_DIAS_BASE  = 30;
    private static final int RANGO_DIAS_AMPLIO = 90;

    // ════════════════════════════════════════════════════════
    //  Prompt principal — compacto y adaptativo
    // ════════════════════════════════════════════════════════
    public String buildPrompt(String preguntaUsuario, String modulo) {
        String ctx = buildContextoFiltrado(preguntaUsuario);

        return """
            Eres FASI, asistente del calendario. Responde en español, breve y claro.
            Solo usa los datos del CONTEXTO. Si falta un dato responde:
            "No tengo ese dato registrado."
            Nunca inventes información.

            %s

            Pregunta: %s
            Respuesta:
            """.formatted(ctx, preguntaUsuario);
    }

    // ════════════════════════════════════════════════════════
    //  Contexto FILTRADO según palabras clave de la pregunta
    // ════════════════════════════════════════════════════════
    private String buildContextoFiltrado(String pregunta) {
        String p = pregunta.toLowerCase();
        LocalDate hoy = LocalDate.now();

        Evento.TipoEvento tipoFiltro = detectarTipo(p);
        int dias = detectarRangoDias(p);
        LocalDate fin = hoy.plusDays(dias);

        List<Evento> eventos;
        if (tipoFiltro != null) {
            eventos = eventoRepo.findByTipoOrderByFechaInicioAsc(tipoFiltro).stream()
                    .filter(e -> !e.getFechaInicio().isBefore(hoy)
                              && !e.getFechaInicio().isAfter(fin))
                    .limit(MAX_EVENTOS_CTX)
                    .collect(Collectors.toList());
        } else {
            eventos = eventoRepo
                    .findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(hoy, fin)
                    .stream()
                    .limit(MAX_EVENTOS_CTX)
                    .collect(Collectors.toList());
        }

        StringBuilder ctx = new StringBuilder("=== CONTEXTO ===\n");
        ctx.append("Hoy: ").append(hoy).append("\n");
        if (tipoFiltro != null) {
            ctx.append("Filtro tipo: ").append(tipoFiltro.getLabel()).append("\n");
        }
        ctx.append("Rango: próximos ").append(dias).append(" días\n\n");

        if (eventos.isEmpty()) {
            ctx.append("Sin eventos en el rango consultado.\n");
        } else {
            ctx.append("Eventos:\n");
            eventos.forEach(e -> ctx
                .append("- ").append(e.getFechaInicio())
                .append(e.getHoraInicio() != null ? " " + e.getHoraInicio() : "")
                .append(" | ").append(e.getTipo().getLabel())
                .append(" | ").append(e.getTitulo())
                .append(e.getLugar() != null ? " (" + e.getLugar() + ")" : "")
                .append(e.getPersonasRelacionadas() != null
                        ? " · " + e.getPersonasRelacionadas() : "")
                .append("\n"));
        }
        return ctx.toString();
    }

    // ════════════════════════════════════════════════════════
    //  Heurísticas — sin IA, en Java puro
    // ════════════════════════════════════════════════════════
    private Evento.TipoEvento detectarTipo(String p) {
        if (p.contains("médic") || p.contains("medic") || p.contains("doctor")
         || p.contains("hospital") || p.contains("consulta"))   return Evento.TipoEvento.CITA_MEDICA;
        if (p.contains("cumple"))                               return Evento.TipoEvento.CUMPLEANOS;
        if (p.contains("tutor"))                                return Evento.TipoEvento.TUTORIA;
        if (p.contains("claustro"))                             return Evento.TipoEvento.CLAUSTRO;
        if (p.contains("curso") || p.contains("formaci"))       return Evento.TipoEvento.CURSO;
        if (p.contains("extraescolar"))                         return Evento.TipoEvento.EXTRAESCOLAR;
        if (p.contains("viaj"))                                 return Evento.TipoEvento.VIAJE;
        if (p.contains("vacacion"))                             return Evento.TipoEvento.VACACIONES;
        if (p.contains("comida") || p.contains("comer")
         || p.contains("cena") || p.contains("almuerz"))        return Evento.TipoEvento.COMIDA;
        if (p.contains("compra"))                               return Evento.TipoEvento.COMPRAS;
        return null;
    }

    private int detectarRangoDias(String p) {
        if (p.contains("hoy"))                                   return 1;
        if (p.contains("mañana"))                                return 2;
        if (p.contains("esta semana") || p.contains("semana"))   return 7;
        if (p.contains("quincena") || p.contains("15 días"))     return 15;
        if (p.contains("este mes") || p.contains("mes"))         return RANGO_DIAS_BASE;
        if (p.contains("trimestre") || p.contains("3 meses"))    return RANGO_DIAS_AMPLIO;
        if (p.contains("año") || p.contains("anual"))            return 365;
        return RANGO_DIAS_BASE;
    }
}

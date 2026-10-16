package com.miempresa.fasi.ia;

import com.miempresa.fasi.calendario.Evento;
import com.miempresa.fasi.calendario.EventoRepository;
import com.miempresa.fasi.contexto.UsuarioActualService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class IaContextBuilder {

    private final EventoRepository eventoRepo;
    private final UsuarioActualService usuarioActualService;

    private static final int MAX_EVENTOS_CTX   = 15;
    private static final int RANGO_DIAS_BASE   = 30;
    private static final int RANGO_DIAS_AMPLIO = 90;

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

    private String buildContextoFiltrado(String pregunta) {
        String p = pregunta.toLowerCase();
        LocalDate hoy = LocalDate.now();
        UUID usuarioId = usuarioActualService.obtenerUsuarioActual().getId();

        Evento.TipoEvento tipoFiltro = detectarTipo(p);
        int dias = detectarRangoDias(p);
        LocalDate fin = hoy.plusDays(dias);

        List<Evento> eventos;
        if (tipoFiltro != null) {
            eventos = eventoRepo.findByUsuarioIdAndTipoOrderByFechaInicioAsc(usuarioId, tipoFiltro).stream()
                    .filter(e -> !e.getFechaInicio().isBefore(hoy)
                              && !e.getFechaInicio().isAfter(fin))
                    .limit(MAX_EVENTOS_CTX)
                    .collect(Collectors.toList());
        } else {
            eventos = eventoRepo
                    .findByRango(usuarioId, hoy, fin)
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
            eventos.forEach(e -> {
                ctx.append("- ").append(e.getFechaInicio())
                   .append(e.getHoraInicio() != null ? " " + e.getHoraInicio() : "")
                   .append(" | ").append(e.getTipo().getLabel())
                   .append(" | ").append(e.getTitulo())
                   .append(e.getLugar() != null ? " (" + e.getLugar() + ")" : "")
                   .append(e.getPersonasRelacionadas() != null
                           ? " · " + e.getPersonasRelacionadas() : "");
                if (e.getEtiquetas() != null && !e.getEtiquetas().isEmpty()) {
                    ctx.append(" #").append(String.join(" #", e.getEtiquetas()));
                }
                ctx.append("\n");
            });
        }
        return ctx.toString();
    }

    private Evento.TipoEvento detectarTipo(String p) {
        if (p.contains("médic") || p.contains("medic") || p.contains("doctor")
         || p.contains("hospital") || p.contains("consulta") || p.contains("dentist")
         || p.contains("fisio") || p.contains("gym") || p.contains("gimnasio")
         || p.contains("terap"))                                  return Evento.TipoEvento.SALUD;

        if (p.contains("cumple") || p.contains("comida") || p.contains("comer")
         || p.contains("cena") || p.contains("almuerz") || p.contains("fiesta")
         || p.contains("quedad") || p.contains("amig"))           return Evento.TipoEvento.SOCIAL;

        if (p.contains("claustro") || p.contains("reuni")
         || p.contains("trabajo") || p.contains("oficina"))       return Evento.TipoEvento.TRABAJO;

        if (p.contains("curso") || p.contains("formaci") || p.contains("clase")
         || p.contains("estudi") || p.contains("taller")
         || p.contains("charla"))                                 return Evento.TipoEvento.APRENDIZAJE;

        if (p.contains("tutor") || p.contains("extraescolar")
         || p.contains("hijo") || p.contains("hija")
         || p.contains("niñ") || p.contains("famil")
         || p.contains("colegio"))                                return Evento.TipoEvento.FAMILIA;

        if (p.contains("viaj") || p.contains("vacacion") || p.contains("vuelo")
         || p.contains("hotel"))                                  return Evento.TipoEvento.VIAJE;

        if (p.contains("compra") || p.contains("trámit") || p.contains("tramit")
         || p.contains("banco") || p.contains("recad")
         || p.contains("itv"))                                    return Evento.TipoEvento.GESTION;

        if (p.contains("hobby") || p.contains("deporte") || p.contains("museo")
         || p.contains("cine") || p.contains("teatro")
         || p.contains("concierto"))                              return Evento.TipoEvento.OCIO;

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

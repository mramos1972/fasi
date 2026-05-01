#!/bin/bash
# ============================================================
#  FASI — Migración a FASI Calendario
#  Ejecutar desde la raíz del proyecto
# ============================================================

set -e
echo "🚀 Iniciando migración FASI → FASI Calendario..."

# ── 1. ETIQUETA DE SEGURIDAD ─────────────────────────────────
echo "🏷️  Creando tag de seguridad en git..."
git add -A
git commit -m "chore: snapshot antes de migración a calendario" 2>/dev/null || true
git tag -a v1-modular -m "FASI v1 — versión modular completa antes de refactor calendario" 2>/dev/null || echo "⚠️  Tag ya existe, continuando..."

# ── 2. ELIMINAR MÓDULOS ANTIGUOS ─────────────────────────────
echo "🗑️  Eliminando módulos antiguos..."

# Java — paquetes a eliminar
rm -rf src/main/java/com/miempresa/fasi/agenda
rm -rf src/main/java/com/miempresa/fasi/tareas
rm -rf src/main/java/com/miempresa/fasi/contabilidad
rm -rf src/main/java/com/miempresa/fasi/documentos
rm -rf src/main/java/com/miempresa/fasi/fotos
rm -rf src/main/java/com/miempresa/fasi/desarrollo

# Templates — carpetas a eliminar
rm -rf src/main/resources/templates/agenda
rm -rf src/main/resources/templates/tareas
rm -rf src/main/resources/templates/contabilidad
rm -rf src/main/resources/templates/documentos
rm -rf src/main/resources/templates/fotos
rm -rf src/main/resources/templates/desarrollo

echo "✅ Módulos antiguos eliminados"

# ── 3. CREAR ESTRUCTURA DE DIRECTORIOS NUEVA ─────────────────
echo "📁 Creando estructura nueva..."

mkdir -p src/main/java/com/miempresa/fasi/calendario
mkdir -p src/main/resources/templates/calendario

echo "✅ Estructura creada"

# ── 4. MIGRACIÓN FLYWAY — V8 calendario ──────────────────────
echo "🗄️  Creando migración V8 (tabla eventos)..."

cat > src/main/resources/db/migration/V8__calendario.sql << 'SQLEOF'
-- ============================================================
--  FASI Calendario — V8
--  Tabla principal de eventos con búsqueda full-text
-- ============================================================

-- Extensión para búsqueda full-text en español
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TABLE eventos (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Campos principales
    tipo                  VARCHAR(30)   NOT NULL,
    titulo                VARCHAR(300)  NOT NULL,

    -- Fecha y hora
    fecha_inicio          DATE          NOT NULL,
    fecha_fin             DATE,
    hora_inicio           TIME,
    hora_fin              TIME,
    todo_el_dia           BOOLEAN       NOT NULL DEFAULT false,

    -- Localización y personas
    lugar                 VARCHAR(300),
    personas_relacionadas VARCHAR(500),

    -- Contenido
    descripcion           TEXT,
    recordatorio_minutos  INTEGER,

    -- Campos específicos por tipo (JSONB — flexible y rápido)
    datos_extra           JSONB         DEFAULT '{}',

    -- Auditoría de voz
    texto_original_voz    TEXT,

    -- Búsqueda full-text (columna generada automáticamente)
    texto_busqueda        TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('spanish',
            unaccent(coalesce(titulo, ''))           || ' ' ||
            unaccent(coalesce(lugar, ''))            || ' ' ||
            unaccent(coalesce(descripcion, ''))      || ' ' ||
            unaccent(coalesce(personas_relacionadas, '')) || ' ' ||
            unaccent(coalesce(tipo, ''))
        )
    ) STORED,

    -- Timestamps
    created_at            TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT now()
);

-- ── Índices ──────────────────────────────────────────────────
-- Full-text search (GIN — el más rápido para texto)
CREATE INDEX idx_eventos_busqueda   ON eventos USING GIN(texto_busqueda);

-- Consultas por fecha (el más usado en un calendario)
CREATE INDEX idx_eventos_fecha      ON eventos(fecha_inicio);
CREATE INDEX idx_eventos_fecha_rng  ON eventos(fecha_inicio, fecha_fin);

-- Filtros por tipo
CREATE INDEX idx_eventos_tipo       ON eventos(tipo);

-- Consultas JSONB (para campos específicos por tipo)
CREATE INDEX idx_eventos_jsonb      ON eventos USING GIN(datos_extra);

-- ── Comentarios de documentación ─────────────────────────────
COMMENT ON TABLE eventos IS 'Eventos del calendario inteligente FASI';
COMMENT ON COLUMN eventos.tipo IS 'CUMPLEANOS|CITA_MEDICA|TUTORIA|CLAUSTRO|CURSO|EXTRAESCOLAR|VIAJE|VACACIONES|COMIDA|COMPRAS|OTRO';
COMMENT ON COLUMN eventos.datos_extra IS 'Campos específicos por tipo en formato JSONB';
COMMENT ON COLUMN eventos.texto_original_voz IS 'Texto dictado por voz tal como lo reconoció el navegador';
COMMENT ON COLUMN eventos.texto_busqueda IS 'Vector full-text generado automáticamente para búsqueda rápida';
SQLEOF

echo "✅ Migración V8 creada"

# ── 5. ENTITY: Evento.java ────────────────────────────────────
echo "☕ Creando Evento.java..."

cat > src/main/java/com/miempresa/fasi/calendario/Evento.java << 'JAVAEOF'
package com.miempresa.fasi.calendario;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

@Entity
@Table(name = "eventos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Evento extends BaseEntity {

    // ── Tipo de evento ───────────────────────────────────────
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TipoEvento tipo;

    // ── Título generado por IA o escrito por el usuario ──────
    @NotBlank
    @Column(nullable = false, length = 300)
    private String titulo;

    // ── Fecha y hora ─────────────────────────────────────────
    @NotNull
    @Column(name = "fecha_inicio", nullable = false)
    private LocalDate fechaInicio;

    @Column(name = "fecha_fin")
    private LocalDate fechaFin;

    @Column(name = "hora_inicio")
    private LocalTime horaInicio;

    @Column(name = "hora_fin")
    private LocalTime horaFin;

    @Column(name = "todo_el_dia", nullable = false)
    private boolean todoDia = false;

    // ── Localización y personas ──────────────────────────────
    @Column(length = 300)
    private String lugar;

    @Column(name = "personas_relacionadas", length = 500)
    private String personasRelacionadas;

    // ── Contenido ────────────────────────────────────────────
    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "recordatorio_minutos")
    private Integer recordatorioMinutos;

    // ── Datos específicos por tipo (JSONB) ───────────────────
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datos_extra", columnDefinition = "jsonb")
    private Map<String, Object> datosExtra;

    // ── Auditoría de voz ─────────────────────────────────────
    @Column(name = "texto_original_voz", columnDefinition = "TEXT")
    private String textoOriginalVoz;

    // ── Enum de tipos ────────────────────────────────────────
    public enum TipoEvento {
        CUMPLEANOS,
        CITA_MEDICA,
        TUTORIA,
        CLAUSTRO,
        CURSO,
        EXTRAESCOLAR,
        VIAJE,
        VACACIONES,
        COMIDA,
        COMPRAS,
        OTRO;

        public String getEmoji() {
            return switch (this) {
                case CUMPLEANOS   -> "🎂";
                case CITA_MEDICA  -> "🏥";
                case TUTORIA      -> "📚";
                case CLAUSTRO     -> "🏫";
                case CURSO        -> "📖";
                case EXTRAESCOLAR -> "🎭";
                case VIAJE        -> "✈️";
                case VACACIONES   -> "🏖️";
                case COMIDA       -> "🍽️";
                case COMPRAS      -> "🛒";
                case OTRO         -> "⚙️";
            };
        }

        public String getColor() {
            return switch (this) {
                case CUMPLEANOS   -> "#f59e0b"; // amber
                case CITA_MEDICA  -> "#ef4444"; // red
                case TUTORIA      -> "#3b82f6"; // blue
                case CLAUSTRO     -> "#8b5cf6"; // violet
                case CURSO        -> "#06b6d4"; // cyan
                case EXTRAESCOLAR -> "#ec4899"; // pink
                case VIAJE        -> "#10b981"; // emerald
                case VACACIONES   -> "#f97316"; // orange
                case COMIDA       -> "#84cc16"; // lime
                case COMPRAS      -> "#6366f1"; // indigo
                case OTRO         -> "#6b7280"; // gray
            };
        }

        public String getLabel() {
            return switch (this) {
                case CUMPLEANOS   -> "Cumpleaños";
                case CITA_MEDICA  -> "Cita Médica";
                case TUTORIA      -> "Tutoría";
                case CLAUSTRO     -> "Claustro";
                case CURSO        -> "Curso";
                case EXTRAESCOLAR -> "Extraescolar";
                case VIAJE        -> "Viaje";
                case VACACIONES   -> "Vacaciones";
                case COMIDA       -> "Comida";
                case COMPRAS      -> "Compras";
                case OTRO         -> "Otro";
            };
        }
    }
}
JAVAEOF

echo "✅ Evento.java creado"

# ── 6. REPOSITORY ─────────────────────────────────────────────
echo "☕ Creando EventoRepository.java..."

cat > src/main/java/com/miempresa/fasi/calendario/EventoRepository.java << 'JAVAEOF'
package com.miempresa.fasi.calendario;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface EventoRepository extends JpaRepository<Evento, UUID> {

    // ── Consultas por rango de fechas (para la vista mensual/semanal) ──
    List<Evento> findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(
            LocalDate desde, LocalDate hasta);

    // ── Consultas por tipo ──────────────────────────────────
    List<Evento> findByTipoOrderByFechaInicioAsc(Evento.TipoEvento tipo);

    // ── Búsqueda full-text con PostgreSQL tsvector ──────────
    @Query(value = """
        SELECT * FROM eventos
        WHERE texto_busqueda @@ plainto_tsquery('spanish', unaccent(:query))
        ORDER BY fecha_inicio DESC
        LIMIT 50
        """, nativeQuery = true)
    List<Evento> buscarFullText(@Param("query") String query);

    // ── Próximos eventos (para widget de dashboard) ─────────
    @Query("SELECT e FROM Evento e WHERE e.fechaInicio >= :hoy ORDER BY e.fechaInicio ASC, e.horaInicio ASC")
    List<Evento> findProximos(@Param("hoy") LocalDate hoy);

    // ── Eventos de un día concreto ──────────────────────────
    List<Evento> findByFechaInicioOrderByHoraInicioAsc(LocalDate fecha);
}
JAVAEOF

echo "✅ EventoRepository.java creado"

# ── 7. SERVICE ────────────────────────────────────────────────
echo "☕ Creando EventoService.java..."

cat > src/main/java/com/miempresa/fasi/calendario/EventoService.java << 'JAVAEOF'
package com.miempresa.fasi.calendario;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventoService {

    private final EventoRepository repo;

    // ── Lectura ──────────────────────────────────────────────

    public List<Evento> findByMes(int year, int month) {
        LocalDate inicio = LocalDate.of(year, month, 1);
        LocalDate fin    = inicio.withDayOfMonth(inicio.lengthOfMonth());
        return repo.findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(inicio, fin);
    }

    public List<Evento> findBySemana(LocalDate lunesSemana) {
        return repo.findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(
                lunesSemana, lunesSemana.plusDays(6));
    }

    public List<Evento> findByDia(LocalDate fecha) {
        return repo.findByFechaInicioOrderByHoraInicioAsc(fecha);
    }

    public List<Evento> buscar(String query) {
        if (query == null || query.isBlank()) return List.of();
        return repo.buscarFullText(query.trim());
    }

    public List<Evento> findProximos(int limite) {
        return repo.findProximos(LocalDate.now())
                   .stream().limit(limite).toList();
    }

    public Evento findById(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Evento no encontrado: " + id));
    }

    // ── Escritura ────────────────────────────────────────────

    @Transactional
    public Evento save(Evento e) {
        return repo.save(e);
    }

    @Transactional
    public Evento update(UUID id, Evento datos) {
        Evento e = findById(id);
        e.setTipo(datos.getTipo());
        e.setTitulo(datos.getTitulo());
        e.setFechaInicio(datos.getFechaInicio());
        e.setFechaFin(datos.getFechaFin());
        e.setHoraInicio(datos.getHoraInicio());
        e.setHoraFin(datos.getHoraFin());
        e.setTodoDia(datos.isTodoDia());
        e.setLugar(datos.getLugar());
        e.setPersonasRelacionadas(datos.getPersonasRelacionadas());
        e.setDescripcion(datos.getDescripcion());
        e.setRecordatorioMinutos(datos.getRecordatorioMinutos());
        e.setDatosExtra(datos.getDatosExtra());
        return repo.save(e);
    }

    @Transactional
    public void delete(UUID id) {
        repo.deleteById(id);
    }
}
JAVAEOF

echo "✅ EventoService.java creado"

# ── 8. DTO para interpretación IA ─────────────────────────────
echo "☕ Creando EventoIaRequest.java y EventoIaResponse.java..."

cat > src/main/java/com/miempresa/fasi/calendario/EventoIaRequest.java << 'JAVAEOF'
package com.miempresa.fasi.calendario;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EventoIaRequest {
    @NotBlank
    private String texto;          // texto dictado por voz o escrito
    private String fechaContexto;  // fecha actual (para resolver "el jueves")
}
JAVAEOF

cat > src/main/java/com/miempresa/fasi/calendario/EventoIaResponse.java << 'JAVAEOF'
package com.miempresa.fasi.calendario;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventoIaResponse {
    private boolean exito;
    private String  mensajeError;
    private Evento  eventoPreview;  // evento parseado, pendiente de confirmación
    private long    tiempoMs;
}
JAVAEOF

echo "✅ DTOs IA creados"

# ── 9. CONTROLLER ─────────────────────────────────────────────
echo "☕ Creando CalendarioController.java..."

cat > src/main/java/com/miempresa/fasi/calendario/CalendarioController.java << 'JAVAEOF'
package com.miempresa.fasi.calendario;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/calendario")
@RequiredArgsConstructor
public class CalendarioController {

    private final EventoService    eventoService;
    private final EventoIaService  eventoIaService;

    // ── Vista principal (mes actual por defecto) ─────────────
    @GetMapping
    public String vistaCalendario(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String vista,
            Model model) {

        LocalDate hoy     = LocalDate.now();
        int       anio    = year  != null ? year  : hoy.getYear();
        int       mes     = month != null ? month : hoy.getMonthValue();
        String    vistaSel = vista != null ? vista : "mes";

        YearMonth ym      = YearMonth.of(anio, mes);

        model.addAttribute("eventos",     eventoService.findByMes(anio, mes));
        model.addAttribute("proximos",    eventoService.findProximos(5));
        model.addAttribute("tipos",       Evento.TipoEvento.values());
        model.addAttribute("yearMonth",   ym);
        model.addAttribute("hoy",         hoy);
        model.addAttribute("vista",       vistaSel);
        model.addAttribute("nuevoEvento", new Evento());

        return "calendario/index";
    }

    // ── Vista de un día concreto ─────────────────────────────
    @GetMapping("/dia/{fecha}")
    public String viaDia(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                         LocalDate fecha, Model model) {
        model.addAttribute("fecha",   fecha);
        model.addAttribute("eventos", eventoService.findByDia(fecha));
        model.addAttribute("tipos",   Evento.TipoEvento.values());
        return "calendario/dia";
    }

    // ── Formulario nuevo evento (manual) ─────────────────────
    @GetMapping("/nuevo")
    public String nuevoForm(@RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
                            Model model) {
        Evento e = new Evento();
        e.setFechaInicio(fecha != null ? fecha : LocalDate.now());
        model.addAttribute("evento", e);
        model.addAttribute("tipos",  Evento.TipoEvento.values());
        return "calendario/form";
    }

    // ── Formulario editar evento ─────────────────────────────
    @GetMapping("/{id}/editar")
    public String editarForm(@PathVariable UUID id, Model model) {
        model.addAttribute("evento", eventoService.findById(id));
        model.addAttribute("tipos",  Evento.TipoEvento.values());
        return "calendario/form";
    }

    // ── Guardar nuevo evento ─────────────────────────────────
    @PostMapping
    public String guardar(@Valid @ModelAttribute Evento evento) {
        eventoService.save(evento);
        return "redirect:/calendario?year=" + evento.getFechaInicio().getYear()
                + "&month=" + evento.getFechaInicio().getMonthValue();
    }

    // ── Actualizar evento ────────────────────────────────────
    @PostMapping("/{id}")
    public String actualizar(@PathVariable UUID id,
                             @Valid @ModelAttribute Evento evento) {
        eventoService.update(id, evento);
        return "redirect:/calendario?year=" + evento.getFechaInicio().getYear()
                + "&month=" + evento.getFechaInicio().getMonthValue();
    }

    // ── Eliminar evento ──────────────────────────────────────
    @PostMapping("/{id}/eliminar")
    public String eliminar(@PathVariable UUID id) {
        eventoService.delete(id);
        return "redirect:/calendario";
    }

    // ── API: Buscar eventos (buscador full-text) ─────────────
    @GetMapping("/api/buscar")
    @ResponseBody
    public ResponseEntity<List<Evento>> buscar(@RequestParam String q) {
        return ResponseEntity.ok(eventoService.buscar(q));
    }

    // ── API: Interpretar texto con IA → preview evento ───────
    @PostMapping("/api/interpretar")
    @ResponseBody
    public ResponseEntity<EventoIaResponse> interpretarVoz(
            @Valid @RequestBody EventoIaRequest request) {
        return ResponseEntity.ok(eventoIaService.interpretar(request));
    }

    // ── API: Confirmar y guardar evento interpretado por IA ──
    @PostMapping("/api/confirmar")
    @ResponseBody
    public ResponseEntity<Evento> confirmarEvento(@RequestBody Evento evento) {
        return ResponseEntity.ok(eventoService.save(evento));
    }

    // ── API: Consulta IA sobre el calendario ─────────────────
    @PostMapping("/api/consulta")
    @ResponseBody
    public ResponseEntity<String> consultaIa(@RequestBody String pregunta) {
        return ResponseEntity.ok(eventoIaService.consultar(pregunta));
    }

    // ── API: Eventos del mes (para renderizado dinámico) ─────
    @GetMapping("/api/mes")
    @ResponseBody
    public ResponseEntity<List<Evento>> eventosMes(
            @RequestParam int year, @RequestParam int month) {
        return ResponseEntity.ok(eventoService.findByMes(year, month));
    }
}
JAVAEOF

echo "✅ CalendarioController.java creado"

# ── 10. IA SERVICE para calendario ────────────────────────────
echo "☕ Creando EventoIaService.java..."

cat > src/main/java/com/miempresa/fasi/calendario/EventoIaService.java << 'JAVAEOF'
package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miempresa.fasi.ia.OllamaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventoIaService {

    private final OllamaClient      ollamaClient;
    private final EventoRepository  repo;
    private final ObjectMapper      objectMapper;

    // ── Interpretar texto de voz → Evento (preview) ──────────
    public EventoIaResponse interpretar(EventoIaRequest request) {
        long inicio = System.currentTimeMillis();
        try {
            String prompt = buildPromptInterpretar(request);
            String json   = ollamaClient.generate(prompt);

            // Extraer el JSON de la respuesta (la IA puede añadir texto extra)
            String jsonLimpio = extraerJson(json);
            Evento evento     = objectMapper.readValue(jsonLimpio, Evento.class);
            evento.setTextoOriginalVoz(request.getTexto());

            return new EventoIaResponse(true, null, evento,
                    System.currentTimeMillis() - inicio);

        } catch (Exception e) {
            log.error("Error interpretando texto de voz", e);
            return new EventoIaResponse(false,
                    "No pude interpretar el texto. Inténtalo de nuevo o crea el evento manualmente.",
                    null, System.currentTimeMillis() - inicio);
        }
    }

    // ── Consulta en lenguaje natural sobre el calendario ─────
    @Transactional(readOnly = true)
    public String consultar(String pregunta) {
        String contexto = buildContextoCalendario();
        String prompt   = buildPromptConsulta(pregunta, contexto);
        return ollamaClient.generate(prompt);
    }

    // ── Prompt para interpretar voz → JSON evento ────────────
    private String buildPromptInterpretar(EventoIaRequest req) {
        String fechaHoy = req.getFechaContexto() != null
                ? req.getFechaContexto()
                : LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        return """
            Eres un asistente de calendario inteligente. Tu tarea es convertir texto en lenguaje natural a un evento de calendario en formato JSON.
            
            FECHA DE HOY: %s
            
            TIPOS DE EVENTO DISPONIBLES (usa exactamente estos valores):
            CUMPLEANOS, CITA_MEDICA, TUTORIA, CLAUSTRO, CURSO, EXTRAESCOLAR, VIAJE, VACACIONES, COMIDA, COMPRAS, OTRO
            
            REGLAS ESTRICTAS:
            1. Responde ÚNICAMENTE con el JSON, sin texto adicional, sin markdown, sin explicaciones.
            2. Las fechas deben estar en formato ISO: YYYY-MM-DD
            3. Las horas en formato HH:mm:ss
            4. Si no se menciona hora, deja horaInicio como null
            5. Si no se menciona lugar, deja lugar como null
            6. Infiere el tipo de evento del contexto
            7. El título debe ser descriptivo y conciso (máximo 60 caracteres)
            8. En personasRelacionadas pon los nombres mencionados separados por coma
            9. Si hay cancelación o cambio, refléjalo en la descripción
            10. datosExtra debe contener campos específicos del tipo de evento
            
            EJEMPLOS DE datosExtra por tipo:
            - CITA_MEDICA: {"especialidad":"cabecera","medico":null,"centro":"consultas externas","paciente":"Rocio"}
            - CUMPLEANOS: {"nombre_persona":"Alberto de Martín","lugar_celebracion":"Costaballena"}
            - EXTRAESCOLAR: {"actividad":"teatro","alumno":"Alicia","motivo_cancelacion":"tiene que estudiar"}
            
            TEXTO A INTERPRETAR: "%s"
            
            JSON DE RESPUESTA (solo el JSON, nada más):
            """.formatted(fechaHoy, req.getTexto());
    }

    // ── Prompt para consultas sobre el calendario ─────────────
    private String buildPromptConsulta(String pregunta, String contexto) {
        return """
            Eres FASI-IA, asistente inteligente del calendario FASI.
            Tienes acceso a los eventos REALES del calendario que se muestran a continuación.
            
            REGLAS:
            1. Responde ÚNICAMENTE con datos del contexto proporcionado.
            2. Si un dato no está, responde: 'No tengo ese dato en el calendario.'
            3. NUNCA inventes información.
            4. Responde en español, de forma clara y estructurada.
            5. Usa emojis con moderación.
            
            %s
            
            PREGUNTA: %s
            
            RESPUESTA:
            """.formatted(contexto, pregunta);
    }

    // ── Construir contexto del calendario para consultas ──────
    private String buildContextoCalendario() {
        StringBuilder ctx = new StringBuilder("=== EVENTOS DEL CALENDARIO ===\n\n");

        LocalDate hoy    = LocalDate.now();
        LocalDate fin    = hoy.plusMonths(3);

        List<Evento> proximos = repo
                .findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(hoy, fin);

        if (proximos.isEmpty()) {
            ctx.append("(No hay eventos próximos registrados)\n");
        } else {
            ctx.append("Próximos 3 meses:\n");
            proximos.forEach(e -> ctx.append(String.format(
                    "  [%s] %s %s | %s%s%s\n",
                    e.getFechaInicio(),
                    e.getTipo().getEmoji(),
                    e.getTitulo(),
                    e.getHoraInicio() != null ? "a las " + e.getHoraInicio() + " " : "",
                    e.getLugar() != null ? "en " + e.getLugar() + " " : "",
                    e.getPersonasRelacionadas() != null ? "· " + e.getPersonasRelacionadas() : ""
            )));
        }

        // Resumen por tipo
        ctx.append("\nRESUMEN POR TIPO (total histórico):\n");
        for (Evento.TipoEvento tipo : Evento.TipoEvento.values()) {
            long count = repo.findByTipoOrderByFechaInicioAsc(tipo).size();
            if (count > 0) {
                ctx.append(String.format("  %s %s: %d eventos\n",
                        tipo.getEmoji(), tipo.getLabel(), count));
            }
        }

        return ctx.toString();
    }

    // ── Extraer JSON de la respuesta de la IA ────────────────
    private String extraerJson(String respuesta) {
        // Buscar primer { y último }
        int inicio = respuesta.indexOf('{');
        int fin    = respuesta.lastIndexOf('}');
        if (inicio >= 0 && fin > inicio) {
            return respuesta.substring(inicio, fin + 1);
        }
        return respuesta.trim();
    }
}
JAVAEOF

echo "✅ EventoIaService.java creado"

# ── 11. ACTUALIZAR HomeController ─────────────────────────────
echo "☕ Actualizando HomeController.java..."

cat > src/main/java/com/miempresa/fasi/HomeController.java << 'JAVAEOF'
package com.miempresa.fasi;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "redirect:/calendario";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
JAVAEOF

echo "✅ HomeController.java actualizado"

# ── 12. ACTUALIZAR SecurityConfig (añadir /calendario/api/**) ─
echo "☕ Actualizando SecurityConfig.java..."

cat > src/main/java/com/miempresa/fasi/config/SecurityConfig.java << 'JAVAEOF'
package com.miempresa.fasi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated()
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/ia/api/**", "/calendario/api/**")
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/calendario", true)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        var admin = User.builder()
            .username("admin")
            .password(encoder.encode("admin"))
            .roles("ADMIN")
            .build();
        return new InMemoryUserDetailsManager(admin);
    }
}
JAVAEOF

echo "✅ SecurityConfig.java actualizado"

# ── 13. TEMPLATES ─────────────────────────────────────────────
echo "🎨 Creando templates Thymeleaf..."

# ── index.html (vista principal calendario) ───────────────────
cat > src/main/resources/templates/calendario/index.html << 'HTMLEOF'
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/base :: layout(~{::title}, ~{::section})}">
<head><title>Calendario — FASI</title></head>
<body>
<section>

  <!-- CABECERA -->
  <div class="flex flex-wrap items-center justify-between gap-3 mb-4">
    <div class="flex items-center gap-3">
      <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100">📅 Calendario</h2>
      <!-- Navegación mes -->
      <a th:href="@{/calendario(year=${yearMonth.minusMonths(1).year},month=${yearMonth.minusMonths(1).monthValue})}"
         class="p-2 rounded-lg hover:bg-gray-200 dark:hover:bg-gray-700 transition">‹</a>
      <span class="font-semibold text-gray-700 dark:text-gray-200"
            th:text="${#temporals.format(yearMonth.atDay(1),'MMMM yyyy')}"></span>
      <a th:href="@{/calendario(year=${yearMonth.plusMonths(1).year},month=${yearMonth.plusMonths(1).monthValue})}"
         class="p-2 rounded-lg hover:bg-gray-200 dark:hover:bg-gray-700 transition">›</a>
      <a th:href="@{/calendario}"
         class="text-xs px-3 py-1 bg-gray-200 dark:bg-gray-700 rounded-full hover:bg-gray-300 transition">Hoy</a>
    </div>
    <div class="flex gap-2">
      <!-- Botón nueva entrada por voz -->
      <button onclick="abrirModalVoz()"
              class="flex items-center gap-2 bg-red-500 hover:bg-red-600 text-white px-4 py-2 rounded-lg transition text-sm font-semibold">
        🎤 Voz
      </button>
      <!-- Botón nuevo evento manual -->
      <a th:href="@{/calendario/nuevo}"
         class="flex items-center gap-2 bg-gray-900 hover:bg-gray-700 text-white px-4 py-2 rounded-lg transition text-sm font-semibold">
        + Nuevo evento
      </a>
    </div>
  </div>

  <!-- BUSCADOR -->
  <div class="mb-4 flex gap-2">
    <input type="text" id="buscador-input" placeholder="🔍 Buscar eventos... (Rocio, médico, teatro...)"
           class="flex-1 border border-gray-300 dark:border-gray-600 rounded-lg px-4 py-2 text-sm
                  bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                  focus:outline-none focus:ring-2 focus:ring-indigo-500"/>
    <button onclick="buscarEventos()"
            class="bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm hover:bg-indigo-700 transition">
      Buscar
    </button>
    <button onclick="limpiarBusqueda()"
            class="border border-gray-300 px-4 py-2 rounded-lg text-sm hover:bg-gray-50 dark:hover:bg-gray-700 transition">
      Limpiar
    </button>
  </div>

  <!-- RESULTADOS BÚSQUEDA (ocultos por defecto) -->
  <div id="resultados-busqueda" class="hidden mb-4 bg-white dark:bg-gray-800 rounded-xl shadow p-4">
    <h3 class="font-semibold text-gray-700 dark:text-gray-200 mb-3">Resultados de búsqueda:</h3>
    <div id="lista-resultados" class="space-y-2"></div>
  </div>

  <!-- GRID CALENDARIO MENSUAL -->
  <div class="bg-white dark:bg-gray-800 rounded-xl shadow overflow-hidden">
    <!-- Cabecera días semana -->
    <div class="grid grid-cols-7 bg-gray-50 dark:bg-gray-700">
      <div th:each="dia : ${ {'Lun','Mar','Mié','Jue','Vie','Sáb','Dom'} }"
           class="px-2 py-3 text-center text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase"
           th:text="${dia}"></div>
    </div>
    <!-- Celdas del mes -->
    <div id="grid-calendario" class="grid grid-cols-7 border-t border-gray-100 dark:border-gray-700">
      <!-- Renderizado por JavaScript -->
    </div>
  </div>

  <!-- PRÓXIMOS EVENTOS -->
  <div class="mt-6">
    <h3 class="font-semibold text-gray-700 dark:text-gray-200 mb-3">📌 Próximos eventos</h3>
    <div class="space-y-2">
      <div th:each="e : ${proximos}"
           class="flex items-center gap-3 bg-white dark:bg-gray-800 rounded-lg shadow px-4 py-3">
        <span class="text-xl" th:text="${e.tipo.emoji}"></span>
        <div class="flex-1">
          <p class="font-medium text-sm text-gray-800 dark:text-gray-100" th:text="${e.titulo}"></p>
          <p class="text-xs text-gray-500"
             th:text="${#temporals.format(e.fechaInicio,'dd/MM/yyyy')}
                      + (${e.horaInicio != null} ? ' · ' + ${e.horaInicio} : '')
                      + (${e.lugar != null} ? ' · ' + ${e.lugar} : '')"></p>
        </div>
        <a th:href="@{/calendario/{id}/editar(id=${e.id})}"
           class="text-blue-500 hover:underline text-xs">Editar</a>
      </div>
      <p th:if="${#lists.isEmpty(proximos)}"
         class="text-gray-400 text-sm text-center py-4">No hay eventos próximos.</p>
    </div>
  </div>

</section>

<!-- MODAL VOZ / IA ──────────────────────────────────────── -->
<div id="modal-voz" class="hidden fixed inset-0 z-50 flex items-center justify-center bg-black/50">
  <div class="bg-white dark:bg-gray-800 rounded-2xl shadow-2xl p-8 w-full max-w-lg mx-4">
    <h3 class="text-xl font-bold text-gray-800 dark:text-gray-100 mb-2">🎤 Nuevo evento por voz o texto</h3>
    <p class="text-sm text-gray-500 mb-4">Habla o escribe de forma natural. Ejemplos:<br/>
      <em>"El martes cumpleaños de Alberto en Costaballena a las 4:30"</em><br/>
      <em>"Cita médica el jueves para Rocío en consultas externas de Jerez"</em>
    </p>

    <!-- Área de texto -->
    <textarea id="voz-texto" rows="3" placeholder="Pulsa el micrófono o escribe aquí..."
              class="w-full border border-gray-300 dark:border-gray-600 rounded-xl px-4 py-3
                     bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                     focus:outline-none focus:ring-2 focus:ring-red-400 text-sm resize-none mb-3"></textarea>

    <!-- Botones -->
    <div class="flex gap-3 mb-4">
      <button id="btn-micro" onclick="toggleMicrofono()"
              class="flex-1 flex items-center justify-center gap-2 bg-red-500 hover:bg-red-600
                     text-white py-2 rounded-xl font-semibold transition">
        🎤 <span id="micro-label">Iniciar grabación</span>
      </button>
      <button onclick="interpretarTexto()"
              class="flex-1 bg-indigo-600 hover:bg-indigo-700 text-white py-2 rounded-xl
                     font-semibold transition">
        ✨ Interpretar con IA
      </button>
    </div>

    <!-- Preview del evento interpretado -->
    <div id="preview-evento" class="hidden bg-gray-50 dark:bg-gray-700 rounded-xl p-4 mb-4">
      <h4 class="font-semibold text-sm text-gray-700 dark:text-gray-200 mb-2">Vista previa del evento:</h4>
      <div id="preview-contenido" class="text-sm space-y-1"></div>
      <div class="flex gap-2 mt-3">
        <button onclick="confirmarEvento()"
                class="flex-1 bg-green-600 hover:bg-green-700 text-white py-2 rounded-lg
                       font-semibold transition text-sm">
          ✅ Confirmar y guardar
        </button>
        <button onclick="editarManual()"
                class="flex-1 border border-gray-300 py-2 rounded-lg text-sm hover:bg-gray-50
                       dark:hover:bg-gray-700 transition">
          ✏️ Editar manualmente
        </button>
      </div>
    </div>

    <!-- Spinner IA -->
    <div id="ia-spinner" class="hidden flex items-center gap-2 text-sm text-gray-500 mb-3">
      <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce"></div>
      <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style="animation-delay:150ms"></div>
      <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style="animation-delay:300ms"></div>
      <span>La IA está interpretando...</span>
    </div>

    <button onclick="cerrarModalVoz()"
            class="w-full text-gray-400 hover:text-gray-600 text-sm mt-1">Cancelar</button>
  </div>
</div>

<script th:inline="javascript">
  // ── Datos del servidor ───────────────────────────────────
  const EVENTOS_MES   = /*[[${eventos}]]*/ [];
  const YEAR_MONTH    = /*[[${yearMonth.toString()}]]*/ '';
  const HOY           = /*[[${hoy.toString()}]]*/ '';

  // ── Renderizar grid del calendario ──────────────────────
  function renderCalendario() {
    const [year, month] = YEAR_MONTH.split('-').map(Number);
    const primerDia     = new Date(year, month - 1, 1);
    const ultimoDia     = new Date(year, month, 0).getDate();
    // Lunes=0 ... Domingo=6
    let diaSemana = primerDia.getDay() - 1;
    if (diaSemana < 0) diaSemana = 6;

    const grid = document.getElementById('grid-calendario');
    grid.innerHTML = '';

    // Celdas vacías iniciales
    for (let i = 0; i < diaSemana; i++) {
      grid.insertAdjacentHTML('beforeend',
        '<div class="min-h-24 border-b border-r border-gray-100 dark:border-gray-700 bg-gray-50 dark:bg-gray-900/20"></div>');
    }

    // Celdas de días
    for (let dia = 1; dia <= ultimoDia; dia++) {
      const fechaStr  = `${year}-${String(month).padStart(2,'0')}-${String(dia).padStart(2,'0')}`;
      const esHoy     = fechaStr === HOY;
      const eventosDia = EVENTOS_MES.filter(e => e.fechaInicio === fechaStr);

      let html = `
        <div class="min-h-24 border-b border-r border-gray-100 dark:border-gray-700 p-1 cursor-pointer
                    hover:bg-indigo-50 dark:hover:bg-indigo-900/20 transition"
             onclick="window.location='/calendario/dia/${fechaStr}'">
          <div class="flex justify-between items-start mb-1">
            <span class="text-sm font-semibold ${esHoy
              ? 'bg-indigo-600 text-white w-6 h-6 rounded-full flex items-center justify-center text-xs'
              : 'text-gray-700 dark:text-gray-300'}">${dia}</span>
            <a href="/calendario/nuevo?fecha=${fechaStr}"
               onclick="event.stopPropagation()"
               class="text-gray-300 hover:text-indigo-500 text-lg leading-none">+</a>
          </div>`;

      eventosDia.slice(0, 3).forEach(e => {
        html += `<div class="text-xs px-1 py-0.5 rounded mb-0.5 truncate text-white font-medium"
                      style="background-color:${e.tipo.color}"
                      title="${e.titulo}">
                   ${e.tipo.emoji} ${e.titulo}
                 </div>`;
      });

      if (eventosDia.length > 3) {
        html += `<div class="text-xs text-gray-400">+${eventosDia.length - 3} más</div>`;
      }

      html += '</div>';
      grid.insertAdjacentHTML('beforeend', html);
    }
  }

  // ── Buscador full-text ───────────────────────────────────
  async function buscarEventos() {
    const q = document.getElementById('buscador-input').value.trim();
    if (!q) return;

    const resp = await fetch(`/calendario/api/buscar?q=${encodeURIComponent(q)}`);
    const data = await resp.json();

    const contenedor = document.getElementById('lista-resultados');
    contenedor.innerHTML = '';

    if (data.length === 0) {
      contenedor.innerHTML = '<p class="text-gray-400 text-sm">No se encontraron eventos.</p>';
    } else {
      data.forEach(e => {
        contenedor.insertAdjacentHTML('beforeend', `
          <div class="flex items-center gap-3 p-2 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-700">
            <span class="text-lg">${e.tipo.emoji}</span>
            <div class="flex-1">
              <p class="font-medium text-sm">${e.titulo}</p>
              <p class="text-xs text-gray-500">${e.fechaInicio}
                 ${e.lugar ? '· ' + e.lugar : ''}
                 ${e.personasRelacionadas ? '· ' + e.personasRelacionadas : ''}</p>
            </div>
            <a href="/calendario/${e.id}/editar" class="text-blue-500 text-xs hover:underline">Ver</a>
          </div>`);
      });
    }

    document.getElementById('resultados-busqueda').classList.remove('hidden');
  }

  function limpiarBusqueda() {
    document.getElementById('buscador-input').value = '';
    document.getElementById('resultados-busqueda').classList.add('hidden');
  }

  document.getElementById('buscador-input').addEventListener('keydown', e => {
    if (e.key === 'Enter') buscarEventos();
  });

  // ── Modal de voz ─────────────────────────────────────────
  let recognition  = null;
  let grabando     = false;
  let eventoActual = null;

  function abrirModalVoz()  { document.getElementById('modal-voz').classList.remove('hidden'); }
  function cerrarModalVoz() {
    document.getElementById('modal-voz').classList.add('hidden');
    document.getElementById('preview-evento').classList.add('hidden');
    document.getElementById('voz-texto').value = '';
    if (grabando) pararMicrofono();
  }

  function toggleMicrofono() {
    grabando ? pararMicrofono() : iniciarMicrofono();
  }

  function iniciarMicrofono() {
    if (!('webkitSpeechRecognition' in window) && !('SpeechRecognition' in window)) {
      alert('Tu navegador no soporta reconocimiento de voz. Usa Chrome.');
      return;
    }
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    recognition = new SR();
    recognition.lang = 'es-ES';
    recognition.continuous = false;
    recognition.interimResults = true;

    recognition.onresult = (event) => {
      const transcript = Array.from(event.results)
        .map(r => r[0].transcript).join('');
      document.getElementById('voz-texto').value = transcript;
    };

    recognition.onend = () => {
      grabando = false;
      document.getElementById('micro-label').textContent = 'Iniciar grabación';
      document.getElementById('btn-micro').classList.remove('bg-red-700');
      document.getElementById('btn-micro').classList.add('bg-red-500');
    };

    recognition.start();
    grabando = true;
    document.getElementById('micro-label').textContent = '⏹ Parar grabación';
    document.getElementById('btn-micro').classList.add('bg-red-700');
    document.getElementById('btn-micro').classList.remove('bg-red-500');
  }

  function pararMicrofono() {
    if (recognition) recognition.stop();
  }

  async function interpretarTexto() {
    const texto = document.getElementById('voz-texto').value.trim();
    if (!texto) return;

    document.getElementById('ia-spinner').classList.remove('hidden');
    document.getElementById('preview-evento').classList.add('hidden');

    try {
      const resp = await fetch('/calendario/api/interpretar', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ texto, fechaContexto: HOY })
      });
      const data = await resp.json();

      if (data.exito && data.eventoPreview) {
        eventoActual = data.eventoPreview;
        mostrarPreview(data.eventoPreview);
      } else {
        alert('❌ ' + (data.mensajeError || 'Error al interpretar'));
      }
    } catch (err) {
      alert('Error de conexión: ' + err.message);
    } finally {
      document.getElementById('ia-spinner').classList.add('hidden');
    }
  }

  function mostrarPreview(evento) {
    const c = document.getElementById('preview-contenido');
    c.innerHTML = `
      <p><strong>Tipo:</strong> ${evento.tipo?.emoji || ''} ${evento.tipo?.label || evento.tipo}</p>
      <p><strong>Título:</strong> ${evento.titulo}</p>
      <p><strong>Fecha:</strong> ${evento.fechaInicio}${evento.horaInicio ? ' a las ' + evento.horaInicio : ''}</p>
      ${evento.lugar ? `<p><strong>Lugar:</strong> ${evento.lugar}</p>` : ''}
      ${evento.personasRelacionadas ? `<p><strong>Personas:</strong> ${evento.personasRelacionadas}</p>` : ''}
      ${evento.descripcion ? `<p><strong>Notas:</strong> ${evento.descripcion}</p>` : ''}
    `;
    document.getElementById('preview-evento').classList.remove('hidden');
  }

  async function confirmarEvento() {
    if (!eventoActual) return;
    try {
      const resp = await fetch('/calendario/api/confirmar', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify(eventoActual)
      });
      if (resp.ok) {
        cerrarModalVoz();
        window.location.reload();
      }
    } catch (err) {
      alert('Error al guardar: ' + err.message);
    }
  }

  function editarManual() {
    if (!eventoActual) return;
    const params = new URLSearchParams({
      fecha: eventoActual.fechaInicio || HOY
    });
    window.location = '/calendario/nuevo?' + params.toString();
  }

  // ── Init ─────────────────────────────────────────────────
  renderCalendario();
</script>
</body>
</html>
HTMLEOF

echo "✅ Template calendario/index.html creado"

# ── form.html ─────────────────────────────────────────────────
cat > src/main/resources/templates/calendario/form.html << 'HTMLEOF'
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/base :: layout(~{::title}, ~{::section})}">
<head><title>Evento — FASI</title></head>
<body>
<section>
  <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100 mb-6"
      th:text="${evento.id == null} ? '📅 Nuevo evento' : '✏️ Editar evento'"></h2>

  <div class="bg-white dark:bg-gray-800 rounded-xl shadow p-8 max-w-2xl">
    <form th:action="${evento.id == null} ? @{/calendario} : @{/calendario/{id}(id=${evento.id})}"
          th:object="${evento}" method="post" class="space-y-5">

      <!-- Tipo de evento -->
      <div>
        <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Tipo de evento *</label>
        <select th:field="*{tipo}" required
                class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2
                       bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100">
          <option value="">— Selecciona un tipo —</option>
          <option th:each="t : ${tipos}" th:value="${t}"
                  th:text="${t.emoji + ' ' + t.label}"></option>
        </select>
      </div>

      <!-- Título -->
      <div>
        <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Título *</label>
        <input type="text" th:field="*{titulo}" required
               class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2
                      bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                      focus:outline-none focus:ring-2 focus:ring-indigo-400"/>
      </div>

      <!-- Fechas y horas -->
      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Fecha inicio *</label>
          <input type="date" th:field="*{fechaInicio}" required
                 class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2
                        bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"/>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Fecha fin</label>
          <input type="date" th:field="*{fechaFin}"
                 class="w-full border border-gray-300 dark:
                        dark:border-gray-600 rounded-lg px-3 py-2
                        bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"/>
        </div>
      </div>

      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Hora inicio</label>
          <input type="time" th:field="*{horaInicio}"
                 class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2
                        bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"/>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Hora fin</label>
          <input type="time" th:field="*{horaFin}"
                 class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2
                        bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100"/>
        </div>
      </div>

      <!-- Todo el día -->
      <div class="flex items-center gap-2">
        <input type="checkbox" th:field="*{todoDia}" id="todoDia"
               class="w-4 h-4 rounded border-gray-300 text-indigo-600"/>
        <label for="todoDia" class="text-sm text-gray-700 dark:text-gray-300">Todo el día</label>
      </div>

      <!-- Lugar y personas -->
      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Lugar</label>
          <input type="text" th:field="*{lugar}"
                 placeholder="Ej: Jerez, consultas externas"
                 class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2
                        bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                        focus:outline-none focus:ring-2 focus:ring-indigo-400"/>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Personas</label>
          <input type="text" th:field="*{personasRelacionadas}"
                 placeholder="Ej: Rocío, Alberto"
                 class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2
                        bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                        focus:outline-none focus:ring-2 focus:ring-indigo-400"/>
        </div>
      </div>

      <!-- Descripción -->
      <div>
        <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Notas / Descripción</label>
        <textarea th:field="*{descripcion}" rows="3"
                  class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2
                         bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                         focus:outline-none focus:ring-2 focus:ring-indigo-400"></textarea>
      </div>

      <!-- Recordatorio -->
      <div>
        <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Recordatorio</label>
        <select th:field="*{recordatorioMinutos}"
                class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2
                       bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100">
          <option value="">Sin recordatorio</option>
          <option value="15">15 minutos antes</option>
          <option value="30">30 minutos antes</option>
          <option value="60">1 hora antes</option>
          <option value="1440">1 día antes</option>
          <option value="10080">1 semana antes</option>
        </select>
      </div>

      <!-- Botones -->
      <div class="flex gap-3 pt-2">
        <button type="submit"
                class="bg-indigo-600 text-white px-6 py-2 rounded-lg hover:bg-indigo-700 transition font-semibold">
          Guardar evento
        </button>
        <a th:href="@{/calendario}"
           class="px-6 py-2 rounded-lg border border-gray-300 hover:bg-gray-50
                  dark:hover:bg-gray-700 transition">
          Cancelar
        </a>
        <!-- Botón eliminar (solo en edición) -->
        <form th:if="${evento.id != null}"
              th:action="@{/calendario/{id}/eliminar(id=${evento.id})}" method="post"
              class="ml-auto" onsubmit="return confirm('¿Eliminar este evento?')">
          <button type="submit"
                  class="px-6 py-2 rounded-lg bg-red-50 text-red-600 hover:bg-red-100 transition">
            Eliminar
          </button>
        </form>
      </div>

    </form>
  </div>
</section>
</body>
</html>
HTMLEOF

echo "✅ Template calendario/form.html creado"

# ── dia.html ──────────────────────────────────────────────────
cat > src/main/resources/templates/calendario/dia.html << 'HTMLEOF'
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/base :: layout(~{::title}, ~{::section})}">
<head><title>Día — FASI</title></head>
<body>
<section>
  <div class="flex items-center justify-between mb-6">
    <div class="flex items-center gap-3">
      <a th:href="@{/calendario(year=${fecha.year},month=${fecha.monthValue})}"
         class="p-2 rounded-lg hover:bg-gray-200 dark:hover:bg-gray-700 transition">‹ Volver</a>
      <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100"
          th:text="${#temporals.format(fecha, 'EEEE, d MMMM yyyy')}"></h2>
    </div>
    <a th:href="@{/calendario/nuevo(fecha=${fecha})}"
       class="bg-indigo-600 text-white px-4 py-2 rounded-lg hover:bg-indigo-700 transition text-sm font-semibold">
      + Nuevo evento
    </a>
  </div>

  <!-- Lista de eventos del día -->
  <div class="space-y-3">
    <div th:each="e : ${eventos}"
         class="bg-white dark:bg-gray-800 rounded-xl shadow p-5 flex gap-4 items-start
                border-l-4"
         th:style="'border-color:' + ${e.tipo.color}">
      <span class="text-3xl" th:text="${e.tipo.emoji}"></span>
      <div class="flex-1">
        <div class="flex items-center gap-2 mb-1">
          <span class="text-xs font-semibold px-2 py-0.5 rounded-full text-white"
                th:style="'background-color:' + ${e.tipo.color}"
                th:text="${e.tipo.label}"></span>
          <span th:if="${e.horaInicio != null}"
                class="text-sm text-gray-500"
                th:text="${e.horaInicio} + (${e.horaFin != null} ? ' – ' + ${e.horaFin} : '')"></span>
        </div>
        <h3 class="font-semibold text-gray-800 dark:text-gray-100 text-lg"
            th:text="${e.titulo}"></h3>
        <p th:if="${e.lugar != null}"
           class="text-sm text-gray-500 mt-1">📍 <span th:text="${e.lugar}"></span></p>
        <p th:if="${e.personasRelacionadas != null}"
           class="text-sm text-gray-500">👥 <span th:text="${e.personasRelacionadas}"></span></p>
        <p th:if="${e.descripcion != null}"
           class="text-sm text-gray-600 dark:text-gray-300 mt-2"
           th:text="${e.descripcion}"></p>
      </div>
      <div class="flex flex-col gap-2">
        <a th:href="@{/calendario/{id}/editar(id=${e.id})}"
           class="text-blue-500 hover:underline text-xs">Editar</a>
        <form th:action="@{/calendario/{id}/eliminar(id=${e.id})}" method="post"
              onsubmit="return confirm('¿Eliminar?')">
          <button type="submit" class="text-red-400 hover:underline text-xs">Eliminar</button>
        </form>
      </div>
    </div>

    <div th:if="${#lists.isEmpty(eventos)}"
         class="bg-white dark:bg-gray-800 rounded-xl shadow p-12 text-center">
      <p class="text-5xl mb-3">📭</p>
      <p class="text-gray-400">No hay eventos para este día.</p>
      <a th:href="@{/calendario/nuevo(fecha=${fecha})}"
         class="inline-block mt-4 bg-indigo-600 text-white px-6 py-2 rounded-lg
                hover:bg-indigo-700 transition text-sm">
        + Añadir evento
      </a>
    </div>
  </div>
</section>
</body>
</html>
HTMLEOF

echo "✅ Template calendario/dia.html creado"

# ── 14. ACTUALIZAR layout/base.html ───────────────────────────
echo "🎨 Actualizando layout/base.html..."

cat > src/main/resources/templates/layout/base.html << 'HTMLEOF'
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security"
      th:fragment="layout(title, content)">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title th:replace="${title}">FASI</title>
  <script th:src="@{/js/tailwind.min.js}"></script>
  <script th:src="@{/js/htmx.min.js}"></script>
  <script>tailwind.config = { darkMode: 'class' };</script>
  <style>
    *, *::before, *::after {
      transition: background-color 0.2s ease, color 0.2s ease, border-color 0.2s ease;
    }
  </style>
</head>
<body class="bg-gray-100 dark:bg-gray-950 min-h-screen">

  <!-- TOPBAR MÓVIL -->
  <header class="lg:hidden bg-gray-900 dark:bg-gray-800 text-white sticky top-0 z-50">
    <div class="flex items-center justify-between px-4 py-3">
      <span class="text-xl font-bold">⚡ FASI</span>
      <div class="flex items-center gap-3">
        <button onclick="toggleDark()" class="text-xl focus:outline-none">
          <span id="dark-icon">🌙</span>
        </button>
        <button id="menu-btn" class="text-2xl focus:outline-none">☰</button>
      </div>
    </div>
    <nav id="mobile-menu"
         class="hidden absolute left-0 right-0 bg-gray-800 dark:bg-gray-900
                px-4 pb-4 space-y-1 shadow-xl z-50">
      <a th:href="@{/calendario}"
         class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">
        📅 <span>Calendario</span>
      </a>
      <a th:href="@{/ia}"
         class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">
        🤖 <span>Asistente IA</span>
      </a>
      <div class="border-t border-gray-700 pt-2 mt-2 text-sm text-gray-400">
        <span sec:authentication="name">usuario</span>
        <form th:action="@{/logout}" method="post" class="inline ml-2">
          <button type="submit" class="text-red-400 hover:text-red-300">salir</button>
        </form>
      </div>
    </nav>
  </header>

  <div id="menu-overlay" class="hidden fixed inset-0 z-40 lg:hidden"
       onclick="cerrarMenu()"></div>

  <div class="flex">
    <!-- SIDEBAR DESKTOP -->
    <aside class="hidden lg:flex lg:flex-col w-64 bg-gray-900 dark:bg-gray-800
                  text-white min-h-screen fixed top-0 left-0 z-40">
      <div class="p-6 border-b border-gray-700 flex items-center justify-between">
        <div>
          <span class="text-2xl font-bold tracking-wide">⚡ FASI</span>
          <p class="text-xs text-gray-400 mt-1">Calendario Inteligente</p>
        </div>
        <button onclick="toggleDark()"
                class="text-2xl focus:outline-none hover:scale-110 transition-transform ml-2">
          <span id="dark-icon-desktop">🌙</span>
        </button>
      </div>
      <nav class="flex-1 p-4 space-y-1">
        <a th:href="@{/calendario}"
           class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">
          📅 <span>Calendario</span>
        </a>
        <a th:href="@{/ia}"
           class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">
          🤖 <span>Asistente IA</span>
        </a>

        <!-- Leyenda de tipos de evento -->
        <div class="pt-4 mt-4 border-t border-gray-700">
          <p class="text-xs text-gray-500 uppercase font-semibold mb-2 px-3">Tipos de evento</p>
          <div class="space-y-1">
            <a th:each="tipo : ${T(com.miempresa.fasi.calendario.Evento.TipoEvento).values()}"
               th:href="@{/calendario(tipo=${tipo.name()})}"
               class="flex items-center gap-2 px-3 py-1 rounded-lg hover:bg-gray-700
                      transition text-sm text-gray-300">
              <span class="w-2 h-2 rounded-full flex-shrink-0"
                    th:style="'background-color:' + ${tipo.color}"></span>
              <span th:text="${tipo.emoji + ' ' + tipo.label}"></span>
            </a>
          </div>
        </div>
      </nav>
      <div class="p-4 border-t border-gray-700 text-sm text-gray-400">
        <span sec:authentication="name">usuario</span>
        <form th:action="@{/logout}" method="post" class="inline ml-2">
          <button type="submit" class="text-red-400 hover:text-red-300">salir</button>
        </form>
      </div>
    </aside>

    <!-- CONTENIDO PRINCIPAL -->
    <main class="flex-1 lg:ml-64 p-4 lg:p-8 w-full">
      <th:block th:replace="${content}"/>
    </main>
  </div>

  <script>
    const menuBtn    = document.getElementById('menu-btn');
    const mobileMenu = document.getElementById('mobile-menu');
    const overlay    = document.getElementById('menu-overlay');

    function abrirMenu()  { mobileMenu.classList.remove('hidden'); overlay.classList.remove('hidden'); }
    function cerrarMenu() { mobileMenu.classList.add('hidden');    overlay.classList.add('hidden'); }
    if (menuBtn) menuBtn.addEventListener('click', e => {
      e.stopPropagation();
      mobileMenu.classList.contains('hidden') ? abrirMenu() : cerrarMenu();
    });

    const htmlEl      = document.documentElement;
    const iconMobile  = document.getElementById('dark-icon');
    const iconDesktop = document.getElementById('dark-icon-desktop');

    function applyDark(isDark) {
      isDark ? htmlEl.classList.add('dark') : htmlEl.classList.remove('dark');
      const icon = isDark ? '☀️' : '🌙';
      if (iconMobile)  iconMobile.textContent  = icon;
      if (iconDesktop) iconDesktop.textContent = icon;
    }
    function toggleDark() {
      const isDark = !htmlEl.classList.contains('dark');
      localStorage.setItem('fasi-dark', isDark ? '1' : '0');
      applyDark(isDark);
    }
    (function() {
      const saved = localStorage.getItem('fasi-dark');
      applyDark(saved !== null ? saved === '1'
                               : window.matchMedia('(prefers-color-scheme: dark)').matches);
    })();
  </script>

</body>
</html>
HTMLEOF

echo "✅ layout/base.html actualizado"

# ── 15. ACTUALIZAR index.html (redirige al calendario) ────────
cat > src/main/resources/templates/index.html << 'HTMLEOF'
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta http-equiv="refresh" content="0;url=/calendario"/>
</head>
<body></body>
</html>
HTMLEOF

echo "✅ index.html actualizado"

# ── 16. LIMPIAR IaContextBuilder (quitar refs a módulos viejos)
echo "☕ Actualizando IaContextBuilder.java..."

cat > src/main/java/com/miempresa/fasi/ia/IaContextBuilder.java << 'JAVAEOF'
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
JAVAEOF

echo "✅ IaContextBuilder.java actualizado"

# ── 17. RESUMEN FINAL ─────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════════════"
echo "  ✅ MIGRACIÓN COMPLETADA — FASI Calendario"
echo "════════════════════════════════════════════════════════"
echo ""
echo "📁 Ficheros creados/modificados:"
echo "   ✅ V8__calendario.sql          — BD con JSONB + full-text"
echo "   ✅ Evento.java                 — Entidad con 11 tipos"
echo "   ✅ EventoRepository.java       — Queries + full-text"
echo "   ✅ EventoService.java          — CRUD + búsqueda"
echo "   ✅ EventoIaService.java        — Voz→JSON + consultas"
echo "   ✅ EventoIaRequest/Response    — DTOs IA"
echo "   ✅ CalendarioController.java   — Web + API REST"
echo "   ✅ calendario/index.html       — Vista mensual + voz"
echo "   ✅ calendario/form.html        — Formulario evento"
echo "   ✅ calendario/dia.html         — Vista día"
echo "   ✅ layout/base.html            — Sidebar limpio"
echo "   ✅ IaContextBuilder.java       — Actualizado al calendario"
echo "   ✅ SecurityConfig.java         — CSRF actualizado"
echo "   ✅ HomeController.java         — Redirige a /calendario"
echo ""
echo "📦 Módulos ELIMINADOS:"
echo "   🗑️  agenda, tareas, contabilidad, documentos, fotos, desarrollo"
echo ""
echo "🚀 Siguiente paso:"
echo "   mvn clean package -DskipTests && java -jar target/app.jar"
echo ""
echo "🌐 Accede en: http://localhost:8091/calendario"
echo "════════════════════════════════════════════════════════"

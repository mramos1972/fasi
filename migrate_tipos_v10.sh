#!/usr/bin/env bash
# ============================================================
# FASI — Migración tipos V10
# Reemplaza el enum de 11 tipos específicos por 10 macrocategorías
# universales + tabla de etiquetas libres.
#
# Requisitos: BD vaciada (no hay eventos).
# Genera backup automático y permite rollback.
# ============================================================
set -euo pipefail

STAMP=$(date '+%Y%m%d_%H%M%S')
BACKUP_DIR="backups/migrate_tipos_v10_${STAMP}"
SRC="src/main/java/com/miempresa/fasi"
TPL="src/main/resources/templates/calendario"
SQL="src/main/resources/db/migration"

echo "════════════════════════════════════════════════════════"
echo "  FASI — Migración tipos V10"
echo "  Backup en: ${BACKUP_DIR}"
echo "════════════════════════════════════════════════════════"

# ── 1. BACKUP ────────────────────────────────────────────────
mkdir -p "${BACKUP_DIR}"
cp "${SRC}/calendario/Evento.java"             "${BACKUP_DIR}/" 2>/dev/null || true
cp "${SRC}/calendario/EventoIaService.java"    "${BACKUP_DIR}/" 2>/dev/null || true
cp "${SRC}/ia/IaContextBuilder.java"           "${BACKUP_DIR}/" 2>/dev/null || true
cp "${TPL}/form.html"                          "${BACKUP_DIR}/" 2>/dev/null || true
cp "${TPL}/dia.html"                           "${BACKUP_DIR}/" 2>/dev/null || true
echo "✅ Backup creado"

# ── 2. SQL: V10 — tabla etiquetas ────────────────────────────
cat > "${SQL}/V10__rediseno_tipos.sql" <<'EOF'
-- ══════════════════════════════════════════════════════════
-- V10: Rediseño de tipos de evento
-- Como la BD está vacía, no se migran datos antiguos.
-- ══════════════════════════════════════════════════════════

-- Tabla de etiquetas libres (uno-a-muchos con eventos)
CREATE TABLE evento_etiquetas (
    evento_id  UUID         NOT NULL REFERENCES eventos(id) ON DELETE CASCADE,
    etiqueta   VARCHAR(50)  NOT NULL,
    PRIMARY KEY (evento_id, etiqueta)
);

CREATE INDEX idx_etiquetas_etiqueta ON evento_etiquetas(etiqueta);
CREATE INDEX idx_etiquetas_evento   ON evento_etiquetas(evento_id);

-- Comentario para futuros desarrolladores
COMMENT ON TABLE evento_etiquetas IS
  'Etiquetas libres asociadas a eventos. Permiten búsqueda y filtros granulares además del tipo macro.';
EOF
echo "✅ V10__rediseno_tipos.sql creado"

# ── 3. Evento.java — nuevo enum + etiquetas ─────────────────
cat > "${SRC}/calendario/Evento.java" <<'EOF'
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "eventos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Evento extends BaseEntity {

    // ── Tipo de evento (macrocategoría) ──────────────────────
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TipoEvento tipo;

    // ── Etiquetas libres (búsqueda y filtro fino) ────────────
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "evento_etiquetas",
        joinColumns = @JoinColumn(name = "evento_id")
    )
    @Column(name = "etiqueta", length = 50)
    @Builder.Default
    private Set<String> etiquetas = new HashSet<>();

    // ── Título ───────────────────────────────────────────────
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

    // ────────────────────────────────────────────────────────
    //  ENUM DE TIPOS — 10 macrocategorías universales
    // ────────────────────────────────────────────────────────
    public enum TipoEvento {
        PERSONAL,
        SOCIAL,
        SALUD,
        TRABAJO,
        FAMILIA,
        VIAJE,
        APRENDIZAJE,
        OCIO,
        GESTION,
        OTRO;

        public String getEmoji() {
            return switch (this) {
                case PERSONAL    -> "👤";
                case SOCIAL      -> "🎉";
                case SALUD       -> "⚕️";
                case TRABAJO     -> "💼";
                case FAMILIA     -> "🏠";
                case VIAJE       -> "✈️";
                case APRENDIZAJE -> "📚";
                case OCIO        -> "🎨";
                case GESTION     -> "📋";
                case OTRO        -> "📌";
            };
        }

        public String getColor() {
            return switch (this) {
                case PERSONAL    -> "#3b82f6"; // azul
                case SOCIAL      -> "#ec4899"; // rosa
                case SALUD       -> "#ef4444"; // rojo
                case TRABAJO     -> "#8b5cf6"; // violeta
                case FAMILIA     -> "#10b981"; // verde
                case VIAJE       -> "#f59e0b"; // ámbar
                case APRENDIZAJE -> "#6366f1"; // indigo
                case OCIO        -> "#14b8a6"; // teal
                case GESTION     -> "#64748b"; // slate
                case OTRO        -> "#94a3b8"; // gris claro
            };
        }

        public String getLabel() {
            return switch (this) {
                case PERSONAL    -> "Personal";
                case SOCIAL      -> "Social";
                case SALUD       -> "Salud";
                case TRABAJO     -> "Trabajo";
                case FAMILIA     -> "Familia";
                case VIAJE       -> "Viaje";
                case APRENDIZAJE -> "Aprendizaje";
                case OCIO        -> "Ocio";
                case GESTION     -> "Gestión";
                case OTRO        -> "Otro";
            };
        }

        /** Serializar siempre como objeto con todos los campos para el frontend */
        @com.fasterxml.jackson.annotation.JsonValue
        public java.util.Map<String, String> toJson() {
            return java.util.Map.of(
                "name",  this.name(),
                "label", this.getLabel(),
                "emoji", this.getEmoji(),
                "color", this.getColor()
            );
        }

        /** Deserializar desde String "SOCIAL" o desde objeto {name:"SOCIAL",...} */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static TipoEvento fromValue(Object value) {
            if (value instanceof String s) {
                String v = s.trim().toUpperCase();
                try { return TipoEvento.valueOf(v); }
                catch (IllegalArgumentException ignored) {}
                for (TipoEvento t : values()) {
                    if (t.getLabel().equalsIgnoreCase(s.trim())) return t;
                }
            }
            if (value instanceof java.util.Map<?,?> m) {
                Object n = m.get("name");
                if (n instanceof String s) {
                    try { return TipoEvento.valueOf(s.trim().toUpperCase()); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
            return OTRO;
        }
    }
}
EOF
echo "✅ Evento.java reescrito (10 macrocategorías + etiquetas)"

# ── 4. IaContextBuilder.java — heurísticas adaptadas ────────
cat > "${SRC}/ia/IaContextBuilder.java" <<'EOF'
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

    /**
     * Detecta el tipo macro a partir de palabras clave de la pregunta.
     * Mapeo a las 10 macrocategorías.
     */
    private Evento.TipoEvento detectarTipo(String p) {
        // SALUD
        if (p.contains("médic") || p.contains("medic") || p.contains("doctor")
         || p.contains("hospital") || p.contains("consulta") || p.contains("dentist")
         || p.contains("fisio") || p.contains("gym") || p.contains("gimnasio")
         || p.contains("terap"))                                  return Evento.TipoEvento.SALUD;

        // SOCIAL
        if (p.contains("cumple") || p.contains("comida") || p.contains("comer")
         || p.contains("cena") || p.contains("almuerz") || p.contains("fiesta")
         || p.contains("quedad") || p.contains("amig"))           return Evento.TipoEvento.SOCIAL;

        // TRABAJO
        if (p.contains("tutor") || p.contains("claustro") || p.contains("reuni")
         || p.contains("trabajo") || p.contains("oficina"))       return Evento.TipoEvento.TRABAJO;

        // APRENDIZAJE
        if (p.contains("curso") || p.contains("formaci") || p.contains("clase")
         || p.contains("estudi") || p.contains("taller")
         || p.contains("charla"))                                 return Evento.TipoEvento.APRENDIZAJE;

        // FAMILIA
        if (p.contains("extraescolar") || p.contains("hijo") || p.contains("hija")
         || p.contains("niñ") || p.contains("famil")
         || p.contains("colegio"))                                return Evento.TipoEvento.FAMILIA;

        // VIAJE
        if (p.contains("viaj") || p.contains("vacacion") || p.contains("vuelo")
         || p.contains("hotel"))                                  return Evento.TipoEvento.VIAJE;

        // GESTION
        if (p.contains("compra") || p.contains("trámit") || p.contains("tramit")
         || p.contains("banco") || p.contains("recad")
         || p.contains("itv"))                                    return Evento.TipoEvento.GESTION;

        // OCIO
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
EOF
echo "✅ IaContextBuilder.java actualizado (heurísticas macro + etiquetas en contexto)"

# ── 5. Parchear EventoIaService.java — solo el prompt ───────
# Solo actualizamos el método buildPromptInterpretarMinimo, dejando el resto intacto
python3 - <<'PYEOF'
import re
path = "src/main/java/com/miempresa/fasi/calendario/EventoIaService.java"
with open(path, encoding="utf-8") as f:
    src = f.read()

# Nuevo prompt para 10 macrocategorías + etiquetas
nuevo_prompt = '''    private String buildPromptInterpretarMinimo(EventoIaRequest request) {
        java.time.LocalDate hoy = request.getFechaContexto() != null
                ? request.getFechaContexto()
                : java.time.LocalDate.now();

        return """
            Eres extractor de eventos de calendario. Devuelve SOLO JSON válido,
            sin texto explicativo, en este formato exacto:
            {"eventos":[{"tipo":"...","titulo":"...","fechaInicio":"YYYY-MM-DD",
                        "horaInicio":"HH:mm:ss","lugar":"...","personasRelacionadas":"...",
                        "descripcion":"...","etiquetas":["tag1","tag2"]}]}

            REGLAS:
            - tipo: UNO de [PERSONAL, SOCIAL, SALUD, TRABAJO, FAMILIA, VIAJE,
                            APRENDIZAJE, OCIO, GESTION, OTRO]
            - etiquetas: array de strings cortos en minúsculas y sin tildes
              (ej: ["cumpleanos", "amigos"], ["dentista"], ["futbol", "hijo"])
            - personasRelacionadas: SIEMPRE string separado por comas, NUNCA array
            - fechaInicio: SIEMPRE YYYY-MM-DD, sin hora
            - horaInicio: HH:mm:ss o null si no se especifica
            - Hoy es %s

            EJEMPLOS de mapeo:
            - "cumple Noa el viernes" -> tipo=SOCIAL, etiquetas=["cumpleanos"]
            - "médico jueves 10h" -> tipo=SALUD, etiquetas=["medico"]
            - "claustro lunes" -> tipo=TRABAJO, etiquetas=["claustro"]
            - "extraescolar Rocío martes 18h" -> tipo=FAMILIA, etiquetas=["extraescolar"]
            - "comprar pan" -> tipo=GESTION, etiquetas=["compras"]
            - "viaje Madrid 5-7 junio" -> tipo=VIAJE, etiquetas=["viaje"]
            - "curso Spring Boot" -> tipo=APRENDIZAJE, etiquetas=["curso"]
            - "cine sábado" -> tipo=OCIO, etiquetas=["cine"]

            Texto del usuario: %s
            """.formatted(hoy, request.getTexto());
    }
'''

# Buscar y reemplazar el método existente
pattern = re.compile(
    r'    private String buildPromptInterpretarMinimo\(EventoIaRequest request\) \{.*?\n    \}\n',
    re.DOTALL
)
if pattern.search(src):
    src_new = pattern.sub(nuevo_prompt, src)
else:
    # Si no existe, insertarlo antes del cierre de la clase
    src_new = src.rsplit("}", 1)[0] + nuevo_prompt + "}\n"

with open(path, "w", encoding="utf-8") as f:
    f.write(src_new)
print("✅ EventoIaService.java prompt actualizado")
PYEOF

# ── 6. form.html — añadir input de etiquetas + tipos nuevos ──
# El select itera ${tipos} dinámicamente, así que NO hay que tocarlo (se actualiza solo).
# Solo añadimos un input para etiquetas justo después de "Personas".

python3 - <<'PYEOF'
path = "src/main/resources/templates/calendario/form.html"
with open(path, encoding="utf-8") as f:
    html = f.read()

# Añadir bloque de etiquetas después del campo "Personas"
bloque_etiquetas = '''
      <!-- Etiquetas libres -->
      <div>
        <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
          🏷️ Etiquetas
          <span class="text-xs text-gray-400 font-normal">(separadas por comas)</span>
        </label>
        <input type="text" name="etiquetasRaw"
               th:value="${evento.etiquetas != null ? #strings.setJoin(evento.etiquetas, ', ') : ''}"
               placeholder="Ej: cumpleanos, amigos, jara"
               class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2
                      bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                      focus:outline-none focus:ring-2 focus:ring-indigo-400"/>
      </div>
'''

# Insertar antes del bloque de Descripción
marker = '<!-- Descripción -->'
if marker in html and 'name="etiquetasRaw"' not in html:
    html = html.replace(marker, bloque_etiquetas + '\n      ' + marker)
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)
    print("✅ form.html: input de etiquetas añadido")
else:
    print("ℹ️  form.html: ya tenía etiquetas o no se encontró marker (revisar manualmente)")
PYEOF

# ── 7. dia.html — mostrar chips de etiquetas en cada evento ──
python3 - <<'PYEOF'
path = "src/main/resources/templates/calendario/dia.html"
with open(path, encoding="utf-8") as f:
    html = f.read()

# Añadir chips justo después del título h3
chips = '''        <div th:if="${e.etiquetas != null and !e.etiquetas.isEmpty()}"
             class="flex flex-wrap gap-1 mt-1">
          <span th:each="tag : ${e.etiquetas}"
                class="text-[10px] px-1.5 py-0.5 rounded-full bg-gray-100 dark:bg-gray-700
                       text-gray-600 dark:text-gray-300"
                th:text="'#' + ${tag}"></span>
        </div>
'''

marker = 'th:text="${e.titulo}"></h3>'
if marker in html and 'th:each="tag : ${e.etiquetas}"' not in html:
    html = html.replace(marker, marker + '\n' + chips)
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)
    print("✅ dia.html: chips de etiquetas añadidos")
else:
    print("ℹ️  dia.html: ya tenía chips o no se encontró marker (revisar manualmente)")
PYEOF

# ── 8. Verificación final ────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════════════"
echo "  ✅ MIGRACIÓN APLICADA"
echo "════════════════════════════════════════════════════════"
echo ""
echo "📋 Próximos pasos:"
echo "   1. ./mvnw clean compile          # Verifica que compila"
echo "   2. docker compose down -v        # Limpia volumen BD"
echo "   3. docker compose up -d          # Reinicia BD"
echo "   4. ./mvnw spring-boot:run        # Arranca FASI"
echo ""
echo "🧪 Smoke test:"
echo "   - Abre http://localhost:8080/calendario"
echo "   - Crea evento por voz: 'cumple Noa viernes'"
echo "   - Debe salir tipo SOCIAL con etiqueta cumpleanos"
echo ""
echo "🔄 Rollback (si algo falla):"
echo "   cp ${BACKUP_DIR}/* <ruta_original>"
echo "   rm ${SQL}/V10__rediseno_tipos.sql"

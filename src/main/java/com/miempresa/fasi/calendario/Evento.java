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

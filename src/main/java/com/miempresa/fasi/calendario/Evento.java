package com.miempresa.fasi.calendario;

import com.miempresa.fasi.common.BaseEntity;
import com.miempresa.fasi.contexto.Usuario;
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

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TipoEvento tipo;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "evento_etiquetas",
        joinColumns = @JoinColumn(name = "evento_id")
    )
    @Column(name = "etiqueta", length = 50)
    @Builder.Default
    private Set<String> etiquetas = new HashSet<>();

    @NotBlank
    @Column(nullable = false, length = 300)
    private String titulo;

    @NotNull
    @Column(name = "fecha_inicio", nullable = false)
    private LocalDate fechaInicio;

    @Column(name = "fecha_fin")
    private LocalDate fechaFin;

    @Column(name = "hora_inicio")
    private LocalTime horaInicio;

    @Column(name = "hora_fin")
    private LocalTime horaFin;

    @Builder.Default
    @Column(name = "todo_el_dia", nullable = false)
    private boolean todoDia = false;

    @Column(length = 300)
    private String lugar;

    @Column(name = "personas_relacionadas", length = 500)
    private String personasRelacionadas;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "recordatorio_minutos")
    private Integer recordatorioMinutos;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datos_extra", columnDefinition = "jsonb")
    private Map<String, Object> datosExtra;

    @Column(name = "texto_original_voz", columnDefinition = "TEXT")
    private String textoOriginalVoz;

    public enum TipoEvento {
        PERSONAL, SOCIAL, SALUD, TRABAJO, FAMILIA,
        VIAJE, VACACIONES, APRENDIZAJE, OCIO, GESTION, OTRO;

        public String getEmoji() {
            return switch (this) {
                case PERSONAL    -> "👤";
                case SOCIAL      -> "🎉";
                case SALUD       -> "⚕️";
                case TRABAJO     -> "💼";
                case FAMILIA     -> "🏠";
                case VIAJE       -> "✈️";
                case VACACIONES  -> "🏖️";
                case APRENDIZAJE -> "📚";
                case OCIO        -> "🎨";
                case GESTION     -> "📋";
                case OTRO        -> "📌";
            };
        }

        public String getColor() {
            return switch (this) {
                case PERSONAL    -> "#3b82f6";
                case SOCIAL      -> "#ec4899";
                case SALUD       -> "#ef4444";
                case TRABAJO     -> "#8b5cf6";
                case FAMILIA     -> "#10b981";
                case VIAJE       -> "#f59e0b";
                case VACACIONES  -> "#06b6d4";
                case APRENDIZAJE -> "#6366f1";
                case OCIO        -> "#14b8a6";
                case GESTION     -> "#64748b";
                case OTRO        -> "#94a3b8";
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
                case VACACIONES  -> "Vacaciones";
                case APRENDIZAJE -> "Aprendizaje";
                case OCIO        -> "Ocio";
                case GESTION     -> "Gestión";
                case OTRO        -> "Otro";
            };
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public java.util.Map<String, String> toJson() {
            return java.util.Map.of(
                "name",  this.name(),
                "label", this.getLabel(),
                "emoji", this.getEmoji(),
                "color", this.getColor()
            );
        }

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

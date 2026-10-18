package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.miempresa.fasi.contexto.Usuario;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Entity
@Table(name = "eventos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Evento {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TipoEvento tipo = TipoEvento.OTRO;

    @Column(nullable = false)
    private String titulo;

    @Column(name = "fecha_inicio", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate fechaInicio;

    @Column(name = "fecha_fin")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate fechaFin;

    @Column(name = "hora_inicio")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime horaInicio;

    @Column(name = "hora_fin")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime horaFin;

    @Column(name = "todo_dia", nullable = false)
    @Builder.Default
    private boolean todoDia = false;

    private String lugar;

    @Column(name = "personas_relacionadas")
    private String personasRelacionadas;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "texto_original_voz", columnDefinition = "TEXT")
    private String textoOriginalVoz;

    @Column(name = "recordatorio_minutos")
    private Integer recordatorioMinutos;

    @Column(name = "recordatorio_enviado", nullable = false)
    @Builder.Default
    private boolean recordatorioEnviado = false;

    @Column(name = "color_personalizado", length = 7)
    private String colorPersonalizado;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datos_extra", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> datosExtra = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "evento_etiquetas", joinColumns = @JoinColumn(name = "evento_id"))
    @Column(name = "etiqueta")
    @Builder.Default
    private Set<String> etiquetas = new HashSet<>();

    /**
     * Color efectivo para UI: prioriza el personalizado si existe,
     * o cae al predeterminado del TipoEvento.
     */
    public String getColorEfectivo() {
        if (colorPersonalizado != null && !colorPersonalizado.isBlank()) {
            return colorPersonalizado;
        }
        return (tipo != null && tipo.getColor() != null) ? tipo.getColor() : "#64748b";
    }

    public enum TipoEvento {
        PERSONAL("Personal", "👤", "#3b82f6"),
        SOCIAL("Social", "🎉", "#ec4899"),
        SALUD("Salud", "🩺", "#ef4444"),
        TRABAJO("Trabajo", "💼", "#8b5cf6"),
        FAMILIA("Familia", "👨‍👩‍👧‍👦", "#10b981"),
        VIAJE("Viaje", "🏖️", "#f59e0b"),
        VACACIONES("Vacaciones", "🏖️", "#06b6d4"),
        APRENDIZAJE("Aprendizaje", "📚", "#6366f1"),
        OCIO("Ocio", "🎮", "#14b8a6"),
        GESTION("Gestión", "📋", "#64748b"),
        OTRO("Otro", "📌", "#94a3b8");

        private final String label;
        private final String emoji;
        private final String color;

        TipoEvento(String label, String emoji, String color) {
            this.label = label;
            this.emoji = emoji;
            this.color = color;
        }

        public String getLabel() { return label; }
        public String getEmoji() { return emoji; }
        public String getColor() { return color; }

        public static TipoEvento fromValue(Object val) {
            if (val == null) return OTRO;
            String s = val.toString().trim().toUpperCase();
            for (TipoEvento t : values()) {
                if (t.name().equalsIgnoreCase(s) || t.label.equalsIgnoreCase(s)) return t;
            }
            return OTRO;
        }
    }
}

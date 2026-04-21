#!/bin/bash
set -e
BASE="src/main/java/com/miempresa/fasi"
TMPL="src/main/resources/templates"
MIGR="src/main/resources/db/migration"

echo "🤖 Creando módulo IA para FASI con Gemma3..."

# ── Directorios ──────────────────────────────────────────────
mkdir -p "$BASE/ia"
mkdir -p "$TMPL/ia"

# ════════════════════════════════════════════════════════════
# 1. DTOs
# ════════════════════════════════════════════════════════════
cat > "$BASE/ia/IaChatRequest.java" << 'EOF'
package com.miempresa.fasi.ia;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class IaChatRequest {
    @NotBlank
    private String mensaje;
    private String modulo; // opcional: "contabilidad", "tareas", "agenda", "general"
}
EOF

cat > "$BASE/ia/IaChatResponse.java" << 'EOF'
package com.miempresa.fasi.ia;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IaChatResponse {
    private String respuesta;
    private String modulo;
    private long   tiempoMs;
}
EOF

# ════════════════════════════════════════════════════════════
# 2. Entidad historial
# ════════════════════════════════════════════════════════════
cat > "$BASE/ia/IaMensaje.java" << 'EOF'
package com.miempresa.fasi.ia;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "ia_mensajes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IaMensaje extends BaseEntity {

    @Column(columnDefinition = "TEXT", nullable = false)
    private String pregunta;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String respuesta;

    private String modulo;

    private Long tiempoMs;
}
EOF

# ════════════════════════════════════════════════════════════
# 3. Repository
# ════════════════════════════════════════════════════════════
cat > "$BASE/ia/IaMensajeRepository.java" << 'EOF'
package com.miempresa.fasi.ia;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IaMensajeRepository extends JpaRepository<IaMensaje, UUID> {
    List<IaMensaje> findTop20ByOrderByCreatedAtDesc();
    List<IaMensaje> findByModuloOrderByCreatedAtDesc(String modulo);
}
EOF

# ════════════════════════════════════════════════════════════
# 4. OllamaClient — HTTP client a Ollama
# ════════════════════════════════════════════════════════════
cat > "$BASE/ia/OllamaClient.java" << 'EOF'
package com.miempresa.fasi.ia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaClient {

    @Value("${fasi.ollama.url:http://localhost:11434}")
    private String ollamaUrl;

    @Value("${fasi.ollama.model:gemma3:4b}")
    private String model;

    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public String generate(String prompt) {
        try {
            OllamaRequest req = new OllamaRequest();
            req.setModel(model);
            req.setPrompt(prompt);
            req.setStream(false);

            String body = objectMapper.writeValueAsString(req);

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> resp = httpClient.send(
                    httpReq, HttpResponse.BodyHandlers.ofString());

            OllamaResponse ollamaResp =
                    objectMapper.readValue(resp.body(), OllamaResponse.class);
            return ollamaResp.getResponse();

        } catch (Exception e) {
            log.error("Error llamando a Ollama", e);
            return "⚠️ Error al conectar con el asistente IA. " +
                   "Asegúrate de que Ollama está corriendo en WSL " +
                   "(ollama serve).";
        }
    }

    // ── DTOs internos ────────────────────────────────────────
    @Data
    static class OllamaRequest {
        private String  model;
        private String  prompt;
        private boolean stream;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OllamaResponse {
        private String response;
        private boolean done;
    }
}
EOF

# ════════════════════════════════════════════════════════════
# 5. IaContextBuilder — inyecta datos reales de BD en el prompt
# ════════════════════════════════════════════════════════════
cat > "$BASE/ia/IaContextBuilder.java" << 'EOF'
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
EOF

# ════════════════════════════════════════════════════════════
# 6. IaService
# ════════════════════════════════════════════════════════════
cat > "$BASE/ia/IaService.java" << 'EOF'
package com.miempresa.fasi.ia;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IaService {

    private final OllamaClient       ollamaClient;
    private final IaContextBuilder   contextBuilder;
    private final IaMensajeRepository repo;

    @Transactional
    public IaChatResponse chat(IaChatRequest request) {
        long inicio = System.currentTimeMillis();

        String prompt    = contextBuilder.buildPrompt(request.getMensaje(), request.getModulo());
        String respuesta = ollamaClient.generate(prompt);
        long   tiempoMs  = System.currentTimeMillis() - inicio;

        // Persistir en historial
        repo.save(IaMensaje.builder()
                .pregunta(request.getMensaje())
                .respuesta(respuesta)
                .modulo(request.getModulo() != null ? request.getModulo() : "general")
                .tiempoMs(tiempoMs)
                .build());

        log.info("IA respondió en {}ms para módulo '{}'", tiempoMs, request.getModulo());
        return new IaChatResponse(respuesta, request.getModulo(), tiempoMs);
    }

    public List<IaMensaje> historial() {
        return repo.findTop20ByOrderByCreatedAtDesc();
    }
}
EOF

# ════════════════════════════════════════════════════════════
# 7. IaController
# ════════════════════════════════════════════════════════════
cat > "$BASE/ia/IaController.java" << 'EOF'
package com.miempresa.fasi.ia;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/ia")
@RequiredArgsConstructor
public class IaController {

    private final IaService service;

    /** Pantalla principal del chat */
    @GetMapping
    public String chat(Model model) {
        model.addAttribute("request",  new IaChatRequest());
        model.addAttribute("historial", service.historial());
        return "ia/chat";
    }

    /**
     * Endpoint HTMX — devuelve solo el fragmento HTML de la respuesta.
     * El formulario hace POST aquí y HTMX inserta el resultado en el chat.
     */
    @PostMapping("/chat")
    public String chatHtmx(@Valid @ModelAttribute IaChatRequest request,
                            Model model) {
        IaChatResponse resp = service.chat(request);
        model.addAttribute("pregunta",  request.getMensaje());
        model.addAttribute("respuesta", resp.getRespuesta());
        model.addAttribute("tiempoMs",  resp.getTiempoMs());
        model.addAttribute("modulo",    resp.getModulo());
        return "ia/chat :: #respuesta-fragment";
    }

    /** API REST para uso externo / testing */
    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<IaChatResponse> apiChat(@Valid @RequestBody IaChatRequest request) {
        return ResponseEntity.ok(service.chat(request));
    }
}
EOF

# ════════════════════════════════════════════════════════════
# 8. Template chat.html
# ════════════════════════════════════════════════════════════
cat > "$TMPL/ia/chat.html" << 'EOF'
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/base :: layout(~{::title}, ~{::section})}">
<head><title>Asistente IA — FASI</title></head>
<body>
<section>

  <div class="flex justify-between items-center mb-6">
    <div>
      <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100">🤖 Asistente IA</h2>
      <p class="text-sm text-gray-500 dark:text-gray-400 mt-1">
        Powered by <span class="font-semibold text-indigo-600">Gemma 3 4B</span>
        · Ollama local · Acceso completo a tus datos
      </p>
    </div>
  </div>

  <!-- ── Selector de módulo ── -->
  <div class="flex flex-wrap gap-2 mb-4" id="modulo-btns">
    <button onclick="setModulo('general')"
            class="modulo-btn active px-3 py-1 rounded-full text-xs font-semibold bg-indigo-600 text-white">
      🌐 General
    </button>
    <button onclick="setModulo('contabilidad')"
            class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
      💰 Contabilidad
    </button>
    <button onclick="setModulo('tareas')"
            class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
      ✅ Tareas
    </button>
    <button onclick="setModulo('agenda')"
            class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
      📇 Agenda
    </button>
    <button onclick="setModulo('documentos')"
            class="modulo-btn px-3 py-1 rounded-full text-xs font-semibold bg-gray-200 dark:bg-gray-700 text-gray-700 dark:text-gray-200">
      📄 Documentos
    </button>
  </div>

  <!-- ── Área de chat ── -->
  <div id="chat-area"
       class="bg-white dark:bg-gray-800 rounded-xl shadow min-h-96 max-h-[60vh] overflow-y-auto p-6 mb-4 flex flex-col gap-4">

    <!-- Mensaje de bienvenida -->
    <div class="flex gap-3 items-start">
      <span class="text-2xl">🤖</span>
      <div class="bg-indigo-50 dark:bg-indigo-900/30 rounded-xl px-4 py-3 max-w-2xl">
        <p class="text-sm text-gray-700 dark:text-gray-200">
          ¡Hola! Soy tu asistente IA integrado en FASI. Tengo acceso a todos tus datos:
          contactos, tareas, movimientos contables y documentos.<br/><br/>
          Puedes preguntarme cosas como:<br/>
          • <em>"¿Cuál es mi saldo actual y en qué gasto más?"</em><br/>
          • <em>"¿Qué tareas tengo pendientes de alta prioridad?"</em><br/>
          • <em>"Dame un informe de mis ingresos del último mes"</em><br/>
          • <em>"¿Tengo contactos sin email registrado?"</em>
        </p>
      </div>
    </div>

    <!-- Historial reciente -->
    <th:block th:each="msg : ${historial}">
      <!-- Pregunta usuario -->
      <div class="flex gap-3 items-start justify-end">
        <div class="bg-gray-100 dark:bg-gray-700 rounded-xl px-4 py-3 max-w-2xl">
          <p class="text-sm text-gray-800 dark:text-gray-100" th:text="${msg.pregunta}"></p>
        </div>
        <span class="text-2xl">👤</span>
      </div>
      <!-- Respuesta IA -->
      <div class="flex gap-3 items-start">
        <span class="text-2xl">🤖</span>
        <div class="bg-indigo-50 dark:bg-indigo-900/30 rounded-xl px-4 py-3 max-w-2xl">
          <p class="text-sm text-gray-700 dark:text-gray-200 whitespace-pre-wrap"
             th:text="${msg.respuesta}"></p>
          <p class="text-xs text-gray-400 mt-2"
             th:text="'⏱ ' + ${msg.tiempoMs} + 'ms · módulo: ' + ${msg.modulo}"></p>
        </div>
      </div>
    </th:block>

    <!-- Fragmento donde HTMX inyecta la nueva respuesta -->
    <div id="respuesta-fragment"></div>

    <!-- Indicador de carga -->
    <div id="loading-indicator" class="hidden flex gap-3 items-start">
      <span class="text-2xl">🤖</span>
      <div class="bg-indigo-50 dark:bg-indigo-900/30 rounded-xl px-4 py-3">
        <div class="flex gap-1 items-center">
          <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style="animation-delay:0ms"></div>
          <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style="animation-delay:150ms"></div>
          <div class="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style="animation-delay:300ms"></div>
          <span class="text-xs text-gray-400 ml-2">Gemma está pensando...</span>
        </div>
      </div>
    </div>

  </div>

  <!-- ── Formulario de entrada ── -->
  <form id="chat-form"
        hx-post="/ia/chat"
        hx-target="#chat-area"
        hx-swap="beforeend"
        hx-indicator="#loading-indicator"
        class="flex gap-3">

    <input type="hidden" name="modulo" id="modulo-input" value="general"/>

    <textarea name="mensaje"
              id="mensaje-input"
              rows="2"
              placeholder="Escribe tu pregunta... (Enter para enviar, Shift+Enter para nueva línea)"
              required
              class="flex-1 border border-gray-300 dark:border-gray-600 rounded-xl px-4 py-3
                     bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                     focus:outline-none focus:ring-2 focus:ring-indigo-500
                     resize-none text-sm"></textarea>

    <button type="submit"
            class="bg-indigo-600 hover:bg-indigo-700 text-white px-6 py-3 rounded-xl
                   font-semibold transition flex items-center gap-2 self-end">
      <span>Enviar</span>
      <span>→</span>
    </button>
  </form>

  <!-- ── Sugerencias rápidas ── -->
  <div class="mt-3 flex flex-wrap gap-2">
    <span class="text-xs text-gray-400 dark:text-gray-500 self-center">Sugerencias:</span>
    <button onclick="setSugerencia('Dame un resumen financiero completo con recomendaciones')"
            class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100 dark:hover:bg-indigo-900/40
                   text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
      📊 Resumen financiero
    </button>
    <button onclick="setSugerencia('¿Qué tareas de alta prioridad tengo pendientes?')"
            class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100 dark:hover:bg-indigo-900/40
                   text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
      🔥 Tareas urgentes
    </button>
    <button onclick="setSugerencia('Analiza mis gastos y dime en qué categorías gasto más')"
            class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100 dark:hover:bg-indigo-900/40
                   text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
      💸 Análisis de gastos
    </button>
    <button onclick="setSugerencia('Dame consejos para mejorar mi productividad según mis tareas actuales')"
            class="text-xs bg-gray-100 dark:bg-gray-700 hover:bg-indigo-100 dark:hover:bg-indigo-900/40
                   text-gray-600 dark:text-gray-300 px-3 py-1 rounded-full transition">
      💡 Consejos productividad
    </button>
  </div>

</section>

<script>
  // ── Selector de módulo ───────────────────────────────────
  function setModulo(modulo) {
    document.getElementById('modulo-input').value = modulo;
    document.querySelectorAll('.modulo-btn').forEach(btn => {
      btn.classList.remove('active', 'bg-indigo-600', 'text-white');
      btn.classList.add('bg-gray-200', 'dark:bg-gray-700', 'text-gray-700', 'dark:text-gray-200');
    });
    event.target.classList.add('active', 'bg-indigo-600', 'text-white');
    event.target.classList.remove('bg-gray-200', 'dark:bg-gray-700', 'text-gray-700', 'dark:text-gray-200');
  }

  // ── Sugerencias rápidas ──────────────────────────────────
  function setSugerencia(texto) {
    document.getElementById('mensaje-input').value = texto;
    document.getElementById('mensaje-input').focus();
  }

  // ── Enter para enviar ────────────────────────────────────
  document.getElementById('mensaje-input').addEventListener('keydown', function(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      document.getElementById('chat-form').dispatchEvent(new Event('submit', {bubbles: true}));
    }
  });

  // ── Auto-scroll al fondo tras respuesta IA ───────────────
  document.getElementById('chat-form').addEventListener('htmx:afterSwap', function() {
    const chatArea = document.getElementById('chat-area');
    chatArea.scrollTop = chatArea.scrollHeight;
    document.getElementById('mensaje-input').value = '';
  });

  // Scroll inicial al fondo (historial)
  window.addEventListener('load', function() {
    const chatArea = document.getElementById('chat-area');
    chatArea.scrollTop = chatArea.scrollHeight;
  });
</script>

</body>
</html>
EOF

# ════════════════════════════════════════════════════════════
# 9. Migración V7 — tabla historial IA
# ════════════════════════════════════════════════════════════
cat > "$MIGR/V7__ia.sql" << 'EOF'
CREATE TABLE ia_mensajes (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    pregunta    TEXT         NOT NULL,
    respuesta   TEXT         NOT NULL,
    modulo      VARCHAR(50)  NOT NULL DEFAULT 'general',
    tiempo_ms   BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_ia_mensajes_created_at ON ia_mensajes(created_at DESC);
CREATE INDEX idx_ia_mensajes_modulo     ON ia_mensajes(modulo);
EOF

# ════════════════════════════════════════════════════════════
# 10. Añadir propiedades Ollama al application.properties
# ════════════════════════════════════════════════════════════
PROPS="src/main/resources/application.properties"
if ! grep -q "fasi.ollama.url" "$PROPS"; then
cat >> "$PROPS" << 'EOF'

# ── Ollama / IA local ────────────────────────────────────────
fasi.ollama.url=${OLLAMA_URL:http://localhost:11434}
fasi.ollama.model=${OLLAMA_MODEL:gemma3:4b}
EOF
echo "✅ Propiedades Ollama añadidas a application.properties"
fi

# ════════════════════════════════════════════════════════════
# 11. Parchear layout/base.html — añadir enlace IA al menú
# ════════════════════════════════════════════════════════════
BASE_HTML="$TMPL/layout/base.html"

# Sidebar desktop — añadir después del enlace /desarrollo
sed -i 's|<a th:href="@{/desarrollo}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🛠️ <span>Desarrollo</span></a>|<a th:href="@{/desarrollo}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🛠️ <span>Desarrollo</span></a>\n        <a th:href="@{/ia}"            class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🤖 <span>Asistente IA</span></a>|g' "$BASE_HTML"

# Menú móvil — añadir después del enlace /desarrollo
sed -i 's|<a th:href="@{/desarrollo}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🛠️ <span>Desarrollo</span></a>|<a th:href="@{/desarrollo}"   class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🛠️ <span>Desarrollo</span></a>\n      <a th:href="@{/ia}"            class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🤖 <span>Asistente IA</span></a>|g' "$BASE_HTML"

echo "✅ Menú actualizado con enlace a Asistente IA"

# ════════════════════════════════════════════════════════════
# 12. Parchear index.html — añadir tarjeta IA al dashboard
# ════════════════════════════════════════════════════════════
INDEX_HTML="$TMPL/index.html"
sed -i 's|</div>\n</section>|    <a th:href="@{/ia}"\n       class="bg-white dark:bg-gray-800 rounded-xl shadow p-6 hover:shadow-md transition flex items-center gap-4">\n      <span class="text-4xl">🤖</span>\n      <div><p class="font-semibold text-gray-800 dark:text-gray-100">Asistente IA</p><p class="text-sm text-gray-500 dark:text-gray-400">Gemma 3 · Análisis inteligente</p></div>\n    </a>\n  </div>\n</section>|' "$INDEX_HTML"

echo ""
echo "════════════════════════════════════════════"
echo "✅ Módulo IA creado correctamente"
echo "════════════════════════════════════════════"
echo ""
echo "📋 FICHEROS CREADOS:"
echo "   src/main/java/.../ia/IaChatRequest.java"
echo "   src/main/java/.../ia/IaChatResponse.java"
echo "   src/main/java/.../ia/IaMensaje.java"
echo "   src/main/java/.../ia/IaMensajeRepository.java"
echo "   src/main/java/.../ia/OllamaClient.java"
echo "   src/main/java/.../ia/IaContextBuilder.java"
echo "   src/main/java/.../ia/IaService.java"
echo "   src/main/java/.../ia/IaController.java"
echo "   src/main/resources/templates/ia/chat.html"
echo "   src/main/resources/db/migration/V7__ia.sql"
echo ""
echo "⚙️  ANTES DE COMPILAR — asegúrate de que Ollama corre:"
echo "   ollama serve &"
echo "   ollama run gemma3:4b  # para verificar que responde"
echo ""
echo "🚀 COMPILAR Y ARRANCAR:"
echo "   ./mvnw spring-boot:run"
echo ""
echo "🌐 ACCEDER AL CHAT:"
echo "   http://localhost:8080/ia"
echo "════════════════════════════════════════════"

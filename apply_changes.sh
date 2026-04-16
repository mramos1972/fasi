#!/bin/bash
# ============================================================
# FASI — Módulo Desarrollo
# Aplica: V6__desarrollo.sql + Java + Templates + Menú + Dashboard
# Uso: chmod +x apply-changes.sh && ./apply-changes.sh
# ============================================================

set -e
BASE="src/main/resources"
JAVA="src/main/java/com/miempresa/fasi/desarrollo"
TMPL="$BASE/templates/desarrollo"

echo "📁 Creando directorios..."
mkdir -p "$JAVA"
mkdir -p "$TMPL"

# ── V6 Migración SQL ──────────────────────────────────────────
echo "🗄️  Escribiendo V6__desarrollo.sql..."
cat > "$BASE/db/migration/V6__desarrollo.sql" << 'ENDSQL'
CREATE TABLE desarrollos (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    titulo           VARCHAR(200)  NOT NULL,
    descripcion      TEXT,
    estado           VARCHAR(20)   NOT NULL DEFAULT 'PENDIENTE',
    script_generado  TEXT,
    fecha_aplicado   TIMESTAMP,
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now()
);
ENDSQL

# ── Desarrollo.java ───────────────────────────────────────────
echo "☕ Escribiendo Desarrollo.java..."
cat > "$JAVA/Desarrollo.java" << 'ENDJAVA'
package com.miempresa.fasi.desarrollo;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "desarrollos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Desarrollo extends BaseEntity {

    @NotBlank
    @Column(nullable = false)
    private String titulo;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoDesarrollo estado;

    @Column(columnDefinition = "TEXT")
    private String scriptGenerado;

    private LocalDateTime fechaAplicado;

    public enum EstadoDesarrollo { PENDIENTE, APLICADO, DESCARTADO }
}
ENDJAVA

# ── DesarrolloRepository.java ─────────────────────────────────
echo "☕ Escribiendo DesarrolloRepository.java..."
cat > "$JAVA/DesarrolloRepository.java" << 'ENDJAVA'
package com.miempresa.fasi.desarrollo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DesarrolloRepository extends JpaRepository<Desarrollo, UUID> {
    List<Desarrollo> findAllByOrderByCreatedAtDesc();
    List<Desarrollo> findByEstadoOrderByCreatedAtDesc(Desarrollo.EstadoDesarrollo estado);
}
ENDJAVA

# ── DesarrolloService.java ────────────────────────────────────
echo "☕ Escribiendo DesarrolloService.java..."
cat > "$JAVA/DesarrolloService.java" << 'ENDJAVA'
package com.miempresa.fasi.desarrollo;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DesarrolloService {

    private final DesarrolloRepository repo;

    public List<Desarrollo> findAll() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    public List<Desarrollo> findByEstado(Desarrollo.EstadoDesarrollo estado) {
        return repo.findByEstadoOrderByCreatedAtDesc(estado);
    }

    public Desarrollo findById(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Desarrollo no encontrado: " + id));
    }

    @Transactional
    public Desarrollo save(Desarrollo d) {
        if (d.getEstado() == null) {
            d.setEstado(Desarrollo.EstadoDesarrollo.PENDIENTE);
        }
        return repo.save(d);
    }

    @Transactional
    public Desarrollo update(UUID id, Desarrollo datos) {
        Desarrollo d = findById(id);
        d.setTitulo(datos.getTitulo());
        d.setDescripcion(datos.getDescripcion());
        d.setEstado(datos.getEstado());
        d.setScriptGenerado(datos.getScriptGenerado());
        return repo.save(d);
    }

    @Transactional
    public Desarrollo marcarAplicado(UUID id) {
        Desarrollo d = findById(id);
        d.setEstado(Desarrollo.EstadoDesarrollo.APLICADO);
        d.setFechaAplicado(LocalDateTime.now());
        return repo.save(d);
    }

    @Transactional
    public Desarrollo marcarDescartado(UUID id) {
        Desarrollo d = findById(id);
        d.setEstado(Desarrollo.EstadoDesarrollo.DESCARTADO);
        return repo.save(d);
    }

    @Transactional
    public void delete(UUID id) {
        repo.deleteById(id);
    }
}
ENDJAVA

# ── DesarrolloController.java ─────────────────────────────────
echo "☕ Escribiendo DesarrolloController.java..."
cat > "$JAVA/DesarrolloController.java" << 'ENDJAVA'
package com.miempresa.fasi.desarrollo;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Controller
@RequestMapping("/desarrollo")
@RequiredArgsConstructor
public class DesarrolloController {

    private final DesarrolloService service;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("desarrollos", service.findAll());
        model.addAttribute("estados", Desarrollo.EstadoDesarrollo.values());
        return "desarrollo/list";
    }

    @GetMapping("/nuevo")
    public String newForm(Model model) {
        model.addAttribute("desarrollo", Desarrollo.builder()
                .estado(Desarrollo.EstadoDesarrollo.PENDIENTE)
                .build());
        model.addAttribute("estados", Desarrollo.EstadoDesarrollo.values());
        return "desarrollo/form";
    }

    @GetMapping("/{id}/editar")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("desarrollo", service.findById(id));
        model.addAttribute("estados", Desarrollo.EstadoDesarrollo.values());
        return "desarrollo/form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute Desarrollo desarrollo, BindingResult br, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("estados", Desarrollo.EstadoDesarrollo.values());
            return "desarrollo/form";
        }
        service.save(desarrollo);
        return "redirect:/desarrollo";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute Desarrollo desarrollo,
                         BindingResult br, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("estados", Desarrollo.EstadoDesarrollo.values());
            return "desarrollo/form";
        }
        service.update(id, desarrollo);
        return "redirect:/desarrollo";
    }

    @PostMapping("/{id}/aplicar")
    public String aplicar(@PathVariable UUID id) {
        service.marcarAplicado(id);
        return "redirect:/desarrollo";
    }

    @PostMapping("/{id}/descartar")
    public String descartar(@PathVariable UUID id) {
        service.marcarDescartado(id);
        return "redirect:/desarrollo";
    }

    @GetMapping("/{id}/descargar")
    public ResponseEntity<byte[]> descargarScript(@PathVariable UUID id) {
        Desarrollo d = service.findById(id);
        String script = d.getScriptGenerado() != null ? d.getScriptGenerado() : "#!/bin/bash\n# Sin script generado";
        byte[] bytes = script.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"apply-" + d.getId() + ".sh\"")
            .contentType(MediaType.parseMediaType("application/x-sh"))
            .body(bytes);
    }

    @PostMapping("/{id}/eliminar")
    public String delete(@PathVariable UUID id) {
        service.delete(id);
        return "redirect:/desarrollo";
    }
}
ENDJAVA

# ── Templates ─────────────────────────────────────────────────
echo "🌐 Escribiendo templates..."

cat > "$TMPL/list.html" << 'ENDHTML'
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/base :: layout(~{::title}, ~{::section})}">
<head><title>Desarrollo — FASI</title></head>
<body>
<section>
  <div class="flex justify-between items-center mb-6">
    <h2 class="text-2xl font-bold text-gray-800">🛠️ Desarrollo</h2>
    <a th:href="@{/desarrollo/nuevo}"
       class="bg-gray-900 text-white px-4 py-2 rounded-lg hover:bg-gray-700 transition text-sm">
      + Nueva entrada
    </a>
  </div>
  <div class="bg-white rounded-xl shadow overflow-hidden">
    <table class="w-full text-sm">
      <thead class="bg-gray-50 text-gray-600 uppercase text-xs">
        <tr>
          <th class="px-4 py-3 text-left">Título</th>
          <th class="px-4 py-3 text-left">Estado</th>
          <th class="px-4 py-3 text-left">Creado</th>
          <th class="px-4 py-3 text-left">Aplicado</th>
          <th class="px-4 py-3 text-left">Acciones</th>
        </tr>
      </thead>
      <tbody class="divide-y divide-gray-100">
        <tr th:each="d : ${desarrollos}" class="hover:bg-gray-50">
          <td class="px-4 py-3 font-medium" th:text="${d.titulo}"></td>
          <td class="px-4 py-3">
            <span th:text="${d.estado}"
                  th:classappend="${d.estado.name() == 'APLICADO'}    ? 'bg-green-100 text-green-700'  :
                                  (${d.estado.name() == 'DESCARTADO'} ? 'bg-red-100 text-red-700'      :
                                  'bg-yellow-100 text-yellow-700')"
                  class="px-2 py-1 rounded-full text-xs font-semibold"></span>
          </td>
          <td class="px-4 py-3 text-gray-500"
              th:text="${#temporals.format(d.createdAt, 'dd/MM/yyyy HH:mm')}"></td>
          <td class="px-4 py-3 text-gray-500"
              th:text="${d.fechaAplicado != null} ? ${#temporals.format(d.fechaAplicado, 'dd/MM/yyyy HH:mm')} : '-'"></td>
          <td class="px-4 py-3">
            <div class="flex flex-wrap gap-2">
              <a th:href="@{/desarrollo/{id}/editar(id=${d.id})}"
                 class="text-blue-600 hover:underline text-xs">Editar</a>
              <a th:if="${d.scriptGenerado != null and !#strings.isEmpty(d.scriptGenerado)}"
                 th:href="@{/desarrollo/{id}/descargar(id=${d.id})}"
                 class="text-indigo-600 hover:underline text-xs">⬇ Script</a>
              <form th:if="${d.estado.name() == 'PENDIENTE'}"
                    th:action="@{/desarrollo/{id}/aplicar(id=${d.id})}" method="post">
                <button type="submit" class="text-green-600 hover:underline text-xs">✔ Aplicado</button>
              </form>
              <form th:if="${d.estado.name() == 'PENDIENTE'}"
                    th:action="@{/desarrollo/{id}/descartar(id=${d.id})}" method="post"
                    onsubmit="return confirm('¿Descartar este cambio?')">
                <button type="submit" class="text-orange-500 hover:underline text-xs">✖ Descartar</button>
              </form>
              <form th:action="@{/desarrollo/{id}/eliminar(id=${d.id})}" method="post"
                    onsubmit="return confirm('¿Eliminar entrada?')">
                <button type="submit" class="text-red-500 hover:underline text-xs">Eliminar</button>
              </form>
            </div>
          </td>
        </tr>
        <tr th:if="${#lists.isEmpty(desarrollos)}">
          <td colspan="5" class="px-4 py-8 text-center text-gray-400">
            No hay entradas de desarrollo todavía.
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</section>
</body>
</html>
ENDHTML

cat > "$TMPL/form.html" << 'ENDHTML'
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/base :: layout(~{::title}, ~{::section})}">
<head><title>Desarrollo — FASI</title></head>
<body>
<section>
  <h2 class="text-2xl font-bold text-gray-800 mb-6"
      th:text="${desarrollo.id == null} ? 'Nueva entrada de desarrollo' : 'Editar entrada'"></h2>
  <div class="bg-white rounded-xl shadow p-8 max-w-3xl">
    <form th:action="${desarrollo.id == null} ? @{/desarrollo} : @{/desarrollo/{id}(id=${desarrollo.id})}"
          th:object="${desarrollo}" method="post" class="space-y-4">
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">Título *</label>
        <input type="text" th:field="*{titulo}" required
               class="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-gray-800"/>
        <p th:if="${#fields.hasErrors('titulo')}" th:errors="*{titulo}"
           class="text-red-500 text-xs mt-1"></p>
      </div>
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">Descripción / Prompt</label>
        <textarea th:field="*{descripcion}" rows="5"
                  placeholder="Describe aquí qué cambios quieres implementar..."
                  class="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-gray-800 font-mono text-sm"></textarea>
      </div>
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">Estado</label>
        <select th:field="*{estado}"
                class="w-full border border-gray-300 rounded-lg px-3 py-2">
          <option th:each="e : ${estados}" th:value="${e}" th:text="${e}"></option>
        </select>
      </div>
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">Script generado</label>
        <textarea th:field="*{scriptGenerado}" rows="12"
                  placeholder="#!/bin/bash&#10;# Pega aquí el script generado por la IA..."
                  class="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-gray-800 font-mono text-xs bg-gray-50"></textarea>
      </div>
      <div class="flex gap-3 pt-2">
        <button type="submit"
                class="bg-gray-900 text-white px-6 py-2 rounded-lg hover:bg-gray-700 transition">
          Guardar
        </button>
        <a th:href="@{/desarrollo}"
           class="px-6 py-2 rounded-lg border border-gray-300 hover:bg-gray-50 transition">
          Cancelar
        </a>
      </div>
    </form>
  </div>
</section>
</body>
</html>
ENDHTML

# ── Menú sidebar + móvil ──────────────────────────────────────
echo "🔗 Añadiendo Desarrollo al menú en base.html..."
BASEFILE="src/main/resources/templates/layout/base.html"

# Insertar después de la línea de Fotos en ambos bloques nav
sed -i 's|<a th:href="@{/fotos}".*🖼️.*</a>|&\n        <a th:href="@{/desarrollo}" class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-gray-700 transition">🛠️ <span>Desarrollo</span></a>|g' "$BASEFILE"

# ── Dashboard index.html ──────────────────────────────────────
echo "🏠 Añadiendo Desarrollo al dashboard..."
INDEXFILE="src/main/resources/templates/index.html"

sed -i 's|</div>.*</section>|    <a th:href="@{/desarrollo}"\n       class="bg-white rounded-xl shadow p-6 hover:shadow-md transition flex items-center gap-4">\n      <span class="text-4xl">🛠️</span>\n      <div><p class="font-semibold text-gray-800">Desarrollo</p><p class="text-sm text-gray-500">Historial de cambios IA</p></div>\n    </a>\n  </div>\n</section>|' "$INDEXFILE"

echo ""
echo "✅ Módulo Desarrollo implantado correctamente."
echo "   → Recuerda hacer mvn spring-boot:run para levantar con la nueva migración."

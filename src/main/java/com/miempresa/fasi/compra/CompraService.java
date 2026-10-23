package com.miempresa.fasi.compra;

import com.miempresa.fasi.contexto.Usuario;
import com.miempresa.fasi.contexto.UsuarioActualService;
import com.miempresa.fasi.contexto.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompraService {

    private final ItemCompraRepository repo;
    private final UsuarioRepository usuarioRepo;
    private final UsuarioActualService usuarioActualService;

    public List<ItemCompra> listarTodos() {
        Usuario u = usuarioActualService.obtenerUsuarioActual();
        return repo.findByUsuarioIdOrderByCompradoAscCreatedAtDesc(u.getId());
    }

    @Transactional
    public List<String> anadirElementos(List<String> nombres, UUID usuarioId) {
        List<String> agregados = new ArrayList<>();
        Usuario u = (usuarioId != null) 
                ? usuarioRepo.getReferenceById(usuarioId) 
                : usuarioActualService.obtenerUsuarioActual();

        for (String raw : nombres) {
            String nombre = capitalizar(raw.trim());
            if (nombre.isBlank()) continue;

            Optional<ItemCompra> existente = repo.findByUsuarioIdAndNombreIgnoreCase(u.getId(), nombre);
            if (existente.isPresent()) {
                ItemCompra item = existente.get();
                if (item.isComprado()) {
                    item.setComprado(false);
                    repo.save(item);
                }
            } else {
                ItemCompra nuevo = new ItemCompra();
                nuevo.setUsuario(u);
                nuevo.setNombre(nombre);
                nuevo.setComprado(false);
                repo.save(nuevo);
            }
            agregados.add(nombre);
        }
        return agregados;
    }

    @Transactional
    public List<String> quitarElementos(List<String> nombres, UUID usuarioId) {
        List<String> borrados = new ArrayList<>();
        UUID uid = (usuarioId != null) ? usuarioId : usuarioActualService.obtenerUsuarioActual().getId();

        for (String raw : nombres) {
            String q = raw.trim().toLowerCase();
            if (q.isBlank()) continue;

            List<ItemCompra> matches = repo.buscarCoincidencias(uid, q);
            for (ItemCompra it : matches) {
                borrados.add(it.getNombre());
                repo.delete(it);
            }
        }
        return borrados;
    }

    @Transactional
    public void toggleComprado(UUID id) {
        ItemCompra item = repo.findById(id).orElseThrow();
        item.setComprado(!item.isComprado());
        repo.save(item);
    }

    @Transactional
    public void eliminarPorId(UUID id) {
        repo.deleteById(id);
    }

    private String capitalizar(String str) {
        if (str == null || str.isEmpty()) return "";
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
}

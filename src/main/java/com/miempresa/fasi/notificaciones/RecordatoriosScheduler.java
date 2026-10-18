package com.miempresa.fasi.notificaciones;

import com.miempresa.fasi.calendario.Evento;
import com.miempresa.fasi.calendario.EventoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecordatoriosScheduler {

    private final EventoRepository eventoRepo;
    private final EmailService emailService;

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void procesarRecordatorios() {
        LocalDateTime ahora = LocalDateTime.now();
        LocalDate hoy = ahora.toLocalDate();
        LocalDate manana = hoy.plusDays(1);

        List<Evento> candidatos = eventoRepo.findCandidatosRecordatorio(hoy, manana);
        if (candidatos == null || candidatos.isEmpty()) return;

        for (Evento e : candidatos) {
            LocalTime horaEvento = (e.getHoraInicio() != null) ? e.getHoraInicio() : LocalTime.of(9, 0);
            LocalDateTime momentoEvento = LocalDateTime.of(e.getFechaInicio(), horaEvento);
            LocalDateTime momentoAviso = momentoEvento.minusMinutes(e.getRecordatorioMinutos());

            if (!ahora.isBefore(momentoAviso) && ahora.isBefore(momentoEvento.plusHours(2))) {
                boolean enviado = emailService.enviarRecordatorioEvento(e);
                if (enviado) {
                    e.setRecordatorioEnviado(true);
                    eventoRepo.save(e);
                }
            }
        }
    }
}

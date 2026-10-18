package com.miempresa.fasi.notificaciones;

import com.miempresa.fasi.calendario.Evento;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${fasi.recordatorios.email-destino:ramosrequejomiguel@gmail.com}")
    private String emailDestinoPredeterminado;

    @Value("${fasi.recordatorios.remitente:onboarding@resend.dev}")
    private String emailRemitente;

    public boolean enviarRecordatorioEvento(Evento e) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(new InternetAddress(emailRemitente, "FASI Calendario"));
            helper.setTo(emailDestinoPredeterminado);
            helper.setSubject("⏰ Recordatorio: " + e.getTitulo());

            String horaTexto = (e.getHoraInicio() != null) 
                    ? e.getHoraInicio().toString() 
                    : (e.isTodoDia() ? "Todo el día" : "Sin hora especificada");

            String fechaTexto = e.getFechaInicio().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

            String colorBanner = (e.getTipo() != null && e.getTipo().getColor() != null) 
                    ? e.getTipo().getColor() 
                    : "#4f46e5";

            String html = """
                <!DOCTYPE html>
                <html>
                <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f3f4f6; padding: 20px; margin: 0;">
                  <table width="100%%" border="0" cellspacing="0" cellpadding="0">
                    <tr>
                      <td align="center">
                        <table width="560" border="0" cellspacing="0" cellpadding="0" style="background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 12px rgba(0,0,0,0.08);">
                          <tr>
                            <td style="background-color: %s; padding: 24px 28px; color: #ffffff;">
                              <span style="font-size: 11px; text-transform: uppercase; font-weight: bold; letter-spacing: 0.5px; opacity: 0.9;">FASI — Recordatorio</span>
                              <h1 style="margin: 6px 0 0 0; font-size: 20px; font-weight: bold;">%s</h1>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding: 28px;">
                              <table width="100%%" style="border-collapse: collapse; font-size: 14px; color: #374151;">
                                <tr>
                                  <td style="padding: 8px 0; font-weight: bold; width: 110px; color: #6b7280;">📅 Fecha:</td>
                                  <td style="padding: 8px 0; font-weight: 600;">%s</td>
                                </tr>
                                <tr>
                                  <td style="padding: 8px 0; font-weight: bold; color: #6b7280;">🕒 Hora:</td>
                                  <td style="padding: 8px 0; font-weight: 600;">%s</td>
                                </tr>
                                %s
                                %s
                                %s
                              </table>

                              <div style="margin-top: 24px; padding-top: 18px; border-top: 1px solid #e5e7eb; text-align: center;">
                                <a href="https://fasi.1gusto.com/calendario" style="display: inline-block; background-color: #4f46e5; color: #ffffff; padding: 10px 24px; border-radius: 8px; text-decoration: none; font-size: 13px; font-weight: bold;">Abrir Calendario</a>
                              </div>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(
                    colorBanner,
                    e.getTitulo(),
                    fechaTexto,
                    horaTexto,
                    (e.getLugar() != null && !e.getLugar().isBlank()) ? "<tr><td style='padding: 8px 0; font-weight: bold; color: #6b7280;'>📍 Lugar:</td><td style='padding: 8px 0;'>" + e.getLugar() + "</td></tr>" : "",
                    (e.getPersonasRelacionadas() != null && !e.getPersonasRelacionadas().isBlank()) ? "<tr><td style='padding: 8px 0; font-weight: bold; color: #6b7280;'>👥 Personas:</td><td style='padding: 8px 0;'>" + e.getPersonasRelacionadas() + "</td></tr>" : "",
                    (e.getDescripcion() != null && !e.getDescripcion().isBlank()) ? "<tr><td style='padding: 8px 0; font-weight: bold; color: #6b7280;'>📝 Notas:</td><td style='padding: 8px 0;'>" + e.getDescripcion() + "</td></tr>" : ""
                );

            helper.setText(html, true);
            mailSender.send(message);
            log.info("📧 Recordatorio enviado a {} para el evento '{}'", emailDestinoPredeterminado, e.getTitulo());
            return true;
        } catch (Exception ex) {
            log.warn("⚠️ No se pudo enviar el correo de recordatorio para '{}': {}", e.getTitulo(), ex.getMessage());
            return false;
        }
    }
}

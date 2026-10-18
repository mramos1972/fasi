-- Flag para evitar el reenvío múltiple de recordatorios
ALTER TABLE eventos ADD COLUMN IF NOT EXISTS recordatorio_enviado BOOLEAN DEFAULT FALSE NOT NULL;

CREATE INDEX IF NOT EXISTS idx_eventos_recordatorio_pendiente 
ON eventos (recordatorio_enviado, fecha_inicio) 
WHERE recordatorio_minutos IS NOT NULL AND recordatorio_enviado = FALSE;

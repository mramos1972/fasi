-- Constantes armónicas de Bonanza (IHM) para cálculo de mareas
CREATE TABLE marea_constante (
    id          SERIAL PRIMARY KEY,
    nombre      VARCHAR(10)    NOT NULL UNIQUE,  -- M2, S2, N2...
    amplitud    DOUBLE PRECISION NOT NULL,        -- H en metros
    fase        DOUBLE PRECISION NOT NULL,        -- g en grados
    velocidad   DOUBLE PRECISION NOT NULL,        -- ω en grados/hora (constante universal)
    activo      BOOLEAN DEFAULT TRUE
);

-- Nivel medio del mar en Bonanza
INSERT INTO marea_constante (nombre, amplitud, fase, velocidad) VALUES
    ('Z0',  1.980, 0.000,   0.000000),
    ('M2',  1.491, 109.5,  28.984104),
    ('S2',  0.538, 138.2,  30.000000),
    ('N2',  0.310,  89.7,  28.439730),
    ('K1',  0.092, 196.3,  15.041069),
    ('O1',  0.071, 163.8,  13.943036),
    ('M4',  0.088, 267.4,  57.968208),
    ('MS4', 0.045, 301.1,  58.984104);

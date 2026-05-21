# 📅 FASI — Patrones de Recurrencia en Lenguaje Natural

Este documento lista **50+ formas** de expresar eventos recurrentes en español, clasificadas por tipo y con ejemplos de cómo el sistema las interpreta.

---

## ✅ EVENTOS ÚNICOS (NO RECURRENTES)

### Regla clave: "el [día]" SIN "todos" ni plural

| # | Texto | Interpretación |
|---|-------|----------------|
| 1 | `tutoría el lunes` | Próximo lunes, evento único |
| 2 | `claustro el viernes` | Próximo viernes, evento único |
| 3 | `cita médica el jueves` | Próximo jueves, evento único |
| 4 | `reunión el martes a las 10` | Próximo martes 10:00, único |
| 5 | `cumpleaños de Rocío el 15 de marzo` | 15 marzo, evento único |
| 6 | `viaje el sábado` | Próximo sábado, único |
| 7 | `dentista el miércoles` | Próximo miércoles, único |

---

## 🔄 RECURRENCIA SEMANAL

### Tipo 1: "todos los [día]" / "cada [día]"

| # | Texto | Patrón | Días | Límite |
|---|-------|--------|------|--------|
| 8 | `todos los lunes` | WEEKLY | MONDAY | ❌ Pedir |
| 9 | `cada viernes` | WEEKLY | FRIDAY | ❌ Pedir |
| 10 | `todos los martes` | WEEKLY | TUESDAY | ❌ Pedir |
| 11 | `cada jueves` | WEEKLY | THURSDAY | ❌ Pedir |
| 12 | `todos los miércoles` | WEEKLY | WEDNESDAY | ❌ Pedir |

### Tipo 2: "los [día]" (plural implícito)

| # | Texto | Patrón | Días | Límite |
|---|-------|--------|------|--------|
| 13 | `los lunes` | WEEKLY | MONDAY | ❌ Pedir |
| 14 | `los viernes` | WEEKLY | FRIDAY | ❌ Pedir |
| 15 | `los sábados` | WEEKLY | SATURDAY | ❌ Pedir |
| 16 | `los domingos` | WEEKLY | SUNDAY | ❌ Pedir |

### Tipo 3: Múltiples días

| # | Texto | Patrón | Días | Límite |
|---|-------|--------|------|--------|
| 17 | `gimnasio los martes y jueves` | WEEKLY | TUESDAY, THURSDAY | ❌ Pedir |
| 18 | `todos los lunes y miércoles` | WEEKLY | MONDAY, WEDNESDAY | ❌ Pedir |
| 19 | `los martes, jueves y viernes` | WEEKLY | TUESDAY, THURSDAY, FRIDAY | ❌ Pedir |
| 20 | `cada lunes y viernes` | WEEKLY | MONDAY, FRIDAY | ❌ Pedir |

### Tipo 4: Con mes específico

| # | Texto | Patrón | Días | Límite |
|---|-------|--------|------|--------|
| 21 | `todos los lunes de junio` | WEEKLY | MONDAY | ✅ 30 junio |
| 22 | `cada viernes de agosto` | WEEKLY | FRIDAY | ✅ 31 agosto |
| 23 | `los martes de julio` | WEEKLY | TUESDAY | ✅ 31 julio |
| 24 | `todos los miércoles de septiembre` | WEEKLY | WEDNESDAY | ✅ 30 sept |

### Tipo 5: Con límite explícito

| # | Texto | Patrón | Días | Límite |
|---|-------|--------|------|--------|
| 25 | `todos los lunes hasta agosto` | WEEKLY | MONDAY | ✅ 31 agosto |
| 26 | `los viernes hasta el 31 de diciembre` | WEEKLY | FRIDAY | ✅ 31 dic |
| 27 | `cada martes hasta fin de año` | WEEKLY | TUESDAY | ✅ 31 dic |
| 28 | `gimnasio los lunes y jueves hasta julio` | WEEKLY | MONDAY, THURSDAY | ✅ 31 julio |

### Tipo 6: Con rango de meses

| # | Texto | Patrón | Días | Límite |
|---|-------|--------|------|--------|
| 29 | `desde junio hasta agosto los viernes` | WEEKLY | FRIDAY | ✅ 31 agosto |
| 30 | `los lunes desde julio hasta septiembre` | WEEKLY | MONDAY | ✅ 30 sept |
| 31 | `de marzo a mayo todos los miércoles` | WEEKLY | WEDNESDAY | ✅ 31 mayo |

---

## 📆 RECURRENCIA DIARIA

| # | Texto | Patrón | Límite |
|---|-------|--------|--------|
| 32 | `todos los días de julio` | DAILY | ✅ 31 julio |
| 33 | `cada día de agosto` | DAILY | ✅ 31 agosto |
| 34 | `todos los días desde el 1 de junio hasta el 30 de agosto` | DAILY | ✅ 30 agosto |
| 35 | `cada día de vac
aciones` | DAILY | ❌ Pedir |
| 36 | `diario en julio` | DAILY | ✅ 31 julio |

---

## 🗓️ RECURRENCIA MENSUAL

### Tipo 1: Día fijo del mes

| # | Texto | Patrón | Día | Límite |
|---|-------|--------|-----|--------|
| 37 | `el día 15 de cada mes` | MONTHLY | 15 | ❌ Pedir |
| 38 | `todos los días 10 de cada mes` | MONTHLY | 10 | ❌ Pedir |
| 39 | `cada día 1 del mes` | MONTHLY | 1 | ❌ Pedir |
| 40 | `el 25 de cada mes` | MONTHLY | 25 | ❌ Pedir |

### Tipo 2: Día relativo (primer/último X del mes)

| # | Texto | Patrón | Día | Posición | Límite |
|---|-------|--------|-----|----------|--------|
| 41 | `cada primer lunes de cada mes` | MONTHLY_RELATIVE | MONDAY | 1 | ❌ Pedir |
| 42 | `cada último viernes del mes` | MONTHLY_RELATIVE | FRIDAY | -1 | ❌ Pedir |
| 43 | `el segundo martes de cada mes` | MONTHLY_RELATIVE | TUESDAY | 2 | ❌ Pedir |
| 44 | `cada tercer jueves del mes` | MONTHLY_RELATIVE | THURSDAY | 3 | ❌ Pedir |
| 45 | `el cuarto miércoles de cada mes` | MONTHLY_RELATIVE | WEDNESDAY | 4 | ❌ Pedir |

### Tipo 3: Con meses específicos

| # | Texto | Patrón | Día | Meses | Límite |
|---|-------|--------|-----|-------|--------|
| 46 | `el día 10 de enero, marzo y mayo` | MONTHLY | 10 | 1,3,5 | ✅ 31 mayo |
| 47 | `cada día 5 de junio, julio y agosto` | MONTHLY | 5 | 6,7,8 | ✅ 31 agosto |

---

## 📅 RECURRENCIA ANUAL

| # | Texto | Patrón | Día | Mes | Límite |
|---|-------|--------|-----|-----|--------|
| 48 | `todos los 25 de diciembre` | YEARLY | 25 | 12 | ✅ 5 años |
| 49 | `cada 15 de marzo` | YEARLY | 15 | 3 | ✅ 5 años |
| 50 | `el 1 de enero de cada año` | YEARLY | 1 | 1 | ✅ 5 años |
| 51 | `todos los 14 de febrero` | YEARLY | 14 | 2 | ✅ 5 años |

---

## 🔀 CASOS ESPECIALES

### Respuestas de límite aceptadas

| # | Texto usuario | Interpretación |
|---|---------------|----------------|
| 52 | `hasta agosto` | 31 de agosto del año actual/siguiente |
| 53 | `hasta el 31 de julio` | 31 de julio |
| 54 | `hasta el 31/12` | 31 de diciembre |
| 55 | `hasta fin de año` | 31 de diciembre |
| 56 | `indefinido` | 31 de diciembre (fin de año) |
| 57 | `sin límite` | 31 de diciembre (fin de año) |
| 58 | `hasta diciembre` | 31 de diciembre |

---

## ⚠️ CASOS AMBIGUOS RESUELTOS

| Texto | ¿Único o Recurrente? | Razón |
|-------|---------------------|-------|
| `tutoría el lunes` | ✅ ÚNICO | "el [día]" sin "todos" → próxima ocurrencia |
| `los lunes` | 🔄 RECURRENTE | Plural implica repetición |
| `todos los lunes` | 🔄 RECURRENTE | "todos" explícito |
| `claustro el viernes` | ✅ ÚNICO | "el [día]" sin "todos" |
| `claustros los viernes` | 🔄 RECURRENTE | Plural + "los" |
| `gimnasio martes y jueves` | 🔄 RECURRENTE | Múltiples días implica repetición |

---

## 🎯 REGLAS DE ORO

1. **"el [día]" SIN "todos"** → ÚNICO (próxima ocurrencia)
2. **"todos los [día]" / "cada [día]"** → RECURRENTE
3. **"los [días]" (plural)** → RECURRENTE
4. **Múltiples días** → RECURRENTE
5. **Sin límite explícito** → PREGUNTAR al usuario
6. **Con mes específico** → Límite = último día del mes
7. **"indefinido" / "sin límite"** → Hasta fin de año

---

## 📊 RESUMEN ESTADÍSTICO

- **Eventos únicos**: 7 patrones
- **Recurrencia semanal**: 24 patrones
- **Recurrencia diaria**: 5 patrones
- **Recurrencia mensual**: 11 patrones
- **Recurrencia anual**: 4 patrones
- **Respuestas de límite**: 7 patrones
- **TOTAL**: **58 patrones cubiertos**

---

## 🚀 PRÓXIMAS MEJORAS (FUTURO)

### Patrones avanzados (requieren más desarrollo)

| # | Texto | Complejidad | Notas |
|---|-------|-------------|-------|
| 59 | `cada dos semanas los lunes` | Alta | Requiere contador de semanas |
| 60 | `cada 15 días` | Alta | Requiere cálculo de intervalos |
| 61 | `todos los lunes excepto festivos` | Muy Alta | Requiere calendario de festivos |
| 62 | `cada trimestre` | Media | Requiere definir trimestres |
| 63 | `todos los martes hasta final de curso` | Alta | Requiere contexto de "curso escolar" |

---

**Autor**: FASI Development Team  
**Versión**: 1.0  
**Fecha**: 21 mayo 2026

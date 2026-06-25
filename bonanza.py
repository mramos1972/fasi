import requests
import numpy as np
import pandas as pd
from datetime import datetime
from scipy.interpolate import CubicSpline
import utide

BASE_URL = "https://ideihm.covam.es/api-ihm/getmarea"
ID_BONANZA = 37

# ── 1. DESCARGA ───────────────────────────────────────────────────
def descargar_hilo(year):
    registros = []
    for mes in range(1, 13):
        params = {
            "request": "gettide",
            "id": ID_BONANZA,
            "format": "json",
            "month": f"{year}{mes:02d}"
        }
        r = requests.get(BASE_URL, params=params, timeout=15)
        mareas = r.json()["mareas"]["datos"]["marea"]
        for m in mareas:
            dt = datetime.strptime(
                f"{m['fecha']} {m['hora']}", "%Y-%m-%d %H:%M"
            )
            registros.append({"datetime": dt, "altura": float(m["altura"])})
        print(f"  Mes {mes:02d}/{year}: {len(mareas)} registros")
    df = pd.DataFrame(registros).sort_values("datetime").reset_index(drop=True)
    print(f"  Total: {len(df)} registros\n")
    return df

# ── 2. INTERPOLAR A SERIE HORARIA ────────────────────────────────
def interpolar_horaria(df_hilo):
    t0 = df_hilo["datetime"].iloc[0]
    t_hilo_h = np.array([
        (dt - t0).total_seconds() / 3600.0
        for dt in df_hilo["datetime"]
    ])
    h_hilo = df_hilo["altura"].values

    cs = CubicSpline(t_hilo_h, h_hilo)

    t_grid_h = np.arange(0.0, t_hilo_h[-1] + 1.0, 1.0)
    h_grid   = cs(t_grid_h)

    fechas = pd.date_range(
        start=t0,
        periods=len(t_grid_h),
        freq="1h"
    )

    print(f"  Serie interpolada: {len(t_grid_h)} puntos horarios")
    print(f"  Desde: {fechas[0]}  Hasta: {fechas[-1]}\n")
    return fechas, h_grid

# ── 3. ANÁLISIS ARMÓNICO ─────────────────────────────────────────
def analisis_armonico(fechas, h, lat=36.80):
    # Días ordinales Python (desde 0000-12-31)
    t_num = np.array([
        d.toordinal() + d.hour/24.0 + d.minute/1440.0
        for d in fechas
    ])

    coef = utide.solve(
        t_num,
        h,
        lat=lat,
        method="ols",
        conf_int="none",    # evita bug del periodograma en utide 0.3.1
        constit="auto",     # utide elige constituyentes según duración de serie
        epoch="python",     # días desde 0000-12-31
        verbose=False
    )
    return coef

# ── 4. RESULTADOS ─────────────────────────────────────────────────
def mostrar_resultados(coef):
    print("\n" + "="*58)
    print("  CONSTANTES ARMÓNICAS — Bonanza (Sanlúcar de Barrameda)")
    print("  Referencia: Cero Hidrográfico | Horas UTC")
    print("="*58)
    print(f"  {'Constituyente':<12} {'Amplitud H (m)':>14} {'Fase g (°)':>12}")
    print("-"*58)

    idx = np.argsort(coef.A)[::-1]
    for i in idx:
        nombre = coef.name[i]
        amp    = coef.A[i]
        fase   = coef.g[i]
        if amp > 0.003:
            print(f"  {nombre:<12} {amp:>14.4f} m {fase:>10.2f}°")

    print("="*58)
    print(f"\n  Z₀ (nivel medio) = {coef.mean:.4f} m sobre C.H.")

    nombres = list(coef.name)
    A = coef.A

    def amp(n):
        return A[nombres.index(n)] if n in nombres else 0.0

    M2 = amp("M2"); S2 = amp("S2")
    K1 = amp("K1"); O1 = amp("O1")
    F  = (K1 + O1) / (M2 + S2) if (M2 + S2) > 0 else 0

    tipo = ("Semidiurna pura" if F < 0.25
            else "Mixta predominantemente semidiurna" if F < 1.5
            else "Diurna")

    print(f"  Factor de forma  F = {F:.3f}  → {tipo}")
    if S2 > 0:
        print(f"  Relación M2/S2     = {M2/S2:.2f}")
    print(f"  Carrera sizigia    ≈ {2*(M2+S2):.3f} m")
    print(f"  Carrera cuadratura ≈ {2*abs(M2-S2):.3f} m")
    print("="*58)

# ── 5. MAIN ───────────────────────────────────────────────────────
if __name__ == "__main__":
    print("=== Bonanza — Análisis Armónico 2026 ===\n")

    print("Paso 1: Descargando datos hilo...")
    df_hilo = descargar_hilo(2026)

    print("Paso 2: Interpolando a serie horaria...")
    fechas, h_hora = interpolar_horaria(df_hilo)

    print("Paso 3: Calculando constantes armónicas...")
    coef = analisis_armonico(fechas, h_hora)

    mostrar_resultados(coef)

# 2026-11-13 — Presentador: keep-if-ready y más margen (Legion TB323FU)

**Cliente:** Lenovo Legion Tab TB323FU, panel a 165 Hz. **Host:** Vibepollo, "Clue Bitrate Test", HEVC 150 Mbps a 165 fps (P1), capturando de la Sunshine Virtual Display a 660 Hz.
**Red:** PrismPulse (Puppis S1, canal 149 a 80 MHz) en todas las corridas.
**Protocolo:** `tools/sweep.sh 120 "forceh265 150000 165"` con `CLUE_PRESENT=<variante>`, 3 rondas intercaladas en orden rotado (base, ready, ready-margin / ready, ready-margin, base / ready-margin, base, ready). Datos en `traces/present-ab/`.

## Variantes (`am start --es clue_present <nombre>`)

| Variante | Seguridad | Margen | keepIfReady |
|---|---|---|---|
| `base` (lo que había) | 0,5 ms | percentil 95 | no |
| `ready` | 0,5 ms | percentil 95 | sí |
| `ready-margin` | 1,5 ms | percentil 99 | sí |

**keepIfReady:** si el timestamp del host pide pasar un frame a un vsync posterior, pero el frame ya salió del decoder a tiempo para el vsync que le toca, se queda en ese vsync.

## Por qué

- Las trazas de las corridas de routers (`2026-11-13-router-ab-puppis-vs-home.md`) mostraron que el 85% de las repeticiones *programadas* por el presentador (472 de 558) eran evitables: el frame estaba listo con 3–5 ms de mediana para el vsync anterior.
- El host marca cerca del 1–3% de los frames con el timestamp corrido 0,25 vsync (pares de intervalos 1,25 / 0,75), que es un tick del display virtual de 660 Hz.
- Pero esas repeticiones son solo una parte de los tirones en pantalla: el resto son frames que salen del decoder después de su deadline, casi siempre por entrega tardía desde host o red. En el simulador (`tools/presenter-sim.sh`), keepIfReady solo bajaba los errores de cadencia 20–30%; con más margen, 30–73%. Más margen **sin** keepIfReady empeoraba.

## Resultado en la tablet (cadencia real, SurfaceFlinger)

| Corrida | Intervalos de 1 vsync | Intervalos de 2 vsync | sd | Completo→pantalla p50 / p95 / p99 | queue→present p50 |
|---|---|---|---|---|---|
| base-1 | 98,2% | 1,8% | 1,30 ms | 17,64 / 20,93 / 21,82 | 16,89 |
| base-2 | 98,9% | 1,1% | 0,83 ms | 16,14 / 19,21 / 20,40 | 14,93 |
| base-3 | 99,3% | 0,7% | 0,74 ms | 17,35 / 21,43 / 22,12 | 16,64 |
| ready-1 | 99,3% | 0,7% | 0,72 ms | 16,47 / 19,22 / 19,90 | 15,32 |
| ready-2 | 99,2% | 0,8% | 0,87 ms | 16,08 / 19,22 / 19,79 | 15,25 |
| ready-3 | 98,8% | 1,2% | 0,87 ms | 16,53 / 19,25 / 20,06 | 15,86 |
| ready-margin-1 | 99,9% | 0,1% | 0,20 ms | 18,28 / 21,06 / 22,30 | 17,19 |
| ready-margin-2 | 99,8% | 0,2% | 0,64 ms | 17,79 / 21,19 / 22,01 | 16,73 |
| ready-margin-3 | 99,8% | 0,2% | 0,72 ms | 18,58 / 22,29 / 23,10 | 18,36 |

(ms. Ping PC → tablet durante las 9 corridas: p99 6,1–6,8 ms, más de 20 ms en 0–0,05%. Temperatura tras cada corrida: CPU 35–46 °C.)

| Variante | Intervalos irregulares (media, rango) | Completo→pantalla p50 (media) |
|---|---|---|
| base | 1,2% (0,7–1,8%) | 17,0 ms |
| ready | 0,9% (0,7–1,2%) | 16,4 ms |
| ready-margin | **0,17% (0,1–0,2%)** | 18,2 ms |

## Lectura

- **`ready-margin` baja los tirones unas 7 veces** respecto de `base` (0,17% contra 1,2%), y los rangos no se pisan en ninguna de las tres rondas. Cuesta **alrededor de 1,2 ms** de latencia en la mediana.
- **`ready` sola no se distingue de `base`** con tres corridas: los rangos se superponen. La latencia es igual o algo menor.
- La temperatura no explica la variación entre corridas.

## Límites

- Tres corridas de 120 s por variante, una sola red y un solo contenido (ruido a 150 Mbps).
- No se midió en la tablet "más margen sin keepIfReady"; solo el simulador indica que empeora.
- No se midió en la POCO Pad ni en la Wi-Fi de casa, donde hay más frames tarde por red.
- El default se validó con una sola corrida extra (ver abajo).

## Decisión

`ready-margin` es el presentador por defecto desde el 2026-11-13 (`PresentPolicy.DEFAULT_VARIANT`). `--es clue_present base` vuelve a la política anterior para comparar, y `--es clue_present default` borra una variante guardada por un script de medición.

Verificación con la app sin variante guardada (`traces/20261113-144142`, la traza registra `present=ready-margin`): 99,8% de intervalos de 1 vsync, sd 0,34 ms, completo→pantalla p50 18,88 ms.

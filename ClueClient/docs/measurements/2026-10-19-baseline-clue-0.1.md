# Línea base — Clue 0.1 contra Artemis (2026-10-19)

Primera medición con la instrumentación de H1. Es la referencia contra la que se mide cada cambio de H2 en adelante.

## Condiciones

- **Host:** Vibepollo 2.0.1 en modo Desktop, con el encoder por defecto y `capture = ddx`.
  - Con la captura WGC (`sunshine_wgc_capture.exe`) el host mandaba unos 33 fps, así que se forzó DXGI.
  - El preset P4 + AQ + split de la configuración anterior no está activo en esta medición.
- **Stream:** 2560×1600 a 120 fps, 80 Mbps HEVC; el host entregó 120,02 fps.
- **Clientes:**
  - Clue 0.1.0 (release, defaults de la Tarea 3, pacing Equilibrado, traza activa);
  - Artemis (`com.limelight.noir`) con 120 fps, 80 Mbps, ULL apagado y pacing Equilibrado.
- **Contenido:** una página de movimiento sintético a pantalla completa (barras, ruido y contador de frames), igual en todas las corridas.
- **Corridas:** 120 s cada una, alternadas: Clue, Artemis, Artemis, Clue.

## Cadencia real (SurfaceFlinger, `tools/cadence.sh 120`)

| Corrida | 1 vsync | 2 vsync | 3 vsync | 4+ vsync | Desvío |
|---|---|---|---|---|---|
| Clue #1 | 92,5 % | 7,5 % | 0,0 % | 0,0 % | 2,19 ms |
| Artemis #1 | 88,8 % | 11,2 % | 0,0 % | 0,0 % | 2,63 ms |
| Artemis #2 | 90,6 % | 9,3 % | 0,0 % | 0,1 % | — |
| Clue #2 | 89,7 % | 10,2 % | 0,0 % | 0,1 % | — |
| **Promedio** | **Clue 91,1 % · Artemis 89,7 %** | | | | |

La diferencia entre clientes es menor que la variación entre corridas. Los intervalos de 2 vsync se concentran en tramos de varios segundos, que es el patrón del batido entre los 120,02 fps del host y los 120,00 Hz del panel.

## Latencia de Clue por etapa (`TraceAnalyze`, ms)

Sesiones `20261019-161300` (18 063 frames) y `20261019-162058` (16 642 frames).

| Etapa | p50 | p95 | p99 | máx. |
|---|---|---|---|---|
| Red + ensamblado (primer paquete → frame completo) | 0,36–0,37 | 1,04–1,07 | 1,84–1,88 | 7,2 |
| Completo → entrada al decoder | 0,05 | 0,07–0,11 | 0,26–0,48 | 5,4 |
| Decodificación (entrada → salida) | 11,8–12,0 | 14,1–14,3 | 15,5–23,6 | 42,1 |
| Salida del decoder → release | 11,2–11,7 | 14,8–15,1 | 15,9–16,4 | 20,0 |
| Completo → pantalla (programada) | 19,8–20,3 | 24,2–24,3 | 25,1–28,0 | 49,7 |

- 0 PTS sin correlacionar y 0 etapas negativas en ambas sesiones.
- La red pesa menos de 2 ms en p99. Casi toda la latencia está en el decoder (~12 ms) y en la espera hasta el release (~11 ms).
- **Margen principal para H2:** la espera salida → release. Con pacing Equilibrado el frame decodificado espera el vsync siguiente más un frame de colchón.
- La cadencia de la traza da 100 % a 1 vsync porque mide lo **programado**; la real es la de SurfaceFlinger.
- Audio: 0 paquetes descartados en estas corridas, que no tenían sonido. En una sesión anterior con audio se descartó ~4 % con underruns; queda para H4.

## Pendiente

- Overhead de CPU de la traza (activa contra inactiva): no se midió todavía. Hay que alternar `clue_trace_enabled` desde la UI y comparar `top` en corridas de 120 s.

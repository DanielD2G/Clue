# 2026-11-08 — Lenovo TB323FU: HEVC vs AV1, 80/150/300 Mbps

**Cliente:** Lenovo Legion Tab TB323FU (Snapdragon 8 Elite Gen 5, `canoe`, Android 16), panel 3040×1904 a 120 Hz (soporta hasta 165 Hz y HDR10).
Wi-Fi 6 a 5 GHz, enlace de 1729 Mbps de subida y 2401 Mbps de bajada.
**Host:** Vibepollo, NVENC P1, split-frame forzado, `av1_mode = 3`.
**Contenido:** app "Clue Bitrate Test", ruido que cambia todo el frame. Satura el bitrate pedido, así que es el peor caso para la red y el decodificador.
**Stream:** 3040×1904 a 120 fps, presentador Clue. Una corrida de 120 s por configuración.

Decodificadores: `c2.qti.hevc.decoder.low_latency` y `c2.qti.av1.decoder.low_latency`. El fabricante declara 100 Mbps para HEVC y 80 Mbps para AV1, pero ambos funcionaron a 300 Mbps.

| Config | Cadencia SF 1x | Red+ensamblado p99 | Decode p50 / p95 / p99 | Completo→pantalla p50 / p95 / p99 |
|---|---|---|---|---|
| HEVC 80  | 99.8% | 1.16 | 2.88 / 3.83 / 4.66 | 17.3 / 20.0 / 20.9 |
| AV1 80   | 99.8% | 1.80 | 3.45 / 5.77 / 8.48 | 16.4 / 20.9 / 21.5 |
| HEVC 150 | 99.7% | 2.06 | 2.93 / 4.48 / 5.87 | 16.1 / 18.9 / 19.3 |
| AV1 150  | 99.8% | 0.04 | 3.38 / 4.10 / 4.65 | 14.8 / 19.4 / 20.1 |
| HEVC 300 | 99.8% | 3.37 | 2.99 / 6.42 / 7.52 | 15.5 / 20.6 / 21.9 |
| AV1 300  | 99.6% | 4.13 | 3.38 / 9.40 / 10.66 | 17.6 / 26.0 / 27.7 |

(ms. Las trazas están en `traces/20261108-1447..1501`; el resumen en `traces/sweep-tb323fu.txt`.)

## Conclusiones

- **El host recibe los 300 Mbps:** Vibepollo registra "Client Requested bitrate is [300000kbps]". Antes, moonlight-common-c recortaba lo pedido a 100 Mbps.
- **Cadencia:** casi perfecta en todas las configuraciones (≥ 99.6% de intervalos de 1 vsync). La tablet nueva no tiene el problema de la POCO Pad.
- **AV1 decodifica unos 0.5 ms más lento en p50** que HEVC. A 300 Mbps, además, empeora mucho la cola (p95 de 9.4 ms contra 6.4 ms). Por eso "Auto" sigue en HEVC, y AV1 queda disponible con "Preferir AV1".
- **La cola de red crece con el bitrate** (p99 de 1.2 a 3.4 ms con HEVC). 300 Mbps funciona, pero agrega cola sin beneficio visible con este contenido.
- **Límites de la prueba:**
  - Es una sola corrida por configuración, así que los valores sueltos de p99 tienen ruido (por ejemplo, AV1 80 contra AV1 150).
  - No mide calidad de imagen. La ventaja de AV1 (más calidad por bit) aparece con contenido real a bitrate bajo o medio, y eso queda pendiente.

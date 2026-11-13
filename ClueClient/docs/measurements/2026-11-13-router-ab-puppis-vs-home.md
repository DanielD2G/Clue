# 2026-11-13 — Puppis S1 contra el router de casa (Legion TB323FU)

**Cliente:** Lenovo Legion Tab TB323FU, Clue con la selección de camino más corto (rama `fix/shortest-path-host-address`).
**Host:** Vibepollo en la RTX 5080, "Clue Bitrate Test", HEVC 150 Mbps, 165 fps (P1).
**Redes:**
- **Puppis:** PrismPulse, canal 149 a 80 MHz. PC por USB-Ethernet (`192.168.137.102`).
- **Casa:** Alexneitor, canal 36 a 160 MHz. PC por cable al router (`192.168.0.137`).
Clue eligió solo la dirección directa en cada red.
**Protocolo:** `tools/sweep.sh 120 "forceh265 150000 165"`, intercalado Puppis, casa, Puppis, casa. En paralelo, ping ICMP del PC a la tablet a 50 Hz (se descartan los primeros 10 s). Script y datos crudos en `traces/router-ab/`.

## Resultados

| Corrida | Enlace bajada | Completo→pantalla p50 / p95 / p99 | Red+ensamblado p95 / p99 | Cadencia SF 1x | No presentados | Ping p50 / p95 / p99 / máx | Ping > 20 ms | Pérdida ping |
|---|---|---|---|---|---|---|---|---|
| puppis-1 | ~1050 Mbps | 17.19 / 19.60 / 20.24 | 0.78 / 2.04 | 99.4% | 9 | 3.8 / 5.7 / 7.7 / 42 | 0.05% | 0.02% |
| home-1 | ~2400 Mbps | 17.68 / 21.04 / 21.92 | 1.26 / 2.38 | 98.9% | 11 | 3.7 / 6.4 / 10.2 / 71 | 0.05% | 0% |
| puppis-2 | ~1030 Mbps | 17.49 / 21.80 / 22.76 | 1.15 / 2.54 | 98.8% | 10 | 3.8 / 5.7 / 7.5 / 103 | 0.08% | 0.02% |
| home-2 | ~2400 Mbps | 17.44 / 20.52 / 21.60 | 0.75 / 1.77 | 98.2% | 52 | 3.8 / 8.3 / 19.7 / 90 | 0.95% | 0.14% |

(ms. "Completo→pantalla" es la métrica programada de TraceAnalyze; ver la nota en `2026-11-08-tb323fu-165hz.md`. Trazas: `traces/20261113-133216`, `-133717`, `-134019`, `-134313`.)

## Lectura

- **En la mediana no hay diferencia.** El frame completo llega a la pantalla en 17.2–17.7 ms en las dos redes, y el ping p50 es 3.7–3.8 ms en ambas. A 150 Mbps, los dos enlaces sobran: el de casa tiene más capacidad (160 MHz), pero no se usa.
- **La cola es más estable en el Puppis.** El ping p95/p99 da 5.7 / 7.5–7.7 ms en el Puppis contra 6.4–8.3 / 10.2–19.7 ms en casa. home-2 tuvo 0.95% de pings por encima de 20 ms, 0.14% de pérdida, 52 frames sin presentar y la peor cadencia (98.2%). El canal 149 del Puppis está libre; el canal 36 de casa lo comparten el resto de los equipos de la casa y los AP vecinos.
- La variación entre corridas de la misma red (puppis-1 contra puppis-2) es del mismo orden que la diferencia entre redes en la mediana. Solo la cola de ping de casa se separa de forma consistente.

## Límites

- Dos corridas por red, en un solo horario. La congestión de la red de casa depende de la hora.
- El ping es RTT ICMP desde el PC: aproxima la latencia de red, pero no es la latencia de un sentido del video.
- Los p99 de queue→present de 90–291 ms son artefactos de las capturas de frametimeline (igual que el 2026-11-08), no frames reales.

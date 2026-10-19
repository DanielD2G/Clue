# H3 — línea base de entrada (2026-10-19)

## Condiciones

- **Clue:** build de H3 con las Tareas 1 y 2 y pacing "Clue adaptativo", en la POCO Pad con cargador de pared.
- **Host:** Vibepollo 2.0.1 en Desktop, con `capture = ddx`.
  - Stream medido en el reloj de la tablet: 120,055 fps.
  - Panel: 119,998 Hz, lo que da un batido de ~17 s.
- **Página del host:** `tools/host/gamepad-photon.html` a pantalla completa en Chrome, en un perfil aislado.
- **Prueba:** `tools/photon.sh 100 clue`, 2 corridas.
  - Mando 0 sintético, A cada 400 ms.
  - 2 pulsaciones de calentamiento sin medir: Chrome solo expone el mando a la página después de su primera pulsación.

## Resultados

| Tramo | p50 | p95 | p99 | Fuente |
|---|---|---|---|---|
| Evento de mando → paquete en el aire (Clue) | 0,34 ms | 0,46 ms | 0,75 ms | `inputwire.csv`, 206 paquetes, 0 negativos |
| Botón → frame en cola en la tablet | 33,1–33,2 ms | 45,8–47,2 ms | 48,2–49,5 ms | `photon.sh`, 100/100 detectadas en cada corrida |
| Frame en cola → en pantalla | 20,1 ms | — | 28,4 ms | `measure.sh` (SurfaceFlinger) |
| Decodificación | 10,5 ms | 12,0 ms | 17,3 ms | traza |
| **Botón → fotón (suma de p50)** | **~53 ms** | | | |

- **Fotón real:** `PixelCopy` sobre un `SurfaceView` lee el último buffer **encolado**. El fotón real se calcula sumándole la cola → presentación.
- **Remanente:** quedan ~22 ms entre el aire y el frame encolado, sin contar la decodificación. Ese tiempo es red de ida, host (la página lee el mando en `requestAnimationFrame`, Chrome compone, captura y encode, ~2,3 ms de proceso del host) y red de vuelta.

## Qué implica para H3

- **Envío en Clue:** el tramo de Clue ya está por debajo de 1 ms. Las Tareas 4 (prioridad del hilo) y 6 (envío inmediato) pueden ganar como máximo ~0,3 ms. Es menos que la variación entre corridas de botón → frame (±0,1 ms en p50, ±1,5 ms en p95), así que no se pueden validar con esta medición.
- **Pérdida de paquetes:** la Tarea 5 (estado no confiable con redundancia) no cambia nada sin pérdida, porque hoy hay 0 % de paquetes perdidos. Su valor está en el p99 con pérdida de Wi-Fi: un paquete confiable perdido espera la retransmisión de ENet, que tarda un RTT o más.
- **Dónde están los tramos grandes:** host y red (~22 ms), presentador más SurfaceFlinger (~20 ms) y decodificación (~10,5 ms).

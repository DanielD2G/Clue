# Clue — diseño del cliente

**Fecha:** 2026-10-19
**Estado:** borrador para revisión
**Base:** moonlight-android v12.2 (`b48494c`), con moonlight-common-c `874ac95`
**Reglas del proyecto:** [AGENTS.md](../../../AGENTS.md)

## 1. Intención

Clue es el cliente de game streaming para Android de este proyecto. Hoy se juega con Artemis contra Vibepollo, y medimos dónde se pierde la experiencia:

- **El host y la red ya están bien.** El procesamiento del host ronda los 3 ms y la red los 4 ms, sin pérdida.
- **Lo que queda está en el cliente:**
  - La decodificación y la presentación suman unos 12 ms más unos 14 ms.
  - Los clicks de audio vienen de que el cliente descarta paquetes.
  - Hay un temblor periódico: el reloj del host y el del panel se desfasan de a poco. Con el pacing "Menor latencia" provoca rachas de 40–50 % de frames repetidos.
  - Las opciones de serie obligan a elegir entre fluidez y latencia.

Clue existe para dar **la mejor experiencia posible en nuestro hardware, con latencia casi nula**. Las prioridades, en este orden, son:

1. performance;
2. baja latencia, sobre todo en los mandos;
3. exprimir el hardware;
4. usabilidad.

Ver [AGENTS.md](../../../AGENTS.md).

## 2. Objetivos

| # | Objetivo | Cómo se verifica |
|---|---|---|
| O1 | La app se llama **Clue** y convive con Moonlight y Artemis | Package id propio, nombre e ícono de Clue, instalable junto a los otros |
| O2 | Medición propia por frame, confiable | Traza por frame con todas las etapas en un único reloj, sincronizado con el host |
| O3 | Fluidez de "Equilibrado" con latencia de "Menor latencia" | Cadencia y latencia mejores o iguales que Artemis en las dos métricas (§8) |
| O4 | Mandos y entrada con latencia mínima | Del evento de Android al envío: p99 ≤ 1 ms; sin bloqueo detrás de un paquete perdido |
| O5 | Audio sin cortes y con baja latencia | Cero "pending audio" descartado en 10 min de juego; latencia de salida medida y menor que la de AudioTrack |
| O6 | Todo funciona contra hosts de serie | Vibepollo 2.0.x, Sunshine y Apollo sin cambios |
| O7 | Base lista para extensiones con el host | Mecanismo de negociación opcional, sin uso todavía (§6, H2) |

## 3. No objetivos, por ahora

- **Host propio.** Va en un proyecto aparte.
- **Anti-batido cooperativo**, en el que el host ajusta su fase al vsync del cliente. Va en una segunda spec; acá solo se deja la base (O7).
- **PyroWave** queda en evaluación (§7). No se implementa en esta spec.
- **Rediseño de la UI.** En esta etapa solo cambian la marca y los defaults.
- **iOS, macOS, Android TV y otros clientes.**
- **Optimizar dispositivos distintos de la POCO Pad**, más allá de no romperlos.

## 4. Anti-objetivos (aunque "funcione", esto sería un fracaso)

- Ganar latencia a cambio de tirones, o fluidez a cambio de latencia, sin datos que lo justifiquen.
- Romper la compatibilidad con hosts de serie.
- Exigir que el usuario toque ajustes para obtener lo mejor. Los defaults tienen que ser óptimos para nuestro hardware.
- Divergir tanto de upstream que traer actualizaciones de Moonlight se vuelva impracticable.
- Aceptar una optimización sin medición, o con una corrida de menos de 120 s.

## 5. Restricciones

- **Hardware de referencia:** POCO Pad (`2405CPCFBG`).
  - Snapdragon 7s Gen 2 y panel 2560×1600 de 30–120 Hz, sin refresco variable.
  - Decodificador `c2.qti.hevc.decoder.low_latency`, calificado para 4K60 y 70 Mbps.
  - Sin AV1 y sin HDR10.
  - HyperOS 3 sobre Android 16.
- **Host de referencia:** Vibepollo 2.0.x en una RTX 5080, en modo Desktop, con P4 + AQ + split-frame, 2560×1600 a 120 fps y 80 Mbps.
- **Licencia:** GPL-3.0. El código queda público en `DanielD2G/Clue` y se conserva la atribución a Moonlight.
- **Repo:** solo `ClueClient/`. Upstream entra con `git subtree` y moonlight-common-c es un submódulo.
- **Plataforma:** compileSdk y targetSdk los de upstream (36/37), y NDK r29. El código nuevo va en Java/Kotlin o en C dentro de `moonlight-core`.
- **Una app sin privilegios no puede usar SCHED_FIFO.** Las prioridades se manejan con nice, `THREAD_PRIORITY_*` y ADPF.

## 6. Qué se construye: hitos

Cada hito termina con una medición contra la línea base (§8). Un cambio que no mejora la medición o la empeora se revierte, aunque "debería" ayudar.

### H0 — Identidad y build

- Package id propio y `applicationId` nuevo. El nombre visible es **Clue**, con ícono propio.
- El nombre de cliente que ve el host es "Clue (POCO Pad)" o similar, en lugar del modelo del dispositivo.
- Build reproducible de debug y release firmado con una clave propia. Script de build e instalación por `adb`.
- Pantalla "Acerca de" con la atribución a Moonlight y la GPL.
- **Defaults de fábrica para el hardware de referencia:**
  - resolución nativa, 120 fps y 80 Mbps HEVC;
  - presentador de Clue (H2);
  - overlay de estadísticas accesible.

### H1 — Instrumentación (antes de optimizar)

- **Registro por frame en un único dominio de reloj** (`CLOCK_MONOTONIC`), con estos instantes:
  1. primer paquete recibido;
  2. último paquete recibido;
  3. frame reensamblado;
  4. entrada al decodificador (`queueInputBuffer`);
  5. salida del decodificador;
  6. release;
  7. presentación real (`onFrameRendered`, que se activa: upstream lo tiene apagado);
  8. vsync objetivo.
- Hay que unificar los relojes de common-c (`CLOCK_MONOTONIC_RAW`) y de Java (`CLOCK_MONOTONIC`).
- **Sincronización de reloj con el host** al estilo NTP, sobre el ping que ya existe en el canal de control. Así cada frame queda referenciado al tiempo de captura y envío del host. Si el host de serie no expone su reloj, se reporta solo la parte del cliente; la referencia al host completa llega con la extensión (§6, H6).
- **Registro por evento de entrada:** timestamp del evento de Android y momento en que se encola y se envía.
- **Audio:** frames encolados y presentados, underruns y overruns, y latencia de salida (`AAudioStream_getTimestamp`).
- **Exportación:** archivo binario o CSV por sesión, que se baja por `adb`, más un script de análisis en el PC con las métricas de §8. Overhead del registro: ≤ 1 % de CPU y nada de asignaciones en el camino caliente.
- **Overlay de Clue** que muestre lo que importa: p50 y p99 de cada etapa, frames repetidos y descartados por causa, y estado del audio.

### H2 — Video: presentador alineado al vsync

- Decodificación con `AMediaCodec` en modo **asíncrono**, en un hilo nativo. Se conservan las claves de baja latencia que funcionan en `c2.qti`. Las claves de fence experimentales de Artemis **no** se usan, porque empeoraban la cadencia en este chip.
- Presentador guiado por las **frame timelines de Choreographer**, con `AChoreographer_postExtendedFrameCallback`:
  - en cada vsync se elige el frame decodificado más nuevo que llega al próximo deadline;
  - se libera con su `expectedPresentTime`;
  - nunca hay más de 1 frame de colchón.
- La alternativa a evaluar es `ASurfaceTransaction_setFrameTimeline`.
- **Colchón adaptativo** de 0 o 1 frame según el jitter de llegada medido. Si los frames llegan lejos del deadline, se presenta sin colchón (latencia mínima). Si llegan cerca, que es la zona del batido, se usa 1 frame (fluidez). Así se combina lo mejor de "Menor latencia" y "Equilibrado".
- Stream a **120,000 fps exactos** con `setFrameRate(…, FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)`, verificando que HyperOS no baje la frecuencia a 90 o 60.
- Hilos de recepción, decodificación y presentación con prioridad `URGENT_DISPLAY` y sesión **ADPF** (`PerformanceHintManager`) con el presupuesto de un frame.
- Se mantienen RFI y LTR de upstream, verificando que RFI quede activo en `c2.qti`.

### H3 — Entrada: mandos primero (prioridad máxima del proyecto)

La entrada tiene **prioridad por encima del video y del audio** en todo el cliente: en el aire, en los hilos y en las colas.

Estado actual de upstream, verificado en el código de v12.2:

- Ya hace bien tres cosas:
  - `requestUnbufferedDispatch` incluye `SOURCE_CLASS_JOYSTICK`;
  - cada mando va por su propio canal ENet (`CTRL_CHANNEL_GAMEPAD_BASE + n`);
  - el paquete en cola se actualiza con el estado más nuevo.
- Y tiene tres problemas:
  - el socket ENet de control se crea sin prioridad (*best effort*), mientras que video y audio usan `SO_PRIORITY` 5 y 6;
  - el estado del mando viaja `ENET_PACKET_FLAG_RELIABLE`, con un TODO para pasarlo a no confiable;
  - `sendMessageEnet` puede esperar hasta 10 ms, en pasos de 1 ms, a que un paquete confiable salga.

Cambios:

1. **Prioridad en el aire (WMM AC_VO).**
   - El socket de control/entrada se marca con `SO_PRIORITY` 6–7 y DSCP EF (`IP_TOS` 0xB8), para que el driver Wi-Fi lo mande por la cola de voz.
   - Se verifica en captura que la marca llegue y que no rompa nada contra Vibepollo y Sunshine.
   - Se mide la latencia de subida bajo la carga de video, con y sin la marca.
2. **Sin bloqueo de cabeza de línea.**
   - El estado de los mandos y el mouse relativo viajan **no confiables y secuenciados**: gana el más nuevo.
   - Para resistir pérdidas, el último estado se reenvía cada pocos ms mientras haya entrada activa (intervalo a medir; por ejemplo 4 ms).
   - Los cambios de botón se mandan además por duplicado, en dos datagramas.
   - Se elimina la espera de backpressure de hasta 10 ms para estos paquetes.
   - Se adopta solo si Vibepollo y Sunshine los procesan correctamente; si no, queda el camino confiable con la prioridad del punto 1.
3. **Envío inmediato.** El evento se envía en el mismo hilo en que llega, sin agrupar con otros mandos ni retener. El hilo de entrada tiene la prioridad más alta de la app.
4. **Enlace del mando.**
   - Clue detecta si el mando está conectado por USB, Bluetooth o receptor de 2,4 GHz, y estima su tasa de reportes a partir de los timestamps de los eventos.
   - Muestra la latencia medida y recomienda la mejor conexión disponible: cable o 2,4 GHz a 1000 Hz.
5. **Modo de prueba botón → pantalla, automático.**
   - Clue envía una pulsación sintética y anota el instante.
   - En el host, una página o app liviana que lee el mando virtual (por ejemplo, la Gamepad API del navegador) cambia de color al recibirla.
   - Clue detecta el cambio en el video recibido, con una lectura de una región chica de la superficie.
   - Resultado: latencia de entrada de punta a punta en ms (p50 y p99), sin cámara. Es la métrica oficial de H3.
6. El touch y la lapicera conservan las muestras históricas que ya envía upstream.
7. **Más adelante, con el host:** un canal de entrada propio con timestamps e inyección inmediata (extensión `x-clue-`, segunda spec).

### H4 — Audio

- Se reemplaza el `AudioTrack` bloqueante por **AAudio** en modo `EXCLUSIVE` + `LOW_LATENCY` con callback. Si el dispositivo no lo soporta, se usa `SHARED`.
- Opus decodifica a un ring lock-free que alimenta el callback. El buffer arranca en 2 bursts y se ajusta según los XRuns.
- **Control de deriva:** el reloj del host y el del DAC se desfasan. En lugar de descartar 40 ms de golpe, se corrige de a poco con resampling fino o descarte y duplicado de muestras sueltas.
- Objetivo: ningún paquete descartado por "pending audio" y latencia de salida medida.

### H5 — Red, del lado del cliente

- Recepción con `recvmmsg` por lotes en el hilo de video, con timestamp por lote.
- Buffers de socket dimensionados para la ráfaga de un frame a 150 Mbps.
- Locks de Wi-Fi de baja latencia que ya usa upstream, verificando que el driver los respete.
- Avisar si el teléfono está en 2,4 GHz o con RSSI bajo.

### H6 — Base para extensiones con el host

- Negociación opcional por atributos SDP propios con prefijo `x-clue-`. Los hosts de serie la ignoran.
- No se usa todavía. Prepara la segunda spec:
  - reporte de la fase de vsync al host, contra el batido;
  - feedback de llegada por frame;
  - sincronización de reloj completa con el host.

## 7. Para más adelante

- **PyroWave.** Primero se evalúa si en la POCO Pad rinde mejor que HEVC en latencia, cadencia y calidad dentro de nuestro ancho de banda. Necesita mucho bitrate y LAN rápida. Solo se integra si gana en las mediciones.
- **UI nueva**, en su propia spec cuando el núcleo esté estable.
- **Anti-batido cooperativo**, junto con el host o con un parche a Vibepollo.

## 8. Medición y criterios de aceptación

- **Protocolo.** Mismo contenido en todas las corridas (TestUFO a pantalla completa o una app de frames sintéticos), con el host en modo Desktop.
  - Corridas de **120 s o más**, alternando las variantes A/B en orden aleatorio.
  - Cadencia tomada de `dumpsys SurfaceFlinger --frametimeline -all` más la traza de Clue.
- **Línea base:** Artemis 12.1 con 120 fps, 80 Mbps, ULL apagado y pacing Equilibrado. Medido: 95–96 % de intervalos a 1 vsync y unos 13,7 ms de buffer listo a pantalla.

| Métrica | Objetivo de Clue |
|---|---|
| Intervalos a 1 vsync, 120 s, host de serie | ≥ la línea base, con picos de la fase mala del batido ≤ 5 % |
| Del último paquete a la presentación, p50 | ≤ línea base − 1 frame (≈ 8 ms) |
| Del último paquete a la presentación, p99 | ≤ 2 frames |
| Del evento de entrada al envío, p99 | ≤ 1 ms |
| Botón → pantalla (modo de prueba, H3), p50 / p99 | Se fija con la primera medición; cada cambio de H3 tiene que bajarlo |
| Pérdida de un paquete de mando | No traba la entrada: el siguiente estado llega en ≤ 1 intervalo de reenvío |
| Audio descartado en 10 min | 0 |
| Overhead de la instrumentación | ≤ 1 % de CPU |

Estos números son objetivos iniciales, propuestos por el agente: se ajustan con la primera medición de H1.

## 9. Decisiones tomadas

| Decisión | Quién |
|---|---|
| Base Moonlight 12.2 upstream, no Artemis | Usuario |
| Nombre Clue; repo `DanielD2G/Clue` con solo `ClueClient/` | Usuario |
| Primer alcance solo cliente, contra hosts de serie | Usuario |
| Hardware de referencia primero; generalizar después | Usuario |
| Upstream por `git subtree` conservando el historial | Agente |
| Orden de hitos H0 → H1 → H2 → H3 → H4 → H5 → H6, con H1 antes de toda optimización | Agente |
| Colchón adaptativo de 0 o 1 frame en el presentador | Agente |
| La entrada tiene prioridad máxima, por encima del video y del audio | Usuario |
| Mejoras de H3: AC_VO, no confiable con redundancia, detección del enlace, prueba botón → pantalla | Usuario (aprobado); detalles como el intervalo de reenvío, del agente |
| Prefijo `x-clue-` para las extensiones | Agente |
| Objetivos numéricos de §8 | Agente (provisorios) |

## 10. Lo que queda a criterio de quien implemente

- Java o Kotlin para el código nuevo del lado de Android. Conviene seguir el estilo del archivo que se toque.
- Formato exacto del archivo de trazas y del script de análisis, mientras produzca las métricas de §8.
- El package id concreto, por ejemplo `com.clue.client`, y el diseño del ícono.
- Si el presentador usa `releaseOutputBuffer(timestamp)` o `ASurfaceTransaction`: lo decide la medición de H2.
- Cómo se expone en la UI el colchón adaptativo. Lo deseable es que no sea una opción visible.

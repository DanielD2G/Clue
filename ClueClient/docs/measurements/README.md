# Cómo medir Clue

Ninguna optimización entra sin datos (ver [AGENTS.md](../../AGENTS.md)). Este es el protocolo para comparar variantes de Clue entre sí y contra otros clientes.

## Condiciones fijas

- **Host:** Vibepollo 2.0.x en modo **Desktop**, con `capture = ddx` (con WGC, Vibepollo 2.0.1 entregó unos 33 fps). Anotar en cada medición el preset de encoder activo. El cliente pide 2560×1600 a 120 fps y 80 Mbps HEVC.
- **Contenido:** la misma página de movimiento a pantalla completa en todas las corridas (TestUFO o una página sintética con contador de frames), sin otras ventanas encima.
- **Red:** el Wi-Fi de siempre. Durante las corridas no se usan otras descargas ni otros streams.
- **Tablet:** la pantalla queda encendida durante toda la corrida (opciones de desarrollador → "Mantener la pantalla activa").

## Corridas

- Cada corrida dura **120 s como mínimo**. El batido entre el reloj del host y el del panel tiene un ciclo de alrededor de un minuto, así que una corrida más corta puede caer entera en la fase buena o en la mala.
- Para comparar variantes A y B, hacer como mínimo 2 corridas de cada una, alternadas (A, B, A, B).

## Herramientas

| Herramienta | Qué mide |
|---|---|
| `tools/clue-launch-desktop.sh` | Abre Clue y arranca o reanuda "Desktop" |
| `tools/cadence.sh 120 [package]` | Cadencia **real** de presentación según SurfaceFlinger: % de intervalos de 1, 2, 3 y 4+ vsync. Sirve para cualquier cliente (`com.clue.client`, `com.limelight.noir`, …) |
| Overlay de Clue | Latencias p50/p99 en vivo: red, decodificación, a pantalla y entrada→envío |
| `tools/pull-traces.sh` | Baja las trazas de las sesiones de Clue al PC (carpeta `../traces`) |
| `java tools/TraceAnalyze.java <carpeta>` | Desglose por etapa (n, p50, p95, p99, máx.) de una sesión. Las deltas negativas se reportan aparte |

## Advertencias

- En la POCO Pad, `onFrameRendered` informa la hora de presentación **programada**, no la real. La latencia "a pantalla" de la traza llega entonces hasta el vsync programado, y la cadencia real sale únicamente de `cadence.sh`.
- El "decode time" del overlay de upstream no es una métrica confiable. Hay que usar las líneas de Clue.
- Los eventos inyectados con `adb input` no sirven para medir latencia de entrada: su hora de evento la pone el shell antes de inyectarlos.

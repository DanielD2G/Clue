# Cómo medir Clue

Ninguna optimización entra sin datos (ver [AGENTS.md](../../AGENTS.md)). Este es el protocolo para comparar variantes de Clue entre sí y contra otros clientes.

## Condiciones fijas

- **Host:** Vibepollo 2.0.x en modo **Desktop**, con `capture = ddx` (con WGC, Vibepollo 2.0.1 entregó unos 33 fps). Anotar en cada medición el preset de encoder activo. El cliente pide 2560×1600 a 120 fps y 80 Mbps HEVC.
- **Contenido:** la misma página de movimiento a pantalla completa en todas las corridas (TestUFO o una página sintética con contador de frames), sin otras ventanas encima.
- **Red:** el Wi-Fi de siempre. Durante las corridas no se usan otras descargas ni otros streams.
- **Tablet:** la pantalla queda encendida durante toda la corrida (opciones de desarrollador → "Mantener la pantalla activa").
  - Con cargador de pared y batería por encima del 30 %. El stream continuo consume más de lo que carga el USB del PC: el 2026-10-19 la tablet se apagó por batería en medio de una corrida (`sys.boot.reason = shutdown,battery`). Con batería baja HyperOS también puede limitar el rendimiento.

## Flujo

1. **Filtrar en el PC.** Cada idea para el presentador se prueba primero con `tools/presenter-sim.sh` sobre una sesión grabada. Corre la política real contra un modelo de SurfaceFlinger, en segundos. A la tablet llega solo la variante que gana.
2. **Validar en la tablet.** Usar `tools/measure.sh <modo> [segundos]`: una pasada da cadencia real, cola → presentación real, espera salida → release y salida → pantalla.
3. **Repetir la referencia solo si cambian las condiciones.** Equilibrado se vuelve a medir solo si cambia el host, la red o el contenido. Si no, vale la última medición registrada.

## Corridas

- **Duración: al menos 3 ciclos del batido** entre el reloj del host y el del panel. `measure.sh` informa el batido medido y avisa si la corrida fue más corta.
  - Con el host a 120,02 fps el ciclo dura ~50 s, así que hacen falta 120 s o más.
  - Con el host a 120,2 fps dura ~4,5 s, y 40 s alcanzan.
- **Para comparar variantes A y B:** como mínimo 2 corridas de cada una, alternadas (A, B, A, B).

## Herramientas

| Herramienta | Qué mide |
|---|---|
| `tools/measure.sh <modo> [s] [etiqueta]` | Una corrida completa en el modo de pacing pedido (`clue`, `balanced`, …). Agrega una línea a `../traces/measure-results.txt` |
| `tools/presenter-sim.sh <sesión> [latch ms]` | Reproduce una sesión grabada (`presenter.csv` + `vsync.csv`) con la política del presentador y un modelo de SurfaceFlinger. `-Dskip=k` simula un juego que saltea 1 de cada k frames |
| `tools/clue-launch-desktop.sh` | Abre Clue y arranca o reanuda "Desktop". Con `CLUE_PACING=<modo>` elige antes el modo de pacing |
| `tools/cadence.sh 120 [package]` | Cadencia **real** de presentación según SurfaceFlinger: % de intervalos de 1, 2, 3 y 4+ vsync. Sirve para cualquier cliente (`com.clue.client`, `com.limelight.noir`, …) |
| Overlay de Clue | Latencias p50/p99 en vivo: red, decodificación, a pantalla y entrada→envío |
| `tools/pull-traces.sh` | Baja las trazas de las sesiones de Clue al PC (carpeta `../traces`) |
| `java tools/TraceAnalyze.java <carpeta>` | Desglose por etapa (n, p50, p95, p99, máx.) de una sesión. Las deltas negativas se reportan aparte |

## Advertencias

- En la POCO Pad, `onFrameRendered` informa la hora de presentación **programada**, no la real. La latencia "a pantalla" de la traza llega entonces hasta el vsync programado, y la cadencia real sale únicamente de `cadence.sh`.
- El "decode time" del overlay de upstream no es una métrica confiable. Hay que usar las líneas de Clue.
- Los eventos inyectados con `adb input` no sirven para medir latencia de entrada: su hora de evento la pone el shell antes de inyectarlos.

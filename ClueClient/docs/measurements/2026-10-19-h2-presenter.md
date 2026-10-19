# H2 — presentador Clue contra Equilibrado (2026-10-19)

Primera medición del presentador alineado al vsync, el modo de pacing `clue`, contra el pacer Equilibrado de upstream. Ambos corren sobre el mismo build, con las claves `operating-rate` y qti del decoder activas.

## Condiciones

- Las mismas de la [línea base](2026-10-19-baseline-clue-0.1.md): Vibepollo 2.0.1 en Desktop con `capture = ddx`, 2560×1600, 120 fps y 80 Mbps HEVC, y la misma página de movimiento.
- El host entregó entre 120,1 y 120,2 fps.
- 4 corridas alternadas: Clue, Equilibrado, Equilibrado, Clue. En cada una:
  - 120 s de cadencia (`tools/cadence.sh`);
  - 30 s de cola → presentación real;
  - traza de Clue para la espera salida del decoder → release.

## Métrica nueva: presentación real

`onFrameRendered` no da la hora real de presentación. En el pacer Equilibrado devuelve el `frameTime` del vsync con que se liberó el frame, que ya pasó. Por eso el "a pantalla" de la traza no se puede comparar entre modos.

La hora real sale del frametimeline de SurfaceFlinger. Para cada buffer de la capa del video se toma de **Actual End**, cuando el buffer quedó en cola, a **Actual Present**.

De la salida del decoder a la pantalla:

- **Equilibrado:** espera salida → release (traza) + cola → presentación (SurfaceFlinger).
- **Clue:** libera apenas sale el frame (~1 ms), así que es casi solo su cola → presentación.

## Resultados

| Corrida | 1 vsync | Desvío | Salida → release p50 | Cola → presentación p50 / p99 | Salida → pantalla p50 (aprox.) |
|---|---|---|---|---|---|
| Clue #1 | 99,4 % | 0,65 ms | ~1 ms | 20,64 / 24,46 | ~21,6 ms |
| Equilibrado #1 | 99,8 % | 0,41 ms | 12,65 | 13,94 / 14,67 | ~26,6 ms |
| Equilibrado #2 | 99,6 % | 0,98 ms | 13,27 | 13,84 / 14,69 | ~27,1 ms |
| Clue #2 | 99,4 % | 1,20 ms | 0,93 | 22,94 / 31,41 | ~23,9 ms |

- **Latencia:** Clue baja en promedio **~4,1 ms en p50** desde la salida del decoder hasta la pantalla real.
- **Cadencia:** prácticamente igual a la de Equilibrado, 99,4 % contra 99,7 %.
- **Cola de p99:** mejor en Clue #1 y peor en Clue #2, con unos 3 ms más que Equilibrado. En esa corrida la red tenía ~4 ms de variación y el margen del PLL creció.

## Por qué no es más

- **El piso de SurfaceFlinger** en este equipo, del latch a la presentación, es de 12,33 ms (2 vsync menos 1/2).
- **Equilibrado** absorbe el jitter con un frame entero de colchón (8,33 ms).
- **Clue** lo absorbe con un margen medido de ~2–4 ms más 1 ms de seguridad.
- **Ganancia máxima:** un frame menos ese margen. El margen lo fija el jitter de llegada:
  - decodificación con p95 − p50 ≈ 3 ms;
  - variación de red de 1 a 4 ms.

Para bajar más hay que reducir ese jitter. Es trabajo de H5 (red) y de la evaluación de AMediaCodec nativo y ADPF, no de la política.

## Decisión

- **El criterio del plan no se cumple:** pedía bajar ≥ 5 ms en p50, y la medición da ~4,1 ms.
- **Igual se mantiene `clue` como default.** Las reglas del proyecto ponen la latencia primero, la cadencia no empeora más de 1 punto, y la spec (§8) define esos umbrales como provisionales.
- **Riesgo aceptado:** la cola p99 puede subir unos 3 ms con una red ruidosa.
- **Cómo volver atrás:** basta con elegir "Equilibrado" en Ajustes → Ritmo de cuadros.

## Herramientas

El muestreo de cola → presentación usa el frametimeline de SurfaceFlinger, igual que `tools/cadence.sh`. Queda pendiente pasarlo a `tools/`.

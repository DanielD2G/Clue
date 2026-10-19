# Clue H2 — presentador alineado al vsync: plan de implementación

> **Para agentes:** SUB-SKILL REQUERIDO: usar superpowers:subagent-driven-development (recomendado) o superpowers:executing-plans para implementar este plan tarea por tarea. Los pasos usan checkboxes (`- [ ]`).

**Objetivo:** bajar la espera salida del decoder → release, hoy de ~12 ms p50, sin perder la cadencia de 1 vsync.

**Arquitectura:**
- Un modo de pacing nuevo, **"clue"**, que pasa a ser el default.
- Cada frame se libera **apenas sale del decoder**, con `releaseOutputBuffer(index, t)`, donde `t` es el `expectedPresentationTime` del primer vsync cuyo deadline todavía se alcanza.
- Los timelines salen de `Choreographer.postVsyncCallback` (API 33+).
- Una política pura (`PresentPolicy`) elige ese vsync y decide el colchón adaptativo de 0 o 1 frame:
  - 0 cuando los frames llegan lejos del deadline;
  - 1 cuando el batido de relojes los acerca al borde.
- Si dos frames apuntan al mismo vsync, SurfaceFlinger descarta el más viejo.

**Stack:** Java (estilo del archivo que se toca), Android SDK 37 con API 33+ para el modo nuevo, JUnit 4.

**Spec:** [`docs/superpowers/specs/2026-10-19-clue-client-design.md`](../specs/2026-10-19-clue-client-design.md) §H2. Línea base: [`docs/measurements/2026-10-19-baseline-clue-0.1.md`](../../measurements/2026-10-19-baseline-clue-0.1.md).

## Punto de partida (medido el 2026-10-19)

Ya está en `clue/h0-h1` (commit `3dac8c2a`): claves `operating-rate` y qti en el decoder.

| Medida | Valor |
|---|---|
| Cadencia real | 97,9 % / 99,1 % a 1 vsync |
| Decodificación p50 | ~11 ms; es tiempo real del hardware, el decoder no retiene frames (verificado con huecos de llegada) |
| Salida → release p50 | ~12,3 ms |
| Completo → pantalla p50 | ~20,4 ms |

Lo que causa la espera: el pacer Equilibrado de upstream libera en `doFrame` el frame **más viejo** de una cola de 2. Como el host corre a 120,02 fps y el panel a 120,00 Hz, la cola se queda con un frame extra en régimen.

## Fuera de este plan

Decodificación nativa con `AMediaCodec` asíncrono, sesión ADPF y `ASurfaceTransaction`. Entran solo si después de este plan la medición muestra que el cuello está ahí (spec §10).

## Restricciones globales

- Nada en el camino caliente asigna memoria, bloquea ni espera sin tope. Caminos calientes: decode-out, callback de vsync y release.
- Ninguna cola del pipeline de video crece más allá de un frame.
- Cambios en código upstream: acotados y marcados `// CLUE`, sin reformatear.
- Con API < 33, el valor `clue` se comporta exactamente como `balanced`.
- Los defaults tienen que ser los correctos para la POCO Pad sin que el usuario toque nada.
- Ninguna optimización entra sin medición: corridas de 120 s alternadas contra la línea base. La cadencia se toma de SurfaceFlinger (`tools/cadence.sh`).
- Tests: `./gradlew :app:testNonRootDebugUnitTest`. Build e instalación: `bash tools/build-install.sh release`.

## Review Focus

1. **Panel a otra frecuencia que el stream** (HyperOS baja a 90 o 60 Hz, o stream de 60 fps): los targets nunca retroceden y avanzan con el período real del panel. Test `periodMismatchKeepsTargetsMonotonic` (Tarea 1).
2. **Callbacks de vsync detenidos** (app en segundo plano o PiP) mientras el decoder sigue entregando: con un snapshot viejo, el target sigue siendo futuro y sin overflow. Test `staleSnapshotStillTargetsFuture` (Tarea 1).
3. **Reconfiguración o reset del codec:** `reset()` borra el último target, el colchón y la ventana de bordes. Test `resetClearsState` (Tarea 1).
4. **Recuperación del codec:** upstream espera la bandera `CR_FLAG_CHOREOGRAPHER` cuando hay hilo de Choreographer, así que el callback de vsync del modo `clue` tiene que llamar a `doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER)` igual que `doFrame`. Verificación manual en la Tarea 3: rotar o salir y volver al stream sin que se cuelgue.
5. **Dispositivos con API < 33 y `clue` guardado:** se comportan como `balanced`. Lo cubre la condición de la Tarea 3; se revisa en el diff.

---

### Tarea 1: `PresentPolicy`, la política pura

**Archivos:**
- Crear: `app/src/main/java/com/clue/video/PresentPolicy.java`
- Test: `app/src/test/java/com/clue/video/PresentPolicyTest.java`

**Interfaces que produce:**

```java
public final class PresentPolicy {
    public static final long DEFAULT_SAFETY_NS = 1_000_000L;  // margen antes del deadline
    public static final long DEFAULT_GUARD_NS = 1_500_000L;   // zona de borde a cada lado del deadline
    public static final int ENTER_WINDOW = 120, ENTER_EDGES = 12; // >=12 bordes en los últimos 120 frames -> colchón 1
    public static final int EXIT_WINDOW = 240;                    // 0 bordes en los últimos 240 frames -> colchón 0

    public PresentPolicy(long safetyNs, long guardNs);
    /** Hilo de vsync. Snapshot del timeline preferido; periodNs <= 0 conserva el período anterior. */
    public void onVsync(long deadlineNs, long expectedPresentNs, long periodNs);
    /** Hilo del renderer, al salir cada frame del decoder. Devuelve el tiempo para releaseOutputBuffer, o 0 si todavía no hubo vsync. */
    public long targetFor(long nowNs);
    public int depth();            // 0 o 1
    public int edgesInWindow();    // bordes en los últimos ENTER_WINDOW frames, para el overlay
    public long depthSwitches();
    public void reset();           // conserva el snapshot de vsync y borra el resto
}
```

**Algoritmo** (los tests no lo determinan del todo):
- **Snapshot entre hilos:** seqlock con `volatile long seq` y campos `long`. Sin asignar memoria.
- **Elegir el vsync:** con el snapshot `(D0, P0, T)`, calcular `k = max(0, ceilDiv(now + safety - D0, T))` y `slack = D0 + k*T - now - safety`, con `slack` en `[0, T)`.
- **Bordes:** un frame es "borde" si `slack < guard || slack > T - guard`. Se registra en un ring fijo de `EXIT_WINDOW` booleans con contadores incrementales para las dos ventanas.
- **Colchón:**
  - con colchón 0, si los bordes en `ENTER_WINDOW` llegan a `ENTER_EDGES` o más, pasa a 1;
  - con colchón 1, si hay 0 bordes en `EXIT_WINDOW`, vuelve a 0;
  - en cada cambio, `depthSwitches++`.
- **Target:** `target = P0 + (k + depth) * T` y después `target = max(target, lastTarget)`. Se guarda `lastTarget` y se devuelve `target`.

- [ ] **Paso 1: escribir los tests que fallan.** Constantes: `T = 8_333_333L`, `D0 = 100_000_000L`, `P0 = 110_000_000L`, y `new PresentPolicy(1_000_000L, 1_500_000L)`.
  - `noVsyncReturnsZero`: `targetFor(5)` da `0`.
  - `picksNextAchievableTimeline`: después de `onVsync(D0, P0, T)`:
    - `targetFor(95_000_000L)` da `P0`;
    - `targetFor(99_500_000L)`, que no deja margen, da `P0 + T`.
  - `lateCallbackExtrapolates`: `targetFor(D0 + 3*T + 500_000L)` da `P0 + 4*T`.
  - `cleanArrivalsStayAtDepth0`: 500 frames en `now = D0 + i*T - 5_000_000L` dan `depth() == 0` y `depthSwitches() == 0`.
  - `edgeArrivalsSwitchToDepth1`: 120 frames, uno de cada 10 en `now = D0 + i*T - 1_200_000L` (borde) y el resto como en el test anterior:
    - después, `depth() == 1`;
    - el siguiente frame limpio en el índice `i` devuelve `P0 + (i + 1)*T`.
  - `quietWindowReturnsToDepth0`: partiendo del estado anterior, 240 frames limpios dan `depth() == 0` y `depthSwitches() == 2`.
  - `periodMismatchKeepsTargetsMonotonic`:
    - snapshot con `T = 11_111_111L`;
    - 300 frames cada 8 333 333 ns;
    - cada target es ≥ el anterior y > `now`.
  - `staleSnapshotStillTargetsFuture`: `now = D0 + 10_000_000_000L` da un target > `now` y < `now + 3*T`.
  - `resetClearsState`: después de llegar a colchón 1:
    - `reset()` deja `depth() == 0` y `edgesInWindow() == 0`;
    - `targetFor` vuelve a dar `P0` para `now = 95_000_000L`, porque `lastTarget` se borra.
- [ ] **Paso 2: ejecutarlos.** `./gradlew :app:testNonRootDebugUnitTest --tests com.clue.video.PresentPolicyTest`. Esperado: FAIL, porque `PresentPolicy` no existe.
- [ ] **Paso 3: implementar `PresentPolicy`** con el algoritmo de arriba.
- [ ] **Paso 4: ejecutar los tests otra vez.** Esperado: PASS. También pasa la suite completa.
- [ ] **Paso 5: commit.** `feat(h2): adaptive vsync target policy for the Clue presenter`

### Tarea 2: modo de pacing "clue" como default

**Archivos:**
- Modificar `app/src/main/java/com/clue/defaults/ClueDefaults.java`: `FRAME_PACING = "clue"`.
- Modificar `app/src/test/java/com/clue/defaults/ClueDefaultsTest.java`: `assertEquals("clue", ClueDefaults.FRAME_PACING)`.
- Modificar `app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java`:
  - `public static final int FRAME_PACING_CLUE = 4;`;
  - en el parseo, `"clue"` → `FRAME_PACING_CLUE`;
  - migración en `applyClueFirstRunDefaults`.
- Modificar `res/values/arrays.xml`: primer item en names (`@string/pacing_clue`) y en values (`clue`).
- Modificar `res/values/strings.xml`: `pacing_clue` = "Clue adaptive (recommended)".
- Modificar `res/values-es/strings.xml`: `pacing_clue` = "Clue adaptativo (recomendado)".

**Interfaces que produce:** `PreferenceConfiguration.FRAME_PACING_CLUE` (int 4), que guarda `prefs.framePacing`.

- [ ] **Paso 1: actualizar el test de defaults.** `./gradlew :app:testNonRootDebugUnitTest --tests com.clue.defaults.ClueDefaultsTest`. Esperado: FAIL.
- [ ] **Paso 2: cambiar `ClueDefaults`, el parseo, los arrays y los strings.**
- [ ] **Paso 3: migración.** En `applyClueFirstRunDefaults`, si `!prefs.getBoolean("clue_pacing_v2", false)`:
  - si `frame_pacing` no existe o vale `"balanced"`, guardar `"clue"`;
  - guardar `clue_pacing_v2 = true`.

  La elección explícita de otro modo se respeta.
- [ ] **Paso 4: ejecutar la suite.** Esperado: PASS.
- [ ] **Paso 5: commit.** `feat(h2): Clue adaptive frame pacing option, default and migration`

### Tarea 3: integrar el presentador en el renderer

**Archivos:**
- Crear `app/src/main/java/com/clue/video/ClueVsyncSource.java` (API 33+): `Choreographer.VsyncCallback` que se re-postea en cada vsync.
  - Toma el timeline preferido: `getDeadlineNanos()` y `getExpectedPresentationTimeNanos()`.
  - El período es la diferencia entre los dos primeros timelines si hay al menos 2; si no, `0`.
  - Llama a `policy.onVsync(...)` y después a un `Runnable` de recuperación.
  - `start()` y `stop()` corren en el looper del hilo de Choreographer existente.
- Modificar `app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java`, con todo marcado `// CLUE`:
  - `clueMode = prefs.framePacing == FRAME_PACING_CLUE && SDK_INT >= 33`.
  - Si el modo es `clue` y la API es menor a 33, se usa el camino de `FRAME_PACING_BALANCED` en todas las comparaciones.
  - `startChoreographerThread()`:
    - en `clueMode` crea el `HandlerThread` con `Process.THREAD_PRIORITY_URGENT_DISPLAY`;
    - arranca `ClueVsyncSource` en lugar de `postFrameCallback`;
    - la recuperación llama a `doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER)`.
  - Hilo del renderer:
    - `Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)` al inicio de `run()`, en todos los modos;
    - en `clueMode` usa la rama "no balanced", que vacía las salidas y se queda con la más nueva;
    - libera con `long t = policy.targetFor(System.nanoTime()); releaseOutputBuffer(lastIndex, t != 0 ? t : System.nanoTime())`;
    - registra la traza con `onRelease(pts, System.nanoTime(), t)`.
  - En la configuración del codec, junto al `reset` de la traza, llamar a `policy.reset()`.
  - Detener `ClueVsyncSource` donde hoy se quita el frame callback.
  - Overlay: agregar una línea `Presentador Clue: colchón %d · bordes %d/120 · cambios %d` solo en `clueMode`.

- [ ] **Paso 1: implementar `ClueVsyncSource` y los cambios del renderer.**
- [ ] **Paso 2: compilar y ejecutar la suite.** `bash tools/build-install.sh release`. Esperado: `Success` y tests en PASS.
- [ ] **Paso 3: verificar en la tablet.** Ejecutar `tools/clue-launch-desktop.sh`.
  - El overlay muestra la línea del presentador.
  - El logcat muestra `Configuring with format` sin excepciones.
  - Salir y volver al stream 3 veces seguidas: el stream no se cuelga.
- [ ] **Paso 4: commit.** `feat(h2): release frames at decode-out against Choreographer vsync timelines`

### Tarea 4: medición y registro

**Archivos:**
- Crear: `docs/measurements/2026-10-19-h2-presenter.md`

- [ ] **Paso 1: corridas.** 4 corridas de 120 s, alternadas: `clue`, `balanced`, `balanced`, `clue`.
  - El modo se cambia desde la UI de Clue.
  - Cada corrida: `tools/cadence.sh 120 com.clue.client`, luego `tools/pull-traces.sh` y `TraceAnalyze`.
- [ ] **Paso 2: criterio de aceptación.** El modo `clue` queda como default solo si cumple las dos condiciones:
  - el promedio de completo → pantalla p50 baja **≥ 5 ms** frente a `balanced`;
  - el promedio a 1 vsync no cae más de 1 punto frente a `balanced`.

  Si no las cumple, se revierte el default de la Tarea 2 a `"balanced"` y se documenta el motivo.
- [ ] **Paso 3: documentar.** Tabla de cadencia y latencia por etapa con el mismo formato que la línea base, más los cambios de colchón observados.
- [ ] **Paso 4: commit.** `docs(measurements): H2 presenter against balanced`

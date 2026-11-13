# Autotuner de bitrate: plan de implementación

> **Para agentes:** SUB-SKILL REQUERIDO: superpowers:subagent-driven-development o superpowers:executing-plans. Los pasos usan checkboxes (`- [ ]`).

**Objetivo:** Clue encuentra solo el mayor bitrate estable para cada PC y cada red, y lo sugiere.

**Arquitectura:**
- La lógica pura se escribe con TDD:
  - `BitrateSearch`, la búsqueda;
  - `StepVerdict`, que decide si un escalón es estable;
  - `TunerStore`, que guarda el resultado por PC y red.
- `Game` gana un modo de prueba. Corre una sesión a un bitrate dado, mide y devuelve las métricas.
- `BitrateTunerActivity` orquesta los escalones, muestra el progreso y el resultado final.

**Spec:** [`docs/superpowers/specs/2026-10-20-bitrate-tuner-design.md`](../specs/2026-10-20-bitrate-tuner-design.md).

## Restricciones globales

- Reglas de `ClueClient/AGENTS.md`:
  - cambios en el código upstream acotados y marcados `// CLUE`;
  - nada que asigne memoria en el camino caliente del stream;
  - textos en `values/` (inglés) y `values-es/` (español).
- La app del host se llama exactamente `Clue Bitrate Test`, lo instala `tools/host/install-bitrate-test.ps1`.
- Tests: desde `ClueClient/`, `./gradlew :app:testNonRootDebugUnitTest`. Build: `./gradlew :app:assembleNonRootDebug`.
- La interfaz sigue el estilo del launcher nuevo de `main`, ya está en esta rama.

## Review Focus

1. **El host corta la sesión a mitad de un escalón** (la app se cierra o se pierde la conexión): ese escalón cuenta como no estable, por "sesión interrumpida", y la prueba sigue o termina sin colgarse.
2. **El usuario sale con atrás durante la prueba:** se cancela todo, se cierra la app del host y no se guarda ningún resultado.
3. **Host sin la app de prueba:** se avisa cómo instalarla y no se sugiere nada.
4. **Red sin SSID legible** (sin permiso de ubicación o por cable): se usa la clave `unknown` o `wired` y la prueba funciona igual.
5. **El primer escalón ya falla:** no se sugiere nada que no se haya medido estable. Se avisa que la red no sostiene ni 20 Mbps y se ofrece usar 10.

---

### Tarea 1: `BitrateSearch`

**Archivos:**
- Crear `app/src/main/java/com/clue/tuner/BitrateSearch.java`.
- Test en `app/src/test/java/com/clue/tuner/BitrateSearchTest.java`.

**Interfaz:**

```java
public final class BitrateSearch {
    public static final int START_KBPS = 20000, CAP_KBPS = 150000;
    public BitrateSearch();
    public int nextKbps();                    // -1 cuando terminó
    public void report(int kbps, boolean stable);
    public int bestStableKbps();              // 0 si ninguno fue estable
    public static int suggest(int bestKbps, int decoderMaxKbps); // best*0,85 redondeado hacia abajo a 5000; acotado por decoderMax si > 0; 0 si best == 0
}
```

**Comportamiento:**
- **Subida:** escalones `20000 → ×1,3`, redondeados a 1000, hasta el primer escalón no estable o hasta `CAP_KBPS`.
- **Bisección:** entre el último estable y el primer no estable, hasta que `(fail - pass) / pass < 0,10`.

- [ ] Tests:
  - con todo estable, llega al tope (150000) y termina;
  - con el primer escalón inestable, `bestStableKbps() == 0` y termina;
  - con un umbral simulado de 61000 (estable si ≤ 61000), el resultado queda entre 55000 y 61000, con 12 escalones o menos;
  - `suggest(61000, 70000)` da 50000, `suggest(100000, 70000)` da 70000 y `suggest(0, 0)` da 0.
- [ ] Ejecutar: FAIL. Implementar. Ejecutar: PASS. Commit `feat(tuner): bitrate search`.

### Tarea 2: `StepVerdict` y `TunerStore`

**Archivos:**
- Crear `com/clue/tuner/StepMetrics.java`: una clase de valores con:
  - `requestedKbps`;
  - `framesReceived`, `framesLost`, `framesPresented`;
  - `fpsTarget`, `fpsReceived`;
  - `decodeP95Ms`, `rttP95Ms`;
  - `interrupted`.
- Crear `com/clue/tuner/StepVerdict.java`, con `static Result evaluate(StepMetrics m, double baselineRttP95Ms)`.
  - `Result` lleva `boolean stable` y `String reason`.
  - Umbrales de la spec:
    - pérdida < 0,5 %;
    - decode p95 ≤ 2 × (1000 / fpsTarget);
    - no presentados < 2 %;
    - RTT p95 ≤ baseline + 15;
    - fps recibidos ≥ 0,9 × fpsTarget;
    - `interrupted` hace que no sea estable.
  - Si `baselineRttP95Ms` es menor que 0 (es el primer escalón), no se aplica el criterio de RTT.
- Crear `com/clue/tuner/TunerStore.java` (SharedPreferences `clue_tuner`):
  - `save(pcUuid, network, suggestedKbps, summary)`;
  - `Integer lookup(pcUuid, network)`;
  - `markSkipped(pcUuid, network)` y `boolean shouldOffer(pcUuid, network)`;
  - `static String networkKey(Context)`: SSID entre comillas, o `wired` / `unknown`.
- Tests: cada umbral por separado, en su borde (justo debajo pasa, justo encima falla), y `shouldOffer` antes y después de guardar o saltear. Este último va con Robolectric si ya está en el proyecto; si no, se extrae la lógica de las claves a una función pura y se testea esa.
- Commit `feat(tuner): step verdict and result store`.

### Tarea 3: modo de prueba en `Game`

**Archivos:** modificar `com/limelight/Game.java`, marcado `// CLUE`.
- **Extras de lanzamiento:**
  - `clue_tune_kbps` (int): pisa `prefConfig.bitrate` en esta sesión, sin guardarlo;
  - `clue_tune_warmup_ms` (default 4000) y `clue_tune_measure_ms` (default 12000).
- **Con el modo activo:**
  - sin traza ni overlay;
  - al cumplir el calentamiento, toma un snapshot de las estadísticas del decoder:
    - `VideoStats`: `totalFramesReceived`, `framesLost` y frames presentados;
    - decode p95 de `TraceStats` o del decoder;
  - durante la medición muestrea `MoonBridge.getEstimatedRttInfo()` cada 100 ms en un array fijo;
  - al terminar calcula el delta y arma un `StepMetrics`;
  - lo devuelve con `setResult(RESULT_OK, intent)` (los campos como extras) y hace `finish()`, que desconecta sin cerrar la app del host.
  - Si la conexión termina antes, devuelve `interrupted = true`.
- Exponer desde `MediaCodecDecoderRenderer` lo que haga falta (un getter de snapshot de stats) sin asignar memoria por frame.
- Commit `feat(tuner): stream test mode in Game`.

### Tarea 4: `BitrateTunerActivity` y la interfaz

**Archivos:**
- Crear `com/clue/tuner/BitrateTunerActivity.java` y su layout, siguiendo el estilo del launcher.
- **Entrada:** el uuid del PC por extra.
  - Busca la app `Clue Bitrate Test` en la lista de apps del PC, igual que `AppView`.
  - Si no está, muestra el aviso de instalación y termina.
- **Bucle:**
  1. Pide `BitrateSearch.nextKbps()`.
  2. Lanza `Game` con `ServerHelper.createStartIntent(...)` más los extras de prueba, con `startActivityForResult`.
  3. Al volver, evalúa con `StepVerdict` (el baseline es el RTT p95 del primer escalón) y llama a `report`.
  4. Actualiza la lista de escalones en pantalla: bitrate y ✓/✗ con el motivo.
- **Al terminar:** cierra la app de prueba en el host con `NvHTTP.quitApp`, en un hilo aparte. Después muestra "Recomendado: X Mbps" con Aplicar / Cancelar.
  - Aplicar escribe la preferencia de bitrate (la misma clave que usa `PreferenceConfiguration`) y llama a `TunerStore.save`.
- **Atrás:** cancela todo, cierra la app del host y no guarda.
- **Máximo del decoder:** `MediaCodecInfo` del decoder HEVC elegido → `getVideoCapabilities().getBitrateRange().getUpper()`. Si no se puede leer, 0.
- Commit `feat(tuner): tuner activity`.

### Tarea 5: cuándo corre

- **Automático:** al lanzar cualquier app de un PC, si `TunerStore.shouldOffer(pc, red)`, primero aparece un diálogo "Probar el mejor bitrate para esta red (2–4 min)".
  - **Probar** abre `BitrateTunerActivity`.
  - **Ahora no** marca saltado y sigue con el lanzamiento normal.
- **Manual:** entrada "Probar bitrate" en el menú contextual del PC, y en Ajustes para el último PC usado.
- Commit `feat(tuner): offer on first launch and manual entry points`.

### Tarea 6: prueba en el equipo y registro

- Correr la prueba completa contra Vibepollo en la tablet. Anotar los escalones, el resultado sugerido y el tiempo total en `docs/measurements/2026-10-20-bitrate-tuner.md`.
- Validar el sugerido: 2 corridas de `tools/measure.sh` al sugerido, sin cuadros perdidos y con cadencia ≥ 98 %.
- Commit `docs(measurements): bitrate tuner first run`.

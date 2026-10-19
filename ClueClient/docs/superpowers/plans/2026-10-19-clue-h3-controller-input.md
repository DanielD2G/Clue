# Clue H3 — entrada, mandos primero: plan de implementación

> **Para agentes:** SUB-SKILL REQUERIDO: usar superpowers:subagent-driven-development (recomendado) o superpowers:executing-plans para implementar este plan tarea por tarea. Los pasos usan checkboxes (`- [ ]`).

**Objetivo:** medir la latencia de entrada de punta a punta (botón → pantalla) y bajarla, empezando por el tramo que hoy no se mide: del evento al paquete en el aire.

**Arquitectura:**
- Se mide antes de cambiar nada:
  - sellos nativos cuando el paquete sale por el socket;
  - un modo de prueba botón → pantalla automático, sin cámara.
- Cada cambio de envío entra solo si baja esas métricas:
  - hilo de envío con prioridad alta;
  - envío inmediato del mando;
  - estado del mando no confiable con redundancia.

**Stack:** C (moonlight-common-c, JNI), Java, JUnit 4, una página HTML en el host.

**Spec:** [`docs/superpowers/specs/2026-10-19-clue-client-design.md`](../specs/2026-10-19-clue-client-design.md) §H3.

## Correcciones a la spec (verificadas en el código el 2026-10-19)

- **AC_VO ya está hecho upstream.**
  - `ControlStream.c:1785` activa `ENET_SOCKOPT_QOS`, que en `enet/unix.c` pone DSCP EF + ECT(1) (`IP_TOS`) y `SO_PRIORITY` 6.
  - Solo se desactiva si 3 intentos de conexión seguidos no tienen respuesta.
  - Por lo tanto el punto 1 de §H3 queda **verificado**, no implementado.
  - Queda medir en captura que la marca llegue: Tarea 1, paso de verificación.
- **Los hosts ya aceptan paquetes no confiables** por el canal de control: el touch batcheable viaja con flags 0 (`InputStream.c:1349`).
- **Camino actual de un evento de mando:**
  1. `onKey`/`onGenericMotion` en el hilo de UI;
  2. `ControllerHandler`;
  3. JNI `LiSendMultiControllerEvent`, que lo encola o lo fusiona en el paquete pendiente (`InputStream.c:1040-1100`);
  4. el hilo `InputSend`, con prioridad por defecto (`InputStream.c:665`);
  5. `sendMessageEnet`, confiable, con espera de hasta 10 ms por paquete (`ControlStream.c:772-795`).

## Fuera de este plan

- La detección del enlace del mando (USB, BT o 2,4 GHz, y su tasa de reportes), punto 4 de §H3. Es UI y se planifica aparte, cuando haya números de este plan.
- Las extensiones `x-clue-` con el host.

## Restricciones globales

- **Hosts:** Clue sigue funcionando contra Sunshine, Apollo y Vibepollo de serie. Nada rompe el protocolo.
- **Camino caliente:** la instrumentación del camino caliente no asigna memoria ni bloquea. Los rings son preasignados y sin locks.
- **Modo de prueba:** puede asignar memoria (PixelCopy), pero solo corre si se pide por un extra de lanzamiento.
- **Código upstream:** cambios acotados, marcados `// CLUE` en Java y `/* CLUE */` en C.
- **Medición:** ningún cambio de envío entra sin bajar la mediana o el p99 de botón → pantalla, o de evento → aire, frente a la línea base de la Tarea 3. Mismo protocolo, corridas alternadas.
- **Tests:** `./gradlew :app:testNonRootDebugUnitTest`. Build e instalación: `bash tools/build-install.sh release`.
- **Tablet:** con cargador de pared y más de 30 % de batería para medir (ver `docs/measurements/README.md`).

## Review Focus

1. **Varios mandos a la vez:** cada uno tiene su canal y su paquete pendiente, y el envío inmediato o los reenvíos de uno no pueden pisar el estado de otro. Test nativo `multipleControllersKeepIndependentState` (Tarea 5).
2. **Pérdida de un paquete no confiable con un cambio de botón:** el host tiene que ver el botón presionado aunque se pierda un datagrama, gracias a la redundancia. Verificación en la Tarea 5 con pérdida inyectada (`tc` no está disponible en Android sin root; se usa un descarte determinista del 10 % en modo prueba).
3. **Desconexión o reconexión del stream con el modo de prueba activo:** el modo se detiene y no deja hilos vivos. Verificación manual en la Tarea 2.
4. **Hosts GFE (no Sunshine):** `sendMessageEnet` fuerza paquetes confiables (`ControlStream.c:699`). La ruta no confiable tiene que respetarlo. Se revisa en el diff de la Tarea 5.
5. **Mouse relativo y teclado:** no cambian de comportamiento salvo por la prioridad del hilo. Se mantiene el batching de 1 ms del mouse.

---

### Tarea 1: sellos nativos "en el aire" para la entrada

**Archivos:**
- Crear `app/src/main/jni/moonlight-core/clue_input_trace.c` y `.h`: ring nativo lock-free de 4096 entradas `{type, enqueueNs, wireNs}` con `CLOCK_MONOTONIC`. Tipos: 1 mando, 2 mouse, 3 teclado, 4 touch.
- Modificar `moonlight-common-c/src/InputStream.c`:
  - estampar `enqueueNs` al crear o fusionar el holder;
  - estampar `wireNs` en `inputSendThreadProc` después de que `sendInputPacket` vuelva con éxito.

  En un paquete fusionado, `enqueueNs` es el **primer** evento fusionado: es el peor caso.
- Modificar `simplejni.c` y `MoonBridge.java`: `static native int clueDrainInputWire(long[] out)`, que copia hasta `out.length / 3` entradas y devuelve cuántas.
- Modificar `com/clue/trace/TraceSession.java` y `TraceWriter.java`:
  - al cerrar la sesión, drenar a `inputwire.csv` (`type,enqueueNs,wireNs`);
  - en el overlay, "Entrada→aire p50/p99".
- Test: `app/src/test/java/com/clue/trace/TraceWriterTest.java`, con un caso nuevo `writesInputWireCsv`.

- [ ] **Paso 1:** test `writesInputWireCsv`. Con `TraceWriter.writeInputWire(long[] rows, int n, File dir)` y 2 filas `{1, 100, 250}`, `{2, 300, 310}`, el archivo tiene el header `type,enqueueNs,wireNs` y esas filas. Ejecutarlo: FAIL.
- [ ] **Paso 2:** implementar el ring nativo, los sellos, el JNI y el writer. El test pasa y la suite completa también.
- [ ] **Paso 3: verificar en la tablet.**
  - Con un mando conectado, 30 s moviendo sticks y apretando botones.
  - Esperado: `inputwire.csv` con más de 1000 filas, `wireNs ≥ enqueueNs` en el 100 % de las filas, y la línea del overlay visible.
  - En el PC, una captura con `pktmon` o Wireshark muestra DSCP 46 en los paquetes UDP del puerto de control (47999). Anotar el resultado en el documento de medición.
- [ ] **Paso 4: commit.** `feat(h3): native enqueue-to-wire timestamps for input packets`

### Tarea 2: modo de prueba botón → pantalla

**Archivos:**
- Crear `tools/host/gamepad-photon.html`.
  - Página a pantalla completa que lee la Gamepad API en cada `requestAnimationFrame`.
  - Pinta un cuadrado de 256×256 px arriba a la izquierda: blanco si el botón 0 (A) está presionado en cualquier mando, negro si no.
  - Muestra en texto chico el contador de pulsaciones.
- Crear `app/src/main/java/com/clue/input/PhotonTest.java`:
  - se activa con el extra de lanzamiento `clue_photon_test=<n>`, que se lee en `Game.onCreate`;
  - 3 s después del primer frame, si no hay mando virtual registra el mando 0 con `LiSendControllerArrivalEvent`;
  - repite n veces, cada 400 ms: anota `t0 = System.nanoTime()`, envía A presionado, a los 100 ms lo suelta;
  - entre pulsaciones, en cada vsync, lee con `PixelCopy` (SurfaceView → bitmap de 16×16 del centro del cuadrado);
  - registra `t1` = el `expectedPresentationTime` del primer frame con luminancia > 0,5, y descarta las pulsaciones sin cambio en 200 ms;
  - escribe `photon.csv` (`pressNs,detectNs`) en la carpeta de la traza y loguea `ClueTrace photon n=… p50=… p99=…`.
- Crear `tools/photon.sh [n=100]`: lanza con el extra, espera, baja `photon.csv` e imprime p50/p95/p99.
- Test: `app/src/test/java/com/clue/input/PhotonStatsTest.java`, con un caso `percentilesIgnoreMisses`. `PhotonTest.stats(long[] press, long[] detect)` con un detect = 0 da n = 2 y el p50 correcto.

- [ ] **Paso 1:** test de `stats`. Ejecutarlo: FAIL.
- [ ] **Paso 2:** implementar `PhotonTest`, la página y el script. El test pasa.
- [ ] **Paso 3: verificar en la tablet.**
  - Con la página abierta en el host, `tools/photon.sh 50`.
  - Esperado: n ≥ 45 detecciones y valores entre 20 y 150 ms.
  - Salir del stream a mitad de la prueba: no queda ningún hilo `PhotonTest` (`adb shell ps -T`).
- [ ] **Paso 4: commit.** `feat(h3): automatic button-to-photon test mode`

### Tarea 3: línea base de entrada

- [ ] **Paso 1:** 3 corridas de `tools/photon.sh 100` y 3 sesiones de 30 s con mando para `inputwire.csv`. Pacing: Clue adaptativo.
- [ ] **Paso 2:** escribir `docs/measurements/2026-10-19-h3-input-baseline.md` con:
  - botón → pantalla p50/p95/p99;
  - evento → JNI (traza Java existente);
  - encolado → aire (Tarea 1), por tipo.
- [ ] **Paso 3: commit.** `docs(measurements): H3 input baseline`

### Tarea 4: hilo de envío con prioridad alta

**Archivos:** `moonlight-common-c/src/InputStream.c`, al inicio de `inputSendThreadProc`: `setpriority(PRIO_PROCESS, 0, -10)` bajo `#ifdef __ANDROID__`. Se usa el mismo nice que `THREAD_PRIORITY_URGENT_AUDIO`; no hace falta permiso.

- [ ] **Paso 1:** implementar y medir con 3 corridas de encolado → aire.
- [ ] **Paso 2:** si el p99 de encolado → aire no baja, revertir y registrar el ruling. Si baja, commit `perf(h3): input send thread at urgent priority` con los números.

### Tarea 5: estado del mando no confiable, con redundancia y sin espera

**Archivos:**
- Modificar `moonlight-common-c/src/InputStream.c`, en el armado del paquete de mando (`:1073-1076`):
  - con host Sunshine (`IS_SUNSHINE()`) el flag pasa a `ENET_PACKET_FLAG_UNSEQUENCED`, el mismo que usa el touch;
  - con GFE sigue confiable.
- **Redundancia:** si el paquete cambia `buttonFlags` respecto del último enviado para ese mando, se encola una segunda copia idéntica. Además, mientras haya entrada activa (último cambio hace menos de 500 ms), el hilo de envío reenvía el último estado de cada mando cada 4 ms. Esa es la cifra inicial de la spec.
- **Sin espera:** `sendMessageEnet` ya no espera en los paquetes no confiables (la espera de 10 ms solo aplica a confiables, `ControlStream.c:776`). No hace falta cambiarlo; verificarlo en el diff.
- Crear `app/src/main/jni/moonlight-core/test/` con un test nativo host-side de la lógica de redundancia, extraída a `clue_redundancy.c` como función pura: dado `(lastButtons, newButtons, lastSendNs, nowNs)`, devuelve cuántas copias enviar y si toca un reenvío. Se compila y corre con `tools/native-test.sh` (gcc o clang del NDK para host).
  - `buttonChangeSendsTwoCopies`
  - `axisOnlyChangeSendsOneCopy`
  - `idleStateResendsEvery4msWhileActive`
  - `noResendAfter500msIdle`
  - `multipleControllersKeepIndependentState` (estado por mando en un array indexado por número de mando)

- [ ] **Paso 1:** tests nativos. Ejecutarlos: FAIL.
- [ ] **Paso 2:** implementar `clue_redundancy.c` e integrarlo en `InputStream.c`. Los tests pasan.
- [ ] **Paso 3:** verificar contra Vibepollo que el mando funciona y que, con un descarte determinista del 10 % (`clue_drop_test` en modo prueba), no se pierde ninguna pulsación en `photon.sh 100`.
- [ ] **Paso 4:** medir con 3 corridas de botón → pantalla y de encolado → aire contra la línea base. Si la mediana y el p99 no bajan, revertir y registrar el ruling.
- [ ] **Paso 5: commit.** `perf(h3): unreliable controller state with redundancy`

### Tarea 6: envío inmediato del mando

**Archivos:** `moonlight-common-c/src/InputStream.c`. En `LiSendMultiControllerEvent`, con host Sunshine, el paquete no confiable del mando se envía en el hilo que llama (`sendInputPacket` bajo `enetMutex`), sin pasar por la cola ni por el hilo `InputSend`. El mouse y el teclado siguen como están.

- [ ] **Paso 1:** implementar y medir encolado → aire y botón → pantalla. Los tests nativos de la Tarea 5 siguen pasando.
- [ ] **Paso 2:** si no baja, revertir y registrar el ruling. Si baja, commit `perf(h3): send controller state on the caller thread`.

### Tarea 7: registro

- [ ] **Paso 1:** `docs/measurements/2026-10-19-h3-input.md` con la tabla línea base → cada cambio que quedó, y los descartados con su motivo.
- [ ] **Paso 2: commit.** `docs(measurements): H3 input results`

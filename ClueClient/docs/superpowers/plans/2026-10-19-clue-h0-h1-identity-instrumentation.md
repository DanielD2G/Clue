# Clue H0 + H1 — Identidad, build e instrumentación: plan de implementación

> **Para agentes:** sub-skill requerido: usar superpowers:subagent-driven-development (recomendado) o superpowers:executing-plans para implementar este plan tarea por tarea. Los pasos usan checkboxes (`- [ ]`) para el seguimiento.

**Objetivo:** convertir el fork de Moonlight 12.2 en la app **Clue**, instalable junto a Moonlight y Artemis, con defaults para la POCO Pad. Además, cada frame, evento de entrada y anomalía de audio queda medido en un único reloj, exportable y analizable en el PC.

**Arquitectura:**
- El código nuevo vive en el paquete Java `com.clue` (`trace`, `defaults`).
- Se engancha en los puntos existentes de upstream (`MediaCodecDecoderRenderer`, `ControllerHandler`, `Game`, `AndroidAudioRenderer`) con cambios mínimos y comentados `// CLUE:`.
- Las trazas viven en buffers circulares preasignados, sin asignar memoria en el camino caliente, y se escriben a CSV al terminar la sesión.
- El análisis corre en el PC con programas Java de un solo archivo.

**Stack:** Android Gradle Plugin 9.4, Gradle 9.7.1, JDK 21 (Temurin), compileSdk 37 / targetSdk 36, NDK 29.0.14206865 (ndk-build), Java 17 en la app, JUnit 4 para los tests de JVM, Git Bash para los scripts.

**Spec:** [docs/superpowers/specs/2026-10-19-clue-client-design.md](../specs/2026-10-19-clue-client-design.md). Reglas: [AGENTS.md](../../../AGENTS.md).

## Restricciones globales

- **Identidad:**
  - `applicationId` `com.clue.client` (flavor nonRoot) y `com.clue.client.root` (flavor root).
  - En debug, sufijo `.debug` y etiqueta "Clue (Debug)"; en release, etiqueta "Clue", sin el sufijo `.unofficial`.
- **SDK y NDK:** no se cambian los valores de upstream: `minSdk 21`, `compileSdk 37`, `targetSdk 36`, `ndkVersion "29.0.14206865"`. El `namespace` sigue siendo `com.limelight`, así no hay que tocar las clases `R`.
- **Defaults de fábrica:** resolución nativa del display, **120 fps**, **80000 kbps**, pacing `balanced` y overlay de rendimiento activado. Se aplican solo cuando no existe la preferencia.
- **Un único reloj:** todo timestamp de traza está en `CLOCK_MONOTONIC`. En Java es `System.nanoTime()` y `MotionEvent.getEventTimeNanos()`; en C, `clock_gettime(CLOCK_MONOTONIC)`. Los tiempos de moonlight-common-c (`CLOCK_MONOTONIC_RAW`) se convierten en JNI.
- **Camino caliente sin asignaciones ni locks:** los hooks de traza solo escriben en arrays preasignados.
- **Overhead:** el de la instrumentación tiene que ser ≤ 1 % de CPU.
- **Compatibilidad:** funciona contra Vibepollo 2.0.x, Sunshine y Apollo sin cambios en el host. moonlight-common-c **no se modifica** en este plan.
- **Licencia:** GPL-3.0. Se conserva `LICENSE.txt` y la atribución visible a Moonlight.
- **Medición:** corridas de ≥ 120 s, con el host en modo Desktop y TestUFO a pantalla completa. La línea base de Artemis es 95–96 % de intervalos a 1 vsync y ~13,7 ms del buffer listo a pantalla.
- **Comentarios:** en español o inglés, siguiendo el estilo del archivo que se toca. Las líneas nuevas en código upstream llevan el prefijo `// CLUE:`.

## Foco de revisión

1. **Sesiones de más de 18 min:** el anillo de 131072 frames da la vuelta. Se espera que sobrescriba lo más viejo, sin excepciones ni registros cruzados. Test en la Tarea 5: `wrapsAroundWithoutMixingFrames`.
2. **Frames sin `onFrameRendered`**, porque SurfaceFlinger los descartó o el pacer los saltó. Se espera que queden marcados como no presentados, nunca emparejados con la presentación de otro frame. Tests en la Tarea 5: `unpresentedFramesStayUnpresented` y `ptsMissIsCounted`.
3. **Reinicio del decodificador** (cambio de resolución, error, IDR tras una reconfiguración). Se espera que los PTS viejos no se asocien con frames nuevos. Test en la Tarea 5: `resetClearsPtsAssociation`.
4. **Almacenamiento no disponible** o app cerrada a mitad de sesión. Se espera que el stream nunca se caiga por la traza: la escritura falla en silencio con un log, y en `onStop` se escribe lo acumulado. Test en la Tarea 9: `writeFailureDoesNotThrow`.
5. **Relojes mezclados.** Se espera que ninguna etapa dé negativa. El analizador cuenta y reporta las deltas negativas en lugar de promediarlas. Test en la Tarea 11: `negativeDeltasAreReportedNotAveraged`.

---

### Tarea 1: Toolchain y build limpio de upstream

**Archivos:**
- Crear: `ClueClient/local.properties` (ya ignorado por `.gitignore`)
- Crear: `ClueClient/docs/BUILD.md`

**Interfaces:**
- Produce: un comando de build que funciona (`./gradlew :app:assembleNonRootDebug`) y la ruta del SDK que usan las tareas siguientes.

- [ ] **Paso 1: instalar el JDK 21.** Ejecutar `winget install --id EclipseAdoptium.Temurin.21.JDK -e`. Verificar que `java -version` muestre `21.`.
- [ ] **Paso 2: instalar las Android command-line tools** en `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`, desde el zip oficial de `dl.google.com/android/repository/commandlinetools-win-*_latest.zip`. El zip va en su propio directorio temporal.
- [ ] **Paso 3: aceptación de licencias. La hace el usuario**, no el agente, porque implica aceptar los términos del Android SDK. Pedirle que ejecute:
  ```bash
  "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
  ```
- [ ] **Paso 4: instalar los paquetes.**
  ```bash
  sdkmanager "platform-tools" "platforms;android-37" "build-tools;37.0.0" "ndk;29.0.14206865"
  ```
  Si `build-tools;37.0.0` no existe, usar la versión 37.x más alta que liste `sdkmanager --list`.
- [ ] **Paso 5: crear `ClueClient/local.properties`** con `sdk.dir=C\:\\Users\\Daniel\\AppData\\Local\\Android\\Sdk`.
- [ ] **Paso 6: build de upstream sin cambios.** Ejecutar `cd ClueClient && ./gradlew :app:assembleNonRootDebug`. Esperado: `BUILD SUCCESSFUL`, y el APK en `app/build/outputs/apk/nonRoot/debug/`.
- [ ] **Paso 7: escribir `docs/BUILD.md`** con los pasos 1–6, en forma de comandos, para que el entorno se pueda reproducir.
- [ ] **Paso 8: commit.**
  ```bash
  git add ClueClient/docs/BUILD.md
  git commit -m "docs: build toolchain setup for Clue"
  ```

### Tarea 2: Identidad Clue, firma y script de instalación

**Archivos:**
- Modificar: `ClueClient/app/build.gradle` (bloques `productFlavors`, `buildTypes`, `defaultConfig`, y un `signingConfigs` nuevo)
- Crear: `ClueClient/app/src/main/res/drawable/ic_clue_foreground.xml`, `ClueClient/app/src/main/res/values/ic_clue_background.xml`
- Modificar: `ClueClient/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (y `ic_launcher_round.xml` si existe), para apuntar a los recursos de Clue
- Modificar: la pantalla de preferencias (`ClueClient/app/src/main/res/xml/preferences.xml`), agregando la entrada "Acerca de Clue"
- Crear: `ClueClient/.gitignore` con la entrada `keystore.properties`, más `ClueClient/tools/build-install.sh`

**Interfaces:**
- Produce:
  - `tools/build-install.sh [debug|release]`: compila nonRoot, instala por `adb install -r` y muestra el package.
  - Packages `com.clue.client` (release) y `com.clue.client.debug` (debug).

- [ ] **Paso 1: `app/build.gradle`.**
  - `applicationId` de nonRoot pasa a `"com.clue.client"` y el de root a `"com.clue.client.root"`.
  - En debug: `resValue` `app_label` "Clue (Debug)" y `app_label_root` "Clue (Root Debug)".
  - En release: se elimina `applicationIdSuffix ".unofficial"` (se cambió el applicationId, que es lo que pide ese comentario de upstream; el comentario se conserva y se agrega una línea `// CLUE:` que lo explica). `app_label` "Clue" y `app_label_root` "Clue (Root)".
  - `versionName "0.1.0"` y `versionCode 1`.
- [ ] **Paso 2: firma.** Agregar `signingConfigs { release { … } }` que lea `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) solo si el archivo existe, y usarlo en `buildTypes.release`.
  - Generar el keystore local con `keytool -genkeypair -alias clue -keyalg RSA -keysize 4096 -validity 36500`, con una contraseña aleatoria generada por el agente.
  - Guardarlo en `ClueClient/clue-release.jks` y guardar los datos en `keystore.properties`. **Ambos quedan fuera de git**, y la contraseña no se muestra en el chat.
- [ ] **Paso 3: ícono adaptativo.** Fondo de color sólido `#0F1720` y un monograma vectorial "C" en primer plano, color `#3DDC97`. El diseño queda a criterio de quien implemente, dentro del área segura de 66 dp.
- [ ] **Paso 4: preferencia "Acerca de Clue"**, sin acción. El summary dice: "Clue 0.1.0, basado en Moonlight 12.2 (GPL-3.0). github.com/DanielD2G/Clue · github.com/moonlight-stream/moonlight-android".
- [ ] **Paso 5: `tools/build-install.sh`.** Ejecuta `./gradlew :app:assembleNonRoot<Debug|Release>`, después `"$ADB" install -r <apk>` con `ADB` tomado de `$ANDROID_HOME/platform-tools/adb.exe`, o como fallback `/c/Program Files/DroidScreen/adb/adb.exe`. Al final imprime `pm list packages | grep clue`.
- [ ] **Paso 6: verificar.**
  - Ejecutar `bash tools/build-install.sh release`. Esperado: `package:com.clue.client`.
  - Ejecutar `adb shell pm list packages | grep -E "limelight|clue"`. Esperado: siguen `com.limelight`, `com.limelight.noir` y `com.limelight.perf`, más `com.clue.client`.
  - En la tablet aparece el ícono "Clue".
- [ ] **Paso 7: commit.**
  ```bash
  git add ClueClient/app/build.gradle ClueClient/app/src/main/res ClueClient/.gitignore ClueClient/tools/build-install.sh
  git commit -m "feat(h0): Clue identity, package id, icon, about entry and release signing"
  ```

### Tarea 3: Defaults de fábrica para el hardware de referencia

**Archivos:**
- Crear: `ClueClient/app/src/main/java/com/clue/defaults/ClueDefaults.java`
- Modificar: `ClueClient/app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java` (constantes `DEFAULT_*` en las líneas ~72–103 y lectura de preferencias)
- Modificar: `ClueClient/app/build.gradle`, agregando `testImplementation 'junit:junit:4.13.2'`
- Test: `ClueClient/app/src/test/java/com/clue/defaults/ClueDefaultsTest.java`

**Interfaces:**
- Produce: clase final `com.clue.defaults.ClueDefaults` con:
  - `static final String FPS = "120"`
  - `static final int BITRATE_KBPS = 80000`
  - `static final String FRAME_PACING = "balanced"`
  - `static final boolean PERF_OVERLAY = true`
  - `static String nativeResolution(int displayWidth, int displayHeight)`: devuelve `"<lado largo>x<lado corto>"`. Por ejemplo, `nativeResolution(1600, 2560)` devuelve `"2560x1600"`.

- [ ] **Paso 1: test que falla.**
  ```java
  @Test public void nativeResolutionIsLandscape() {
      assertEquals("2560x1600", ClueDefaults.nativeResolution(1600, 2560));
      assertEquals("2560x1600", ClueDefaults.nativeResolution(2560, 1600));
  }
  @Test public void referenceDefaults() {
      assertEquals("120", ClueDefaults.FPS);
      assertEquals(80000, ClueDefaults.BITRATE_KBPS);
      assertEquals("balanced", ClueDefaults.FRAME_PACING);
      assertTrue(ClueDefaults.PERF_OVERLAY);
  }
  ```
- [ ] **Paso 2: ejecutarlo.** `./gradlew :app:testNonRootDebugUnitTest --tests com.clue.defaults.ClueDefaultsTest`. Esperado: FAIL, porque `ClueDefaults` no existe.
- [ ] **Paso 3: implementar `ClueDefaults`.** Después, en `PreferenceConfiguration`:
  - `DEFAULT_FPS = ClueDefaults.FPS`, `DEFAULT_FRAME_PACING = ClueDefaults.FRAME_PACING` y `DEFAULT_ENABLE_PERF_OVERLAY = ClueDefaults.PERF_OVERLAY`.
  - Si `RESOLUTION_PREF_STRING` no existe, usar `ClueDefaults.nativeResolution` con las dimensiones reales del display.
  - Si la preferencia de bitrate no existe, usar `ClueDefaults.BITRATE_KBPS` en lugar de `getDefaultBitrate`.
- [ ] **Paso 4: tests y verificación.** Ejecutar el test del Paso 2. Esperado: PASS. Después, `bash tools/build-install.sh release`, limpiar los datos de la app (`adb shell pm clear com.clue.client`) y abrir la configuración: tiene que mostrar 2560×1600 (nativo), 120 FPS, 80 Mbps, "Equilibrado" y estadísticas activadas.
- [ ] **Paso 5: commit.** `git commit -m "feat(h0): reference-hardware factory defaults"`.

### Tarea 4: Un único reloj (conversión en JNI)

**Archivos:**
- Modificar: `ClueClient/app/src/main/jni/moonlight-core/callbacks.c`, en la función que llama a `bridgeDrSubmitDecodeUnit`

**Interfaces:**
- Produce: `receiveTimeUs` y `enqueueTimeUs` que llegan a `MediaCodecDecoderRenderer.submitDecodeUnit` ya en **`CLOCK_MONOTONIC` µs**, comparables con `System.nanoTime() / 1000`.

- [ ] **Paso 1: agregar `static uint64_t clueRawToMonoUs(uint64_t rawUs)` en `callbacks.c`.**
  - Lee `CLOCK_MONOTONIC` y `CLOCK_MONOTONIC_RAW` con `clock_gettime`, calcula `offsetUs = mono − raw` en cada llamada (sin cache, para seguir la deriva) y devuelve `rawUs + offsetUs`.
  - Se aplica a `receiveTimeUs` y `enqueueTimeUs` antes de pasarlos a Java.
- [ ] **Paso 2: verificar en el dispositivo.**
  - Agregar temporalmente un `LimeLog` una vez por segundo en `submitDecodeUnit` con `System.nanoTime()/1000 − enqueueTimeUs`.
  - Con un stream a 120 fps, los valores tienen que quedar entre 0 y 50000 µs, siempre positivos.
  - Comando: `adb logcat -s com.limelight.LimeLog:I | grep CLUE-CLOCK`.
  - Quitar el log después.
- [ ] **Paso 3: commit.** `git commit -m "feat(h1): convert common-c timestamps to CLOCK_MONOTONIC in JNI"`.

### Tarea 5: Registro de frames (`FrameTrace`)

**Archivos:**
- Crear: `ClueClient/app/src/main/java/com/clue/trace/FrameTrace.java`
- Test: `ClueClient/app/src/test/java/com/clue/trace/FrameTraceTest.java`

**Interfaces:**
- Produce `com.clue.trace.FrameTrace`. Es struct-of-arrays con capacidad potencia de 2 y escritura sin locks. En arm64 las escrituras de `long` son atómicas; las carreras al leer durante la sesión se aceptan, porque solo afectan al overlay.
  - `FrameTrace(int capacityPow2)`. Default de la app: `1 << 17`.
  - `void onReceived(int frameNumber, long firstPacketUs, long completeUs, int hostLatency01ms)`
  - `void onDecodeInput(int frameNumber, long ptsUs, long tNs)`
  - `void onDecodeOutput(long ptsUs, long tNs)`
  - `void onRelease(long ptsUs, long tNs, long targetVsyncNs)`, donde `targetVsyncNs = 0` si no se conoce.
  - `void onPresented(long ptsUs, long presentNs)`
  - `void reset()`: se llama al recrear el decodificador.
  - `long ptsMisses()`: cantidad de búsquedas de PTS que no encontraron frame.
  - `int size()`: frames registrados, hasta la capacidad.
  - `void forEach(FrameVisitor v)`, del más viejo al más nuevo, con `interface FrameVisitor { void visit(int frameNumber, long firstPacketUs, long completeUs, int hostLatency01ms, long decodeInNs, long decodeOutNs, long releaseNs, long targetVsyncNs, long presentNs); }`. Los campos sin dato valen `0`.
- La asociación por PTS busca hacia atrás, desde el último `onDecodeInput`, como máximo **16 slots**. Si no encuentra el PTS, incrementa `ptsMisses()` y no escribe nada.

- [ ] **Paso 1: tests que fallan.**
  - `recordsAllStagesInOrder`: un frame recorre las cinco etapas y `forEach` devuelve los valores exactos que se pasaron.
  - `wrapsAroundWithoutMixingFrames`: con capacidad 8 y 20 frames, `size()==8` y los frameNumbers visitados son 12..19.
  - `unpresentedFramesStayUnpresented`: si se presenta el frame 2 y no el 1, el 1 queda con `presentNs==0`.
  - `ptsMissIsCounted`: `onPresented` con un PTS desconocido hace `ptsMisses()==1`, y ningún frame cambia.
  - `resetClearsPtsAssociation`: después de `reset()`, un `onDecodeOutput` con un PTS anterior al reset es un miss.
- [ ] **Paso 2: ejecutarlos.** `./gradlew :app:testNonRootDebugUnitTest --tests com.clue.trace.FrameTraceTest`. Esperado: FAIL.
- [ ] **Paso 3: implementar `FrameTrace`**, según la interfaz anterior.
- [ ] **Paso 4: ejecutar los tests.** Esperado: PASS.
- [ ] **Paso 5: commit.** `git commit -m "feat(h1): lock-free per-frame trace ring"`.

### Tarea 6: Enganchar `FrameTrace` al pipeline de video

**Archivos:**
- Crear: `ClueClient/app/src/main/java/com/clue/trace/TraceSession.java`
- Modificar: `ClueClient/app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java`
  - `USE_FRAME_RENDER_TIME`, líneas ~42–43 y ~660–672
  - `submitDecodeUnit`, ~1346
  - `queueInputBuffer`, ~1287
  - salida y release, ~1015–1110
- Modificar: `ClueClient/app/src/main/java/com/limelight/Game.java` (inicio y fin de la conexión)

**Interfaces:**
- Consume: `FrameTrace` (Tarea 5) y los tiempos en `CLOCK_MONOTONIC` (Tarea 4).
- Produce `com.clue.trace.TraceSession`:
  - `static TraceSession start(Context ctx)`
  - `static TraceSession current()`, que devuelve null si no hay sesión
  - `FrameTrace frames()`
  - `InputTrace input()` (Tarea 7)
  - `SessionCounters counters()` (Tarea 8)
  - `void stop()`

- [ ] **Paso 1: crear `TraceSession`.** Al principio solo contiene `frames()`. `input()` y `counters()` se agregan en las Tareas 7 y 8.
- [ ] **Paso 2: hooks en `MediaCodecDecoderRenderer`.**
  - `onReceived`, al inicio de `submitDecodeUnit`.
  - `onDecodeInput`, justo antes de `queueInputBuffer`, con el PTS que se pasa a esa llamada.
  - `onDecodeOutput`, al obtener cada buffer de salida.
  - `onRelease`, en cada `releaseOutputBuffer`. Va con el timestamp de release cuando se usa la variante con timestamp, o con 0.
  - `reset`, al (re)configurar el decodificador.
  - `USE_FRAME_RENDER_TIME` pasa a `true`, y el `OnFrameRenderedListener` llama a `onPresented(presentationTimeUs, nanoTime)` con el `nanoTime` que entrega el callback.
- [ ] **Paso 3: sesión en `Game`.** `TraceSession.start` al iniciar el stream y `stop` al terminar.
- [ ] **Paso 4: verificar en el dispositivo.**
  - Hacer un stream de 60 s con TestUFO, contra Vibepollo, en modo Desktop.
  - Dejar temporalmente un log al final que muestre `size()`, `ptsMisses()` y la cantidad de frames con `presentNs>0`.
  - Esperado: `size()≈7200`, `ptsMisses()==0` y ≥ 95 % de frames presentados.
- [ ] **Paso 5: commit.** `git commit -m "feat(h1): trace every frame through receive, decode, release and present"`.

### Tarea 7: Registro de entrada (`InputTrace`)

**Archivos:**
- Crear: `ClueClient/app/src/main/java/com/clue/trace/InputTrace.java`
- Test: `ClueClient/app/src/test/java/com/clue/trace/InputTraceTest.java`
- Modificar:
  - `ClueClient/app/src/main/java/com/limelight/binding/input/ControllerHandler.java`, en los puntos que llaman a `conn.sendControllerInput` (líneas ~422, ~1307 y ~1311)
  - `ClueClient/app/src/main/java/com/limelight/Game.java`, en los handlers de teclado, mouse y touch

**Interfaces:**
- Produce `com.clue.trace.InputTrace`:
  - `static final byte GAMEPAD=1, MOUSE=2, KEYBOARD=3, TOUCH=4`
  - `InputTrace(int capacityPow2)`. Default: `1 << 16`.
  - `int onEvent(byte type, long eventNs)`: devuelve el slot.
  - `void onSent(int slot, long sentNs)`
  - `int size()`
  - `void forEach(InputVisitor v)`, con `interface InputVisitor { void visit(byte type, long eventNs, long sentNs); }`
- **Definición de "enviado":** el momento en que `conn.sendControllerInput` (o la llamada equivalente de teclado, mouse o touch) **vuelve**, es decir, cuando el evento quedó encolado en moonlight-common-c. El tiempo real de salida al cable se mide en H3, cuando se vendorice common-c.
- `eventNs` es `InputEvent.getEventTimeNanos()`, disponible en API ≥ 34. En versiones anteriores, `getEventTime() * 1_000_000L`.

- [ ] **Paso 1: tests que fallan.**
  - `pairsEventWithSend`: `onEvent` seguido de `onSent` en el mismo slot y `forEach` devuelve el par.
  - `unsentEventKeepsZero`: un evento sin `onSent` queda con `sentNs == 0`.
  - `wrapsAround`: con capacidad 4 y 6 eventos, solo quedan los últimos 4.
- [ ] **Paso 2: ejecutarlos.** `./gradlew :app:testNonRootDebugUnitTest --tests com.clue.trace.InputTraceTest`. Esperado: FAIL.
- [ ] **Paso 3: implementar `InputTrace`.** Agregar `input()` a `TraceSession`.
- [ ] **Paso 4: enganchar los hooks.** En el `GenericControllerContext` de `ControllerHandler`, guardar `int pendingTraceSlot` (−1 cuando no hay evento) tomado del `InputEvent` en curso, y llamar a `onSent` después de cada `sendControllerInput`. Lo mismo para teclado, mouse y touch en `Game`.
- [ ] **Paso 5: ejecutar los tests.** Esperado: PASS. En el dispositivo, con un mando conectado durante 30 s, los registros tienen que tener `sentNs ≥ eventNs` en todos los casos.
- [ ] **Paso 6: commit.** `git commit -m "feat(h1): trace input event-to-send for gamepad, mouse, keyboard and touch"`.

### Tarea 8: Contadores de audio y de sesión

**Archivos:**
- Crear: `ClueClient/app/src/main/java/com/clue/trace/SessionCounters.java`
- Modificar: `ClueClient/app/src/main/java/com/limelight/binding/audio/AndroidAudioRenderer.java`, en la rama "Too much pending audio data" (líneas ~188–200)

**Interfaces:**
- Produce `com.clue.trace.SessionCounters`, con campos `AtomicLong`:
  - `audioPacketsDropped`
  - `audioUnderruns`: `AudioTrack.getUnderrunCount()` al cerrar
  - `audioPacketsPlayed`
- Agregar `counters()` a `TraceSession`.

- [ ] **Paso 1: incrementar los contadores.** `audioPacketsDropped` en la rama de descarte, `audioPacketsPlayed` en cada `write` exitoso, y `audioUnderruns` al cerrar el `AudioTrack`.
- [ ] **Paso 2: verificar en el dispositivo.** En un stream de 2 min con audio, `audioPacketsPlayed` tiene que ser ≈ 24000 (paquetes de 5 ms) y `audioPacketsDropped` tiene que coincidir con la cantidad de líneas "Too much pending audio data" en logcat.
- [ ] **Paso 3: commit.** `git commit -m "feat(h1): audio drop and underrun counters"`.

### Tarea 9: Escritura de trazas y descarga

**Archivos:**
- Crear: `ClueClient/app/src/main/java/com/clue/trace/TraceWriter.java`
- Test: `ClueClient/app/src/test/java/com/clue/trace/TraceWriterTest.java`
- Crear: `ClueClient/tools/pull-traces.sh`

**Interfaces:**
- Produce: `static boolean TraceWriter.write(TraceSession s, File dir, Map<String,String> meta)`. Escribe:
  - `frames.csv`, con las columnas `frame,firstPacketUs,completeUs,hostLatency01ms,decodeInNs,decodeOutNs,releaseNs,targetVsyncNs,presentNs`;
  - `input.csv`, con las columnas `type,eventNs,sentNs`;
  - `summary.txt`, con `clave=valor`: el contenido de `meta`, los contadores de sesión, `ptsMisses`, y el modelo, la resolución, los fps, el bitrate y el pacing.
  - Devuelve `false` si algo falla. **Nunca lanza excepciones.**
- `meta` lo arma `TraceSession.stop()`. El directorio es `getExternalFilesDir("traces")/<yyyyMMdd-HHmmss>/`, y la escritura corre en un hilo de fondo.
- `tools/pull-traces.sh [destino]` copia `/sdcard/Android/data/com.clue.client/files/traces/` a `destino`. El default es `../traces`, fuera de git.

- [ ] **Paso 1: tests que fallan.**
  - `writesHeadersAndRows`: con 2 frames y 1 evento, los CSV tienen exactamente los encabezados de arriba y 2 y 1 filas.
  - `writeFailureDoesNotThrow`: si `dir` es un archivo y no un directorio, devuelve `false` sin lanzar excepciones.
- [ ] **Paso 2: ejecutarlos.** `./gradlew :app:testNonRootDebugUnitTest --tests com.clue.trace.TraceWriterTest`. Esperado: FAIL.
- [ ] **Paso 3: implementar `TraceWriter`.** Llamarlo desde `TraceSession.stop()` y también desde `Game.onStop`, para cubrir el cierre de la app.
- [ ] **Paso 4: ejecutar los tests.** Esperado: PASS. En el dispositivo, después de un stream, `bash tools/pull-traces.sh` tiene que bajar una carpeta con los 3 archivos.
- [ ] **Paso 5: commit.** `git commit -m "feat(h1): export traces to CSV and pull script"`.

### Tarea 10: Estadísticas en el overlay

**Archivos:**
- Crear: `ClueClient/app/src/main/java/com/clue/trace/TraceStats.java`
- Test: `ClueClient/app/src/test/java/com/clue/trace/TraceStatsTest.java`
- Modificar: `ClueClient/app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java`, donde se arma el texto del overlay (líneas ~1392–1410)

**Interfaces:**
- Produce `com.clue.trace.TraceStats`, con `long[]` preasignados y sin asignar memoria por llamada:
  - `TraceStats(int window)`. La app usa `window = 240`, es decir, 2 s a 120 fps.
  - `void compute(FrameTrace ft, InputTrace it, long vsyncNs)`
  - getters `p50`/`p99` en ms para estas etapas:
    - `netAssembly`: `completeUs − firstPacketUs`
    - `decode`: `decodeOutNs − decodeInNs`
    - `toPresent`: `presentNs − completeUs×1000`
    - `inputSend`: `sentNs − eventNs`
  - `repeatedPct`: porcentaje de intervalos de presentación con ≥ 1,5×vsync.
  - `unpresentedPct`.
- El percentil se calcula con *nearest-rank* sobre los valores válidos (> 0) de la ventana.

- [ ] **Paso 1: tests que fallan.**
  - `percentilesNearestRank`: con valores 1..100 ms, p50 = 50 y p99 = 99.
  - `repeatedCountsDoubleIntervals`: con presentaciones cada 8,333 ms y una de 16,67 ms, el resultado es `repeatedPct` ≈ 1/N·100.
  - `ignoresMissingStages`: los frames con `presentNs==0` no entran en `toPresent`.
- [ ] **Paso 2: ejecutarlos.** `./gradlew :app:testNonRootDebugUnitTest --tests com.clue.trace.TraceStatsTest`. Esperado: FAIL.
- [ ] **Paso 3: implementar `TraceStats`.** Agregar tres líneas al overlay existente, con `compute` una vez por actualización del overlay (no por frame):
  - `Clue red+ensamblado p50/p99 · decode p50/p99 · a pantalla p50/p99`
  - `Repetidos X% · no presentados Y% · audio descartado N`
  - `Entrada→envío p50/p99`
- [ ] **Paso 4: ejecutar los tests.** Esperado: PASS. En el dispositivo, el overlay tiene que mostrar las tres líneas con valores plausibles.
- [ ] **Paso 5: commit.** `git commit -m "feat(h1): Clue latency and cadence lines in the performance overlay"`.

### Tarea 11: Herramientas de análisis en el PC y línea base de Clue

**Archivos:**
- Crear: `ClueClient/tools/TraceAnalyze.java`, un programa Java de un archivo (`java tools/TraceAnalyze.java <carpeta>`)
- Crear: `ClueClient/tools/cadence.sh`. Toma muestras de `dumpsys SurfaceFlinger --frametimeline -all` durante N segundos, para la capa `SurfaceView[com.clue.client…]`, e imprime el porcentaje de intervalos a 1, 2 y 3+ vsync. Es la versión versionada de los scripts de medición que se usaron en las pruebas.
- Crear: `ClueClient/docs/measurements/README.md` (protocolo) y `ClueClient/docs/measurements/2026-10-xx-baseline-clue-0.1.md` (resultados)

**Interfaces:**
- Consume: los archivos de la Tarea 9.
- Produce: una tabla por etapa (n, p50, p95, p99 y máximo, en ms), con la cadencia según `presentNs` (porcentaje de 1x, 2x y 3+ vsync), la entrada→envío y los contadores. Las deltas negativas se cuentan y se listan aparte.

- [ ] **Paso 1: test del analizador.** En `TraceAnalyze.java`, un `main` con el argumento `--selftest` que arma en memoria un CSV con deltas conocidas (incluida una negativa) y verifica dos cosas:
  - `negativeDeltasAreReportedNotAveraged`: la negativa aparece en el contador `negative=1` y no entra en los percentiles.
  - Los p50 coinciden con los valores esperados.

  Ejecutar `java tools/TraceAnalyze.java --selftest`. Esperado: `SELFTEST OK`.
- [ ] **Paso 2: implementar el análisis** sobre carpetas reales.
- [ ] **Paso 3: escribir `docs/measurements/README.md`.**
  - Host Vibepollo 2.0.x en modo Desktop, con P4 + AQ + split-frame.
  - TestUFO a pantalla completa.
  - Corridas de ≥ 120 s, alternando A/B.
  - Usar `tools/cadence.sh` y `tools/TraceAnalyze.java`.
- [ ] **Paso 4: línea base.** Con Clue 0.1 (defaults de la Tarea 3), hacer 2 corridas de 120 s. En el documento de resultados anotar:
  - la cadencia de `cadence.sh`, para comparar con Artemis (95–96 % a 1 vsync);
  - la tabla de `TraceAnalyze`;
  - el overhead de CPU: `adb shell top -n 5 -p <pid>`, con y sin traza; para esto, agregar en `TraceSession` un interruptor de preferencia `clue_trace_enabled`, en `true` por defecto.

  Verificar que el overhead sea ≤ 1 %.
- [ ] **Paso 5: commit y push.**
  ```bash
  git add ClueClient/tools ClueClient/docs/measurements
  git commit -m "feat(h1): trace analyzer, cadence tool and Clue 0.1 baseline"
  git push origin main
  ```

---

## Fuera de este plan (siguen en planes propios)

- **H2:** presentador alineado al vsync con colchón adaptativo.
- **H3:** prioridad de entrada AC_VO, envío no confiable con redundancia, detección del enlace del mando y prueba botón → pantalla. Antes hay que vendorizar moonlight-common-c como subtree.
- **H4:** audio con AAudio.
- **H5:** red del lado del cliente.
- **H6:** negociación de extensiones `x-clue-`.

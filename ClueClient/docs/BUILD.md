# Cómo compilar Clue (Windows)

Pasos para preparar la máquina de build. Se probaron en Windows 11 con Git Bash.

1. **JDK 21:**

   ```powershell
   winget install --id EclipseAdoptium.Temurin.21.JDK -e
   ```

   Después, apuntá `JAVA_HOME` a `C:\Program Files\Eclipse Adoptium\jdk-21.*-hotspot`.

2. **Android command-line tools.** Descargá `commandlinetools-win-*_latest.zip` de `https://dl.google.com/android/repository/` y descomprimilo de modo que quede `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat`.

3. **Licencias del SDK.** Tenés que leerlas y aceptarlas vos:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
   ```

4. **Paquetes del SDK:**

   ```powershell
   sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "ndk;29.0.14206865"
   ```

5. **`ClueClient/local.properties`.** No se versiona. Contiene una sola línea:

   ```
   sdk.dir=C\:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk
   ```

6. **Build de debug:**

   ```bash
   cd ClueClient && ./gradlew :app:assembleNonRootDebug
   ```

   El APK queda en `app/build/outputs/apk/nonRoot/debug/`.

Los tests de JVM se corren con:

```bash
./gradlew :app:testNonRootDebugUnitTest
```

## Instalar en la tablet

Para compilar e instalar la release en el dispositivo conectado:

```bash
tools/build-install.sh release
```

En HyperOS (Xiaomi/POCO) hay que activar "Instalar vía USB" en las opciones de desarrollador. Además, la primera vez hay que aceptar en la tablet el aviso de instalación; si no se activa esa opción, la instalación falla con `INSTALL_FAILED_USER_RESTRICTED`.

## Variantes: estándar y "game"

- **Estándar** (`./gradlew :app:assembleNonRootRelease`): sin `android:isGame` ni `appCategory="game"`. Es la predeterminada, porque Legion Space de Lenovo limita a 120 Hz a las apps de juego (ver `docs/measurements/2026-11-08-tb323fu-165hz.md`).
- **Game** (`./gradlew :app:assembleNonRootRelease -PclueGameCategory`): suma `src/gameCategory/AndroidManifest.xml` encima del manifest principal y declara la app como juego. Sirve para los modos de juego de otros fabricantes, por ejemplo Game Turbo en Xiaomi.

Las dos tienen el mismo `applicationId`: si están firmadas con la misma clave, una se instala encima de la otra sin perder los datos ni el emparejamiento.

## CI (GitHub Actions)

`.github/workflows/build-apk.yml`, en la raíz del repo, corre en cada push a `main`, en cada PR y a mano (`workflow_dispatch`):

1. Corre los tests de JVM.
2. Compila las dos variantes de release.
3. Comprueba que solo la "game" declare `isGame`.
4. Sube `clue-<versión>-standard.apk` y `clue-<versión>-game.apk` como artefacto `clue-apks` de la corrida.

Si se pushea un tag `v*` (por ejemplo `git tag v0.2.0 && git push origin v0.2.0`), además crea un GitHub Release con los dos APK.

**Firma.** Para que los APK de CI se puedan instalar encima de una instalación existente, hacen falta cuatro secrets en el repo (Settings → Secrets and variables → Actions). Los tiene que cargar el dueño del repo:

```bash
base64 -w0 ClueClient/clue-release.jks | gh secret set CLUE_KEYSTORE_BASE64 -R DanielD2G/Clue
gh secret set CLUE_KEYSTORE_PASSWORD -R DanielD2G/Clue
gh secret set CLUE_KEY_ALIAS -R DanielD2G/Clue
gh secret set CLUE_KEY_PASSWORD -R DanielD2G/Clue
```

(Los tres últimos piden el valor por teclado; son los mismos que están en `keystore.properties`). Sin esos secrets, CI firma con la clave de debug y el nombre del APK termina en `-debugsigned`: se puede instalar en un equipo limpio, pero no encima de la release.

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

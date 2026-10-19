# Clue — reglas del fork

Clue es un fork de [moonlight-android](https://github.com/moonlight-stream/moonlight-android) v12.2. Su objetivo es la mejor experiencia de game streaming posible en nuestro hardware, con latencia casi nula. Estas reglas valen para cualquier persona o agente que toque este código.

## Reglas

1. **Performance sobre todo.** Ante la duda, gana la opción más rápida. Nada en el camino caliente puede asignar memoria, bloquear ni esperar sin un tope. Los caminos calientes son la recepción de red, el reensamblado, la decodificación, la presentación, el audio y la entrada.
2. **Baja latencia sobre todo, especialmente en los mandos.**
   - La entrada (mandos, mouse, teclado y touch) tiene la máxima prioridad del proyecto, por encima del video y del audio: en el aire (WMM AC_VO), en los hilos y en las colas. Sale apenas se produce, sin agrupar ni retener eventos.
   - Mientras pueda seguir siendo fiable, ningún evento de entrada espera detrás de otro.
   - Ninguna cola del pipeline de video crece más allá de un frame.
3. **Empujamos el hardware.** Primero exprimimos al máximo nuestros equipos de referencia; la compatibilidad genérica viene después.
   - Si un camino específico (claves de vendor de Qualcomm, prioridades de hilos, modos de Wi-Fi o pantalla) da una ganancia medible, se usa.
   - Para el resto de los dispositivos queda un fallback seguro.
4. **Prioridad en usabilidad.** Rendir más no justifica una app difícil de usar. Los defaults tienen que ser los correctos para nuestro hardware: el usuario no debería tener que tocar nada para obtener la mejor experiencia.

## Prioridades

1. La mejor experiencia en el cliente: imagen fluida, sin tirones ni cortes de audio, y respuesta inmediata.
2. Baja latencia de punta a punta.

## Requisitos importantes

- **La app se llama Clue.**
  - Package id propio, para convivir con Moonlight y Artemis instalados.
  - Nombre, ícono y textos de Clue.
  - Se respeta la licencia GPL-3.0 y se mantiene la atribución a Moonlight.
- **Latencia casi nula.** Ninguna optimización se acepta sin medición, y cada mejora se mide sobre la línea base. Ver la sección [Medición](#medición).

## Si se puede, o más adelante

- **Soporte de PyroWave**, el códec de Vibepollo/Vibeshine, solo si es compatible con nuestro hardware y objetivos. Necesita mucho ancho de banda y decodificación propia, así que primero hay que evaluar si en la tablet rinde mejor que HEVC.
- **Mejor UI.**

## Hardware de referencia

- **Cliente:** Xiaomi POCO Pad (`2405CPCFBG`, codename `dizi`).
  - Snapdragon 7s Gen 2 y panel de 2560×1600 a 120 Hz.
  - Decodificador `c2.qti.hevc.decoder.low_latency`, calificado para 4K60 y 70 Mbps. No tiene AV1 por hardware.
  - HDR solo Dolby Vision; no tiene HDR10.
  - Wi-Fi 6 a 160 MHz.
- **Host:** RTX 5080 (NVENC con 2 motores), Ryzen 7 9850X3D y Windows 11. Hoy corre Vibepollo 2.0.x.
- **Configuración medida como mejor** con clientes de serie: 2560×1600 a 120 fps y 80 Mbps HEVC. En Vibepollo: preset P4, AQ espacial y split-frame forzado.

## Compatibilidad

- Clue tiene que funcionar contra hosts de serie: Sunshine, Apollo y Vibepollo.
- Toda mejora que necesite cooperación del host se negocia como **extensión opcional** y nunca rompe el protocolo de serie. Ejemplos: el reporte de la fase de vsync para eliminar el batido de relojes, o el feedback por frame.

## Medición

- **Ninguna optimización entra sin datos.** Se compara contra la línea base con el mismo contenido y el mismo protocolo.
- **Cadencia.** Se toma de `dumpsys SurfaceFlinger --frametimeline -all`, mirando los tiempos de presentación de la capa de video.
  - Las corridas duran **120 s como mínimo**, porque el batido entre el reloj del host y el del panel tiene un ciclo de alrededor de un minuto.
  - En Android 16, `dumpsys SurfaceFlinger --latency` no devuelve datos.
- **Latencia.** Se mide con la instrumentación por frame de Clue, con reloj sincronizado al host. El "decode time" del overlay de Moonlight/Artemis no es una métrica confiable, porque mezcla colas y relojes.

## Código y repositorio

- El repo `Clue` versiona solo `ClueClient/`. moonlight-android se importó con `git subtree`, conservando el historial.
- Las actualizaciones de upstream se traen así:

  ```bash
  git subtree pull --prefix=ClueClient moonlight-upstream <tag>
  ```

- `moonlight-common-c` es un submódulo, mapeado en el `.gitmodules` de la raíz del repo.
- Los cambios quedan acotados y bien comentados, así los merges desde upstream siguen siendo manejables. Hay que evitar reformatear o mover código upstream sin necesidad.

# Clue — autotuner de bitrate: diseño

## Intención

El usuario no quiere volver a ajustar el bitrate a mano. Clue encuentra solo el bitrate más alto que se mantiene estable en su red y en su tablet, como la prueba inicial de Steam Link, y lo sugiere.

El 80 Mbps actual nunca se midió: salió de la puesta a punto de Artemis. El decoder `c2.qti.hevc.decoder.low_latency` de la POCO Pad declara 70 Mbps.

## Decisiones del usuario

- La prueba es como la de Steam Link: se corre una prueba y sale el bitrate ideal.
- Corre sola la primera vez que se conecta. Después se puede correr a mano, y al final sugiere un bitrate.
- Puede tardar lo que haga falta: 1 o 5 minutos.

## Hallazgos que fijan el cómo

- **Ni Artemis ni Apollo/Vibepollo tienen prueba de bitrate ni cambio de bitrate en vivo.** El bitrate se fija al iniciar cada sesión, así que cada escalón de la prueba es una sesión nueva o reanudada.
- **El tráfico real depende del contenido.** Con un escritorio quieto el encoder casi no genera datos, así que la prueba necesita imagen incompresible generada en el host.

## Diseño (decisiones del agente)

1. **App de prueba en el host.**
   - En Vibepollo se suma una app "Clue Bitrate Test". Abre Chrome en kiosco, con un perfil aislado, sobre `bitrate-test.html`: ruido aleatorio a pantalla completa generado por WebGL en cada frame.
   - El ruido cambia por completo en cada frame, así que el encoder no puede comprimirlo y el tráfico se acerca al bitrate pedido.
   - Instalación en el host: `tools/host/install-bitrate-test.ps1`. Copia la página a `C:\ProgramData\Clue`, agrega la app a `apps.json` y reinicia el servicio. Pide un UAC y se hace una sola vez.
2. **Búsqueda.**
   - Escalones geométricos ×1,3 desde 20 Mbps hasta el primero que falla, con tope de 150 Mbps.
   - Después, bisección entre el último que pasó y el que falló, hasta que el intervalo quede por debajo del 10 %.
   - Cada escalón: 4 s de calentamiento descartados y 12 s de medición.
3. **Qué cuenta como "estable"** en un escalón. Tiene que cumplir todo:
   - cuadros perdidos por la red (`framesLost / totalFramesReceived`) < 0,5 %;
   - decodificación p95 ≤ 2 períodos de frame, para que el decoder no se atrase;
   - cuadros recibidos pero no presentados < 2 %;
   - RTT p95 ≤ el RTT p95 del primer escalón + 15 ms;
   - fps recibidos ≥ 90 % de los pedidos.
4. **Sugerencia.**
   - Mayor escalón estable × 0,85, para dejar margen a los vaivenes del Wi-Fi, redondeado a 5 Mbps.
   - Nunca por encima del máximo que declara el decoder para el códec en uso, si lo declara.
5. **Cuándo corre.**
   - **Automático** la primera vez que se lanza un stream a un PC desde una red Wi-Fi para la que no hay resultado guardado. Se puede saltear y no vuelve a preguntar para ese par PC + red.
   - **Manual** desde el menú contextual del PC y desde Ajustes.
6. **Resultado y guardado.**
   - Diálogo "Recomendado: X Mbps · Aplicar / Cancelar", con el detalle de cada escalón.
   - Se guarda por PC (uuid) y red (SSID, o "cableado"/"desconocida").
   - Aplicar escribe la preferencia de bitrate.
7. **Respaldo.** Si el host no tiene la app de prueba, avisa, explica cómo instalarla y no sugiere nada: un resultado medido sobre contenido comprimible sería poco confiable.

## No objetivos

- Bitrate adaptativo durante el juego.
- Prueba sin ayuda del host.
- Ajuste de la resolución o de los fps.

## Antiobjetivos

- Sugerir un bitrate medido sobre contenido que no exige al encoder.
- Dejar abierta la app de prueba en el PC.
- Sugerir algo que después corte o trabe el stream.

## Queda a criterio de quien implemente

- La estructura de la UI de progreso y del diálogo, siguiendo el launcher nuevo.
- Si cada escalón usa una actividad `Game` nueva o la reutiliza.
- Cómo se leen las métricas de cada escalón, siempre que sea sin asignar memoria en el camino caliente.

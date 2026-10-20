# Clue — launcher estilo consola

Rama: `clue/launcher-ui` (desde `clue/h0-h1` @ `c0522f4e`).

## Objetivo

Reemplazar las grillas de Moonlight (`PcView` y `AppView`) por un launcher de consola como el de la
referencia del usuario: fondo oscuro a pantalla completa con la carátula enfocada desenfocada, barra
superior con pestañas e indicadores, carrusel horizontal de carátulas altas donde la enfocada es más
grande y brilla, título e info del juego, y pistas de botones abajo a la derecha. Primero el mando,
también táctil. Hardware de referencia: POCO Pad, 2560×1600 a 320 dpi (1280×800 dp), Android 16.

## Decisiones (sin ronda de preguntas: el usuario pidió arrancar directo)

- Views nativas en Java + XML, sin dependencias nuevas. Nada de esto está en el camino caliente del
  streaming; aun así el launcher no asigna por frame y el blur lo hace el RenderNode.
- **Carrusel** `ui/CoverCarousel` (`HorizontalScrollView` propio): consume el mismo `BaseAdapter`
  (`GenericGridAdapter`), así `PcView`/`AppView` siguen usando `getItem()` y las rutinas de upstream.
  - El carrusel es el único focusable; maneja el índice seleccionado. La carátula seleccionada queda
    anclada a la izquierda (como en la referencia) y crece (ancho y alto animados, alineadas arriba).
  - Bind perezoso: solo se llama a `getView()` para la ventana visible ± margen, porque la cola del
    `CachedAppAssetLoader` descarta cargas si se piden más de ~40 de golpe.
  - Glow: bitmap pre-desenfocado (BlurMaskFilter en canvas por software, una vez) dibujado detrás de la
    seleccionada, más un borde blanco. No depende de sombras del tema.
  - Táctil: deslizar desplaza y al soltar engancha a la carátula más cercana; tocar una no
    seleccionada la selecciona, tocar la seleccionada la abre; pulsación larga abre el menú.
- **Mando**: cruceta/stick izquierda-derecha mueve; A abre; Y (o Menú) abre el menú contextual de
  siempre; X busca (biblioteca); B atrás (fallback del sistema); L1/R1 cambian de pestaña;
  arriba/abajo pasa entre el carrusel y la barra superior.
- **Pestañas**: Biblioteca (juegos del equipo) · Equipos (lista de PCs) · Ajustes. `PcView` es
  Equipos, `AppView` es Biblioteca; Ajustes abre `StreamSettings`. Desde Equipos, ir a Biblioteca
  abre el equipo seleccionado si está vinculado y en línea.
- **Barra superior derecha**: buscar (biblioteca), añadir PC y ayuda (equipos), indicador de mando
  conectado, reloj (`TextClock`), Wi-Fi y batería (vista propia). Pantalla inmersiva (sin barras del
  sistema) en las dos pantallas del launcher.
- **Info**: título del seleccionado y un chip: "En curso" si el juego está corriendo; en equipos el
  estado (en línea / sin conexión / sin vincular / jugando). No hay datos de tiempo de juego en el
  protocolo, así que no se inventa.
- **Fondo**: carátula seleccionada, reducida a un bitmap chico y desenfocada con `RenderEffect`
  (API 31+); en versiones viejas el bitmap chico escalado ya sirve de blur barato. Fundido cruzado
  entre dos `ImageView` y un scrim oscuro.
- Todo lo de antes sigue accesible: vincular, añadir PC, ayuda, ajustes, menús contextuales de PC y
  de juego (reanudar, salir, ocultar, detalles, accesos directos), WoL, test de red, ver ocultos.
- Textos en inglés (`values`) y español (`values-es`).

## Pasos

1. Recursos: colores, dimensiones, íconos vectoriales, layouts nuevos (`activity_pc_view`,
   `activity_app_view`, barra superior compartida, ítems de carátula y de PC).
2. `ui/CoverCarousel`, `ui/CoverImageView`, `ui/LauncherStatusView` (Wi-Fi/mando/batería),
   `ui/LauncherUi` (barra, pestañas, fondo, info, pistas, inmersivo).
3. Adaptadores: layout de carátula nuevo y filtro de búsqueda en `AppGridAdapter`.
4. `PcView` / `AppView`: cablear el carrusel en lugar del `AdapterFragment`, mantener menús.
5. Compilar release, instalar en la POCO Pad (preguntar antes si otra sesión mide), probar con
   mando y táctil, capturas.

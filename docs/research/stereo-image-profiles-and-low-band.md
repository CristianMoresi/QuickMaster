# Stereo Image: perfiles y generación de graves

Fecha: 28-09-2026. Investigación previa; ningún cambio en el DSP o la UI de la app.
Plan general: `../stereo-image-development-plan.md`.

**Ampliación posterior:** `stereo-corpus-study-plan.md` sustituye la lista cerrada
de cinco géneros y el tamaño de muestra inicial. Este documento conserva las
mediciones exploratorias previas; no supone que la investigación esté cerrada.

## Conclusiones que sí están respaldadas

1. Añadir `Generate Low Frequencies`, **desmarcado por defecto**. Su ámbito es
   exclusivamente el estéreo nuevo, no convertir el grave original a mono.
2. Proponer 300 Hz como límite nominal conservador, no un óptimo demostrado.
   Contrastar 200/250/300 Hz antes de fijarlo. Apple aconseja no bajar de 300 Hz
   en su generador por bandas; eso orienta, pero no valida nuestro algoritmo.
   [Apple Stereo Spread](https://support.apple.com/en-asia/guide/logicpro/lgcef240df26/10.7/mac/11.0).
3. Filtrar el **delta final**, después de generar armónicos, no solo restringir
   los centros de EQ. Las colas de las campanas y la intermodulación pueden
   introducir componentes inferiores. Es una condición de diseño a comprobar.
4. `Electronic`, `Pop`, `Rock`, `Hip Hop`, `R&B` eran familias iniciales, no una
   cobertura suficiente. La selección definitiva se derivará del estudio ampliado,
   con estadísticas por frecuencia, además de `Auto (This Track)` y referencia local.
5. No hay en las fuentes revisadas una relación perfecta universal. iZotope
   documenta referencias de éxitos por género en cuatro bandas y un enfoque más
   centrado para Rap/R&B. Su gráfico no identifica explícitamente dB en el eje:
   no trasladar sus ordenadas a coeficientes de QuickMaster.
   [Explicación de Ozone](https://www.izotope.com/community/blog/behind-the-technology-of-izotope-ozone-10).
6. Una referencia musical y un límite técnico son distintos. Un percentil de
   canciones no basta para definir el Guard. No declarar defectuoso un master por
   quedar fuera de la mediana de su género.

El enfoque actual de referencias contextuales también aparece en
[STEREOVAULT](https://www.masteringthemix.com/pages/stereovault-manual) y
[Tonal Balance Control](https://www.izotope.com/products/tonal-balance-control).
Ninguna de las páginas consultadas aporta el corpus numérico reproducible que
necesitamos para calibrar estos cinco perfiles.

## Lo que se ha medido realmente

Medidor reproducible: `stereo_measurements.py`. Resultados completos:
`stereo-input-measurements-20260928.json`. Python 3.14.6, NumPy 2.5.1, SciPy 1.18.1.

- `M=(L+R)/2`, `S=(L-R)/2`, ratio `10 log10(Ps/Pm)`; negativo = menos Side.
- Medida global con todos los samples, sin puertas ni remuestreo.
- Espectro: FFT Hann de 3 s, hop 1,5 s; bandas disjuntas 0/120/200/250/300/2000/
  10000 Hz/Nyquist. Ventanas activas con potencia >= máximo de −60 dBFS y P95−10 dB.
  No es reconocimiento de estrofas/estribillos, ni detector de sonoridad perceptiva.
- Los cocientes por banda son cocientes de energías sumadas de esas ventanas,
  no promedio de dB ni igualdad con el resultado de filtros de crossover reales.
  Se guardan además P10/P50/P90, contabilizando ventanas vacías aparte.
- Ventanas solapadas no son observaciones independientes. No calcular confianza
  por género a partir de su número. Los extremos sin ventana completa no entran
  en el espectro por ventanas; sí en el cálculo global.

| Fuente | S/M global, archivo completo | S/M <250 Hz, ventanas activas | S/M >=250 Hz, mismas ventanas |
| --- | ---: | ---: | ---: |
| By Now | −3,92 dB | −3,87 dB | −4,05 dB |
| Quiet Gold | −7,16 dB | −10,85 dB | −4,50 dB |
| Billie Jean (80s Glam Metal) | −11,10 dB | −11,80 dB | −10,46 dB |
| Wicked Game (80s Synthwave) | −8,24 dB | −9,34 dB | −6,61 dB |
| Incredulity — Scott Buckley | −2,92 dB | −4,81 dB | +0,16 dB |

**Estas son cinco fuentes, no cinco medias de género.** Las cuatro primeras son
las producciones privadas disponibles del usuario, no los masters comerciales
de los artistas originales cuyos títulos puedan coincidir. La quinta es una
publicación neoclásica/ambient de 2025, disponible como MP3 320 kbps; no se ha
verificado un crédito independiente de mastering. Es control externo, no una
muestra suficiente de Cinematic, Pop o ningún otro perfil. No se ha realizado
una evaluación de escucha ni una comparación procesada del generador.

El sondeo demuestra que el ratio global puede ocultar distribuciones espectrales
muy diferentes. También muestra Side grave original significativo: quitarlo al
desmarcar la nueva casilla sería un procesamiento distinto al solicitado.

### Comparación numérica de las regiones candidatas

Porcentaje de energía original situado debajo del límite, mismas ventanas activas:

| Fuente | <200 Hz | <250 Hz | <300 Hz |
| --- | ---: | ---: | ---: |
| By Now | 70,54 % | 72,47 % | 73,20 % |
| Quiet Gold | 50,43 % | 53,68 % | 60,47 % |
| Billie Jean (80s Glam Metal) | 44,44 % | 50,18 % | 53,25 % |
| Wicked Game (80s Synthwave) | 61,10 % | 64,44 % | 68,27 % |
| Incredulity | 55,80 % | 69,44 % | 73,27 % |

Esto describe la señal, **no energía eliminada**: el dry se conserva. Tampoco
predice directamente cuánta energía producirán EQ y armónicos. No prueba que
250 o 300 Hz suenen mejor; ese salto de inferencia sería injustificado.

### Verificación independiente

Autopruebas aprobadas: Parseval para FFT par/impar, ratio conocido −6,0206 dB,
asignación de siete tonos a sus bandas, mono/antífase/silencio como cocientes
límite, un solo canal, invariancia de ganancia y cambio L/R, reconstrucción de
energía M/S. FFmpeg 9.0.2 `pan` + `astats` contrasta además los RMS globales:

| Fuente | Mid RMS dBFS, FFmpeg | Side RMS dBFS, FFmpeg |
| --- | ---: | ---: |
| By Now | −15,740827 | −19,663938 |
| Quiet Gold | −14,848421 | −22,009358 |
| Billie Jean | −13,752394 | −24,854283 |
| Wicked Game | −18,115502 | −26,351461 |
| Incredulity | −19,429158 | −22,345397 |

El cociente difiere menos de 0,00002 dB respecto al analizador. Hash antes/después
de las cinco entradas idéntico; originales intactos. SciPy avisa de chunks WAV
auxiliares desconocidos; se decodificaron los canales PCM sin modificar el archivo.

## Procedencia del control descargado

- Atribución: **“Incredulity” by Scott Buckley — released under CC-BY 4.0.
  www.scottbuckley.com.au**. No implica respaldo del compositor a QuickMaster.
- [Página del autor y licencia](https://www.scottbuckley.com.au/library/incredulity/).
- [Condiciones del autor](https://www.scottbuckley.com.au/library/using-this-music/).
- [Archivo MP3 ofrecido por el autor](https://www.scottbuckley.com.au/library/wp-content/uploads/2025/04/Incredulity.mp3).
- Fecha de descarga: 28-09-2026. MP3 SHA-256:
  `cce101d47fe4b345ca33a3d43e2331b1f1687045ed1bdc3dbae180d0edda3b90`.
- 44,1 kHz, 2 canales; conversión local a WAV float32 sin procesado ni remuestreo
  para medir con la misma herramienta. No afirmar que convierte MP3 en lossless.
  SHA del WAV en JSON. Audio bajo `dist/stereo-research-20260928/audio/`, ignorado
  por Git; no incorporado a la app ni redistribuido. Solo resultados en el repo.

## Fuentes de corpus revisadas, sin confundir acceso con representatividad

| Fuente | Utilidad / límite observado |
| --- | --- |
| [MTG-Jamendo](https://github.com/MTG/mtg-jamendo-dataset) | Muchas etiquetas; impone investigación no comercial y autorización adicional para integración comercial. No usar silenciosamente para perfiles del producto. La variante mono no sirve para M/S. |
| [Cambridge](https://cambridge-mt.com/ms2/mtk-faq/) | Material de práctica y mezclas, no corpus certificado de masters recientes; permisos de investigación requieren atención específica. |
| [MUSDB18](https://sigsep.github.io/datasets/musdb.html) | Dataset de separación, útil como prueba DSP, no una selección representativa de masters actuales por género. |
| [blocSonic](https://blocsonic.com/releases/genre/pop/1/) | Releases recientes y FLAC; licencias individuales, frecuentemente NC, y fuerte concentración de artistas. No se ha descargado ni utilizado para calibrar perfiles. |
| [Pueblo Nuevo, Dinámica Estructural](https://pueblonuevo.cl/catalogo/dinamica-estructural/) | Edición electrónica 2024 con crédito de mastering y descarga FLAC; un álbum y licencia NC, no una población por género. No descargado. |
| [Toucan Music](https://www.toucanmusic.com/albums/) | Electrónica, con remasters recientes; principalmente MP3 y condiciones individuales. No descargado ni considerado lossless. |

No se han comprado pistas, contratado licencias, creado cuentas, contactado
titulares ni extraído audio de plataformas de streaming. La disponibilidad de
descarga no se ha usado para presumir permisos de integración comercial.

## Criterio de cierre antes de desarrollar

**La calibración no está cerrada.** La investigación permite definir checkbox,
perfiles y método, pero todavía no justifica cinco tablas de objetivos definitivos.
Las fuentes públicas revisadas no resuelven por sí solas el corpus necesario.

1. Seleccionar fuentes con mastering/versión y permisos trazables, diversidad por
   artista/producción y presencia vocal/instrumental adecuada a cada perfil.
   El tamaño y cobertura pasan a los criterios del estudio ampliado. No considerar
   el número de pistas una garantía estadística automática.
2. Obtener distribuciones por pista/sección y bandas, con validación separada por
   producción. Cuantificar variabilidad, no ocultarla con la palabra “perfecto”.
3. Contrastar en experimentos aislados generación 200/250/300 Hz, armónicos y
   graves habilitados: energía, DC, mono, balance, transitorios y escucha cuando
   sea posible. Publicar parámetros como provisionales si solo hay datos técnicos.
4. Resolver si el control Side escalar con objetivo condicionado al espectro basta
   o necesita regulación espectral lenta para estos perfiles. No prometer que
   una sola ganancia iguala cuatro ratios independientes.

Hasta entonces, H0 queda abierto y no se implementa el módulo. No sustituir esta
petición por desarrollar primero y justificar los presets después. La app
instalada permanece intacta; no corresponde build/deploy por esta investigación.

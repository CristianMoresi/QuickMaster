# Stereo Image — plan de investigación de cobertura y captura silenciosa

Fecha: 28-09-2026. Estado: borrador histórico, sustituido por campaña 50/50 completada.
**Revisión posterior y alcance vigente:** `stereo-campaign-state-20260928.md`.
El usuario limita el estudio a diez familias generales / cinco canciones por
familia (50); sustituye los tamaños y la taxonomía exploratoria de este borrador.
La vía actual es Node/Electron sin extensión, no la extensión aquí propuesta.
Resultados vigentes: `stereo-study-20260928/README.md` y `verification.json` en
ese directorio: diez familias, cinco canciones cada una, datos estéreo y tonales.
El capturador Node/Electron está implementado y verificado. El antiguo piloto
`stereo-pilot-progress-20260928.md` conserva sus 12 controles y no cuenta por género.
Sustituye la lista cerrada de cinco géneros y el muestreo inicial insuficiente del
plan anterior. No cambia QuickMaster ni autoriza un release. Las cinco fuentes
medidas anteriormente siguen siendo pruebas del medidor, no perfiles de género.

## 1. Preguntas que debe resolver el estudio

- Qué familias de imagen estéreo aparecen en masters profesionales actuales.
- Qué diferencias necesitan un perfil distinto y cuáles son variación normal.
- Cuánto varían con frecuencia, arreglo, sección, época y método de producción.
- Qué rango constituye una referencia habitual, sin convertirlo automáticamente
  en un límite de seguridad o un óptimo perceptivo.
- Qué información necesita el Side Leveler para no confundir un bajo potente y
  centrado con una falta general de estéreo; si basta ganancia Side escalar o hace
  falta regulación espectral lenta.
- Dónde delimitar el delta generado al desactivar `Generate Low Frequencies` y
  qué restricciones aplicar al activarlo. No fijar 200/250/300 Hz por intuición.

## 2. Primero aprovechar estudios existentes

Buscar por tarea y definición, no solo por la expresión «ideal mid-side ratio»:
stereo image profiles, spatial descriptors, genre/production analysis, perceived
spaciousness, mid/side power ratio, stereo panning, width preference, codec effects.
Fuentes: AES, DAFx, ISMIR, repositorios de autores y fabricantes que documenten
métodos. Seguir las referencias relevantes de la revisión de 2026 y registrar
fecha del estudio, corpus, versiones, fórmula, bandas, ventanas, oyentes y datos
reutilizables. No confundir fecha de indexación web con fecha de publicación.

Hallazgos verificados que justifican continuar, sin afirmar inexistencia absoluta:

| Fuente | Qué aporta / qué no resuelve |
| --- | --- |
| [Revisión sistemática, Mitic/Rossholm, 2026](https://link.springer.com/article/10.1186/s13636-026-00463-4) | Repertorio de descriptores, datasets y problemas de comparabilidad; no una tabla universal de objetivos profesionales M/S por género. |
| [Tzanetakis et al., JAES 2010](https://webhome.csc.uvic.ca/~gtzan/output/stereo_panning_aes.pdf) | Información espacial útil para clasificación y producción; clasificación no equivale a preferencia ni a objetivo de mastering. |
| [Wilson/Fazenda, DAFx 2013](https://www.dafx.de/paper-archive/2013/papers/04.dafx2013_submission_47.pdf) | 55 muestras y 24 participantes; relación entre atributos y calidad. Su anchura es 1 menos correlación L/R, no dB S/M. No importar su máximo como preset moderno. |
| [Mourgela et al., AES 2024](https://arxiv.org/pdf/2412.03373) | Gran conjunto de métricas con géneros autodeclarados; plataforma dirigida principalmente a amateurs. La medida publicada de anchura se basa en diferencia de nivel entre canales, no nuestra relación de potencias por bandas. |
| [Newman-Hills, tesis 2023](https://pure.hud.ac.uk/en/studentTheses/an-investigation-into-the-factors-that-affect-user-ratings-of-mus/) | Pruebas de valoración y degradaciones, incluido estrechamiento, con limitaciones de muestra/entorno; no objetivos multibanda por género. Resumen consultado, PDF no accesible en esta revisión. |
| [Tonal Balance Control 3](https://www.izotope.com/products/tonal-balance-control) | Referencias de más de 30 géneros y captura desde reproductores: demuestra una vía práctica. No publica los datos necesarios para reproducir sus perfiles ni otorga permisos sobre las fuentes capturadas. |

Si se localizan datos suficientes con definiciones y derechos compatibles, usarlos
y validar una muestra propia, reduciendo la campaña. Si solo hay métodos o curvas
sin corpus, reutilizar el método, no inventar las mediciones faltantes. La salida
de esta fase es una tabla de evidencia aprovechable y huecos concretos.

## 3. Cobertura musical: familias de estudio, no menú definitivo

Partir de unas 30 celdas de muestreo, con etiquetas múltiples y estas familias.
Los ejemplos no implican que todos los subestilos compartan un objetivo:

| Familia | Subestilos/contrastes que hay que incluir |
| --- | --- |
| Pop | Mainstream, dance/synth-pop, indie/art-pop; incluir K-pop/J-pop, no solo repertorio anglófono |
| Rock | Alternativo/indie, hard/arena, punk/pop-punk |
| Metal | Clásico y moderno; arreglos de guitarras densos frente a producciones más abiertas |
| Hip Hop | Boom-bap/tradicional, trap/drill; no fusionarlo automáticamente con R&B |
| R&B / Soul / Funk | Contemporáneo, neo-soul, instrumentación orgánica/electrónica |
| Electronic dance | House/disco, techno/minimal, trance/progressive, dubstep/bass, drum & bass/breakbeat |
| Ambient / Downtempo | Ambient, chillout, trip-hop; texturas difusas frente a ritmos centrados |
| Acoustic / Folk / Country | Cantautor, folk, country/Americana; voz/instrumento frente a banda |
| Jazz / Blues | Formaciones pequeñas/grandes, grabación natural frente a producción por pistas |
| Classical | Cámara, orquesta, coro; salas reales y técnicas de microfonía distintas |
| Cinematic | Orquestal, híbrido/electrónico; no presumir que equivale a Classical |
| Latin | Urbano/reggaetón, pop latino, salsa/bachata, regional y flamenco, etiquetados por separado |
| Reggae / Dub / Dancehall | Versiones centradas, espaciales y electrónicas |
| Otras tradiciones | Afrobeats/Afropop y repertorios regionales de Asia/África; ampliar si quedan patrones sin cobertura, no crear una media arbitraria «World» |

Etiquetas transversales: fecha del master, estudio/directo, vocal/instrumental,
densidad, acústico/sintético, grave dominante, producción mono deliberada,
hard-panning, estéreo difuso y remaster. No son todos nuevos perfiles de UI.
Material binaural/Atmos downmix, 8D, reuploads, remixes y grabaciones de público
separados del corpus principal; no mezclar ediciones como si fueran el mismo master.

### Tamaño y selección

1. **Piloto de viabilidad: 12–20 referencias** heterogéneas. Validar acceso,
   procedencia y ruta antes de construir una colección grande.
2. **Exploración: aproximadamente 300–400 pistas completas**, unas 10–12 por
   celda inicial. Selección antes de mirar las mediciones; combinar referencias
   de catálogo profesional, actuales y menos populares. No usar únicamente
   playlists algorítmicas, los temas más anchos o lo que sea gratis de descargar.
3. Ampliar donde se vean subpoblaciones o mucha dispersión. Objetivo inicial por
   perfil publicado: **40–60 masters, al menos 20 artistas/proyectos**, con un
   tercio reservado para validación. Revisar independencia real de productores,
   álbumes y estudios; máximo dos pistas del mismo álbum para calibración.
4. El conjunto puede llegar orientativamente a **600–1.000 pistas** si hacen falta
   muchos perfiles. No es una cuota obligatoria ni demuestra calidad por sí sola.
   Si los datos permiten menos perfiles estables, no multiplicarlos para llenar
   el menú. Si falta cobertura, ampliar antes de declarar el estudio completo.
5. Corpus actual principal: masters 2020–2026. Estudios de 2010–2019 y controles
   históricos por separado; no mezclar remaster reciente con mezcla histórica
   sin guardar ambas fechas. Registrar región y sesgos de selección.

La unidad independiente es la producción, no cada ventana, URL o remaster.
Un mismo audio en Spotify y YouTube cuenta una vez; el par sirve para estudiar
la ruta de reproducción. Reservar artistas/producciones completos, no fragmentos
de una misma canción repartidos entre calibración y validación.

## 4. Spotify/YouTube y procedencia de la señal

Spotify y YouTube sirven para localizar ediciones y referencias; no descartarlos
por ser streaming ni presumir que cualquier reproducción es el master original.

- **A: archivos autorizados de versión identificada, preferentemente lossless.**
  Base para medir y para las comparaciones procesadas que permitan sus derechos.
- **B: reproducción oficial verificable y medición local permitida.** Guardar
  aplicación/versión, URL/ID, edición, codec/calidad observada, frecuencia de captura,
  configuración y tramo. Registrar desconocidos; no afirmar bit-perfect por usar
  una opción llamada lossless. Comparar con A cuando exista la misma edición.
- **C: versión o tratamiento desconocidos.** Orientación y pruebas de robustez,
  nunca coeficientes definitivos mezclados silenciosamente con A/B.

La [documentación actual de Spotify](https://support.spotify.com/us/article/audio-quality/)
distingue calidades por cliente/plan, incluido lossless. No asumir que el navegador
ofrece lo mismo que la aplicación. Spotify documenta también que el modo Loud
puede usar limitación: [normalización](https://support.spotify.com/us/artists/article/loudness-normalization/).
En YouTube comprobar los ajustes presentes, incluidos Stable volume y Voice boost,
según su [documentación](https://support.google.com/youtube/answer/14106294?hl=en-GB).

Corrección importante: una ganancia constante idéntica en L/R cancela en Ps/Pm.
No invalida por sí sola el ratio M/S. Sí pueden sesgarlo procesamiento dinámico,
espacial/EQ, conversión de codec y selección de ventanas mediante puertas absolutas.
Validar la influencia con pares de la misma edición, sin imponer de antemano que
todo streaming sea válido o inválido. Separar medición de la entrega y del master.

Probar inicialmente al menos 20 pares cuando haya acceso a las dos versiones;
si no hay pares identificables, informar de la incertidumbre y no inventar una
corrección. No extrapolar el error de un codec/cliente a los demás. No contar los
pares como 40 producciones independientes ni usar interpolación artificial de Hz
para inventar precisión.

Acceso normal y permisos comprobados por fuente. No automatizar extracción de
streams, eludir DRM, abrir cuentas, comprar suscripciones o publicar playlists.
No basar la campaña en la API de Spotify: sus
[condiciones para desarrolladores](https://developer.spotify.com/policy)
restringen expresamente el análisis de contenido. Revisar también las
[condiciones de YouTube](https://uk.youtube.com/t/terms) antes de automatizar el
acceso. El permiso del usuario para usar su equipo no sustituye esos permisos.
La captura local tampoco constituye una exención automática; si esa ruta no es
admisible, buscar la edición por una fuente autorizada, no saltarse la restricción.

## 5. Capturador local, solo si quedan datos necesarios por obtener

Propuesta técnica: **extensión mínima de navegador Chromium + analizador local**.
Node puede administrar la cola y guardar estadísticas; no puede capturar por sí
solo cualquier pestaña de otro origen. No instalar ni integrar esto en QuickMaster.

`pestaña dedicada → tabCapture → AudioWorklet/worker → métricas → JSON local`

Sin ruta audible. La [API tabCapture](https://developer.chrome.com/docs/extensions/reference/api/tabCapture)
suprime normalmente la reproducción local al entregar el stream. Requiere una
invocación/permiso inicial; no prometer capturas arbitrarias sin ese paso. Para
trabajo en segundo plano está documentado el
[documento offscreen](https://developer.chrome.com/docs/extensions/how-to/web-platform/screen-capture).
El analizador puede utilizar un
[AudioContext con salida `none`](https://developer.chrome.com/blog/audiocontext-setsinkid)
cuando la versión lo admita; comprobar funcionamiento y cadencia real.

### Silencio como requisito, no como ajuste opcional

- Navegador/perfil y pestaña de investigación aislados. No capturar micrófono,
  escritorio completo, llamadas, pestañas del usuario ni su mezcla general de audio.
- Preparar la barrera de salida **antes** de navegar a una URL que pueda reproducir.
  No usar volumen cero en el reproductor como solución: puede silenciar también
  los datos. Probar el punto real de captura.
- Además de la supresión normal de tabCapture, verificar una segunda barrera:
  sesión de salida dedicada silenciada después del punto de captura o destino
  virtual sin salida física ya disponible. Confirmar que no afecta a los programas
  del usuario y que el analizador sigue recibiendo PCM. No suponer que dos ventanas
  o perfiles de Chrome tienen sesiones de audio independientes.
- Si no se puede demostrar esa separación, no reproducir canciones. No instalar
  drivers ni cambiar el dispositivo/volumen global como atajo sin permiso específico.
- Estado: `Idle → Silent route verified → Capturing → Measuring → Paused/Stopped`.
  Nunca iniciar Play si la captura y la barrera secundaria no están preparadas.
- Pérdida de captura, cierre del worker, navegación, desconexión o caída: mantener
  la barrera de salida y pausar/cerrar exclusivamente la fuente de investigación.
  Detener el stream no basta: la pestaña podría volver a sonar. No confiar solo en
  una reacción a posteriori para evitar la primera fuga de audio.
- Primero una pestaña y trabajos secuenciales. Varias solo después de demostrar
  aislamiento y separación de mediciones. No mezclar streams de canciones distintas.
- Sin robar foco ni manipular la sesión habitual. CPU/memoria limitadas, prioridades
  bajas donde proceda, cancelación y reanudación por canción. No duplicar trabajos
  fallidos con reproducciones en bucle.

### Validación antes de música real

Fixtures locales conocidos: estéreo, dual-mono, solo L/R, antífase, silencio y
relaciones M/S conocidas. Contrastar análisis del archivo con captura del navegador;
canales, resampling, cadencia, pérdida de bloques, suspensión y reanudación.
Objetivo inicial de error de ruta en fixture sin codec: <0,1 dB por banda activa,
con diferencias explicadas. Es una tolerancia propuesta, no un resultado obtenido.

Verificar al mismo tiempo datos no nulos cuando corresponda y salida audible
bloqueada. Ensayar fin de canción, anuncios, recarga, cierre de extensión y fallo
del worker. Un stream vacío o una captura interrumpida invalida el trabajo; no
produce un supuesto perfil mono. No confundir música mono real con fallo de captura.

Medir PCM antes de cualquier recodificación: no pasar por MediaRecorder/Opus para
después medir. Procesamiento por bloques en memoria; guardar estadísticas,
procedencia y parámetros, no una biblioteca de grabaciones de plataformas.
Un servicio Node, si es necesario, solo en localhost, con acceso autenticado y
orígenes permitidos. No subir audio ni credenciales a servicios externos.

## 6. Qué medir y cómo evitar conclusiones falsas

Mantener Ps/Pm con fórmula explícita. Medir además balance L/R, correlación con
signo, coherencia y distribución espectral/espacial; ninguna sustituye una medida
de calidad percibida. Ampliar la resolución de análisis (por ejemplo tercios de
octava), con agregaciones a regiones prácticas y cortes de 120/200/250/300 Hz.
No prejuzgar que el procesador final tendrá tantas bandas como el analizador.

- Archivos completos, no solo los 30 s más fuertes. Separar regiones estables,
  introducción, desarrollo, breaks, finales, silencios y colas de reverb.
- Detectar cambios de energía/espectro/densidad; revisar límites y etiquetas.
  No afirmar reconocimiento semántico infalible de «estribillo».
- Ventanas cortas para extremos y de segundos para contexto; conservar ambas.
  Medianas, percentiles y distribución entre secciones, no solo media de la canción.
- Separar ausencia de energía, Side realmente cero, M casi nulo, un canal ausente
  y antífase. No rellenar esos estados con valores finitos arbitrarios.
- No excluir partes suaves como si fueran todas silencio ni dar a una canción
  larga más peso por producir más ventanas. Documentar sensibilidad de los gates.
- Registrar exclusiones, ediciones diferentes, pérdidas de captura y anuncios.
  No eliminar masters anchos/estrechos solo porque contradigan la hipótesis.

## 7. Derivar perfiles después de medir

Comparar primero un perfil general con modelos por familia, subestilo y tipo de
producción. Evaluación fuera de muestra por artista/producción y, donde sea
posible, por estudio/sello. Buscar agrupaciones estables, no seleccionar el número
de perfiles para coincidir con una lista comercial.

Separar perfiles solo si la diferencia persiste en nuevas producciones, supera la
incertidumbre de medida y mejora la referencia fuera de muestra. Unir perfiles
redundantes. Las subdivisiones no se justifican únicamente por un p-valor o por
tener distinto nombre de género. Revisar los híbridos y los casos fuera de cobertura.

Cada perfil tendrá centro y rango por frecuencia/sección, procedencia, tamaño
efectivo, intervalo de incertidumbre y versión. El objetivo del Leveler y el techo
del Guard se calibran por separado. La generación añade diferencias; el objetivo
no se convierte en un knob de simple amplificación Side.

Criterios iniciales, a fijar tras el piloto antes de la validación final:

- Intervalo bootstrap por producción del centro de referencia suficientemente
  estrecho: como candidato, semianchura <=1 dB en bandas con energía útil.
- Dos ampliaciones sucesivas del corpus cambian medianas menos de 0,5 dB en esas
  bandas. Estabilidad no demuestra representatividad: auditar cobertura también.
- Diferencias entre rutas/codecs menores que las diferencias usadas para separar
  perfiles, o conjuntos separados y limitaciones declaradas.
- Validación reservada sin retocar perfiles para esos temas. Si se retocan,
  reservar nuevos temas. Mostrar también los casos donde el perfil no encaja.
- Patrones mono deliberados y subgrupos importantes no tratados como errores.

Estos límites son criterios de ingeniería propuestos, no normas publicadas ni
pruebas ejecutadas. Si no se cumplen, ampliar/revisar/combinar perfiles; no declarar
«todos los géneros cubiertos» por tener un desplegable largo.

## 8. Experimentos de generación y evaluación musical

Solo sobre fuentes con permiso para ese procesamiento, en prototipos aislados:
comparar 200/250/300 Hz, generación de graves On/Off, movimiento/bandas/armónicos,
y separar el efecto de Generation, Side Leveler y Guard. El dry grave no debe
alterarse por la casilla; el límite espectral se aplica al delta final.

Evaluar balance, DC, mono, transitorios, cambios de timbre y envolventes, más
comparaciones ciegas a sonoridad igualada. ABX ayuda a detectar diferencias, no
demuestra preferencia. [ITU-R BS.1116](https://www.itu.int/rec/R-REC-BS.1116)
orienta pruebas de pequeñas degradaciones, no define el perfil estéreo perfecto.

Separar estrictamente medición automática y evaluación auditiva real. Reproducir
en una pestaña no significa que el asistente haya oído el audio; solo registrar
escucha si existe acceso efectivo al contenido, indicando quién/sistema, pasaje y
condiciones. No usar los altavoces del usuario ni afirmar un panel humano que no
existe. Si falta validación perceptiva, los parámetros pueden estar medidos pero
no presentarse como sweet spots auditivamente demostrados.

## 9. Entrega y orden de trabajo

1. Matriz de estudios y datos reutilizables; decisión de qué falta medir.
2. Inventario de referencias/ediciones/permisos y cobertura; piloto de 12–20.
3. Si procede, capturador silencioso validado y medidor ampliado independiente.
4. Exploración estratificada, ampliación selectiva y validación reservada.
5. Tabla final de perfiles justificados, rangos y excepciones; decisión sobre
   graves y control Side escalar/espectral, con evidencias y límites.
6. Entonces, actualizar el diseño final e implementar Stereo Image en QuickMaster.

Medir por reproducción no acelera el tiempo musical: 600 pistas de 4 minutos son
unas 40 horas de reproducción, sin contar incidencias/repeticiones. Archivos
autorizados permiten análisis offline mucho más rápido. Es una estimación de
escala, no una promesa de terminar la investigación en una sesión ni un permiso
para reproducir en segundo plano sin el mecanismo de ejecución correspondiente.

Estado actual: revisión documental y piloto offline medido, con código, pruebas,
procedencia y resultados registrados en `stereo-pilot-progress-20260928.md`.
No se han reproducido canciones, instalado extensiones, modificado audio de Windows
ni iniciado capturas. No se compra ni se contacta a terceros como consecuencia
implícita de este plan. No hay todavía corpus suficiente para calibrar perfiles.

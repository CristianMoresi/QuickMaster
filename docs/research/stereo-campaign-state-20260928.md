# Stereo Image — campaña completada (28-09-2026)

## Estado final autoritativo

**50/50 referencias aceptadas y verificadas; diez familias, cinco canciones por
familia. Investigación de medición terminada.** Datos portátiles y sin audio en
`docs/research/stereo-study-20260928/`: `study.json`, `verification.json`, README
y 50 archivos `records/*.json.gz`. Suman 14 920,01 s de duración de referencias
(unas 4 h 9 min); se documenta el margen final y el relleno de captura.

Se completó también la petición adicional de datos para una EQ futura: espectros
normalizados L/R/M/S, distribución tonal, percentiles y variación temporal,
fiabilidad por banda, rango RMS y crest. No hubo que repetir las canciones para
obtener esos derivados. No son LUFS/LRA/true peak ni curvas perceptivas ideales.

Validación final: 58 tests Python + 15 Node; fixtures reales de reloj, espectro y
aislamiento; reconstrucción de las 50 métricas desde sus archivos comprimidos,
comprobación de hashes y agregación de cinco canciones con igual peso por familia.
48 capturas tienen reloj por segundo; las dos primeras Pop se verifican con sus
eventos de progreso cada 30 s. Hay evidencia de ítem seleccionado de catálogo en
49 referencias y de identidad de la página capturada en las 50; D'Angelo se
verifica en su página oficial UMG reproducida. No inventar un catálogo ausente.

SHA256 de `study.json`:
`8de32115b7fa145329734477fac06c00187b99d4c497d2dfee2374f8cf114add`.
Los intentos de `corpus-v2` con reloj incorrecto están excluidos, no promediados.
La selección contiene solo 50 canciones; no quedan treinta familias activas.

Plan actualizado en `docs/stereo-image-development-plan.md`: H0 de medición
cerrado; H1–H5 del módulo siguen pendientes. No se ha implementado Stereo Image
ni una EQ espectral nueva. No se modificó el portable: JAR instalado conserva
`cbad134db59f08138722b07550774e30869f580dbcad25ede7f91d0347aa78a2`.
No commit/push/release. No iniciar más reproducciones: la cobertura está completa.
Los checkpoints siguientes son historia del proceso, no trabajo activo.

## Alcance vigente

El usuario corrigió expresamente el borrador de 30 familias: **diez familias
generales, cinco canciones conocidas por familia, 50 en total**. Electrónica,
pop, rock, metal, hip hop, R&B/soul/funk, acústico/folk/country, jazz/blues,
orquestal/sinfónico/cinemático y latina. Los subestilos son etiquetas, no treinta
perfiles. Fuente activa: `stereo-lab/repertoire.mjs`; selección generada en
`selected-corpus/selection.md` y `.json`.

El usuario pidió continuar con medición durante reproducción pública de YouTube
y Spotify, sin descargar música. No volver a pedirle una biblioteca local. Se está
usando la reproducción pública normal de YouTube; no Spotify, cuentas, DRM,
cookies del usuario ni extracción de URLs de medios. No se hace una afirmación
de legalidad universal. La distinción entre términos de plataforma y legalidad
fue comunicada. Cualquier login obligatorio, CAPTCHA, contenido inaccesible o
control de acceso se respeta; no se elude. El programa nunca bloquea solicitudes
publicitarias; espera o usa el botón ordinario de salto cuando está disponible.

Sin extensiones de Chrome, sin drivers, sin sonido audible ni cambios al audio
global de Windows. Node/Electron aislado captura SOLO su propio `webContents`.
La fuente se silencia antes de cargar páginas, receptor silenciado también,
AudioContext con sink `none`, worklet sin PCM de salida. Permisos de micrófono y
captura de otras ventanas denegados. Ventanas ocultas, sin robar foco. Audio PCM
transitorio por pipe; solo métricas y procedencia se guardan.

## Trabajo realizado

- Electron 44.4.5 instalado exclusivamente bajo `dist/stereo-research-20260928/electron-runtime`.
- Fixture sintético capturado: dos canales, relación esperada -6,020599913 dB;
  error inicial 0,000465 dB. Mute independiente sobrevivió al cierre de captura.
  No es evidencia por géneros.
- 50 búsquedas de metadatos iTunes terminadas, 31 coincidencias textuales exactas
  de artista/título/álbum. Metadatos NO equivalen a masters certificados. No se
  descargaron ni analizaron previews.
- 50 búsquedas públicas de YouTube terminadas (`public-catalog-v1`), 18 consultas
  de desambiguación (`public-catalog-v2`). Se está consultando el catálogo público
  de YouTube Music (`public-catalog-music-v1`) para identificar audio de álbum.
  Las filas visibles distinguen Canción/Vídeo, artista y álbum. No elegir
  automáticamente el primer enlace: p. ej. Norah devuelve primero una demo y
  Queen un directo. Los enlaces de Canción pueden abrirse en el reproductor
  público de YouTube; si no están accesibles, registrar el rechazo, no eludirlo.
- Orquestador local Node para archivos, expansión espectral y pruebas: 32 tests
  Python + 9 Node aprobados a este punto. Nulos mono/antífase corrigieron un
  residuo de cancelación numérica en la expansión espectral, sin tocar el v4 del
  medidor original ni su auditoría anterior.
- Capturador streaming `watch-capture.cjs`: identidad/duración, 48 kHz dos canales,
  secuencia IPC, presión de memoria limitada, continuidad, anuncios, velocidad 1x,
  menú de volumen estable registrado. En la referencia Weeknd no se ofrece el
  ajuste de volumen estable; ganancia del reproductor constante observada.

## Estado honesto de mediciones comerciales

**Lote de 50 en curso; no confundir cobertura parcial con perfiles terminados.**
Fuentes resueltas: `stereo-lab/capture-jobs.mjs`, 50 trabajos en
`dist/stereo-research-20260928/jobs-v1`. Diez familias de cinco; no 30 perfiles.

Mediciones reutilizables de Pop: `watch-pop-1-v5` (Blinding Lights) y
`corpus-v1/pop-4` (Into You). Se preservan fuente, controles de reproducción,
duración y métricas. La asamblea independiente comprueba estos datos de nuevo.

`corpus-v2` tiene cuatro intentos **rechazados**: el reloj de los contextos
silenciosos interactivos avanzaba aproximadamente a 0,65× en Windows. No se
cuentan ni se reutilizan sus valores. `clock-probe.json` compara cuatro rutas:
el contexto silencioso con `latencyHint: playback` mantiene el tiempo real.
`fixture-playback-v1/fixture-result.json`: 9,984 s medidos frente a 10,012 s de
reloj y error de relación M/S de 0,0000213 dB. Fuente y receptor ocultos y mudos,
sink `none`, salida del worklet a cero. No cambio del audio global de Windows.

Lote activo: `corpus-v3`, cuatro capturas en paralelo. Contiene 48 trabajos; los
dos éxitos Pop anteriores completan las 50 referencias sin repetirlos. Ya se
han obtenido candidatos nuevos con reloj correcto. `queue-status.json` es
progreso; la aceptación final la decide `capture_quality.py`, no el exit code.
El capturador aborta si hay deriva >0,75 s, no espera al final para descubrirla.

El final se pausa aproximadamente 250 ms antes del post-roll porque 20 ms no
protegía todos los reproductores. Se registra la frontera y se exige cobertura
>99,5 %. Hay corto relleno silencioso de arranque/cola; no se afirma captura
sample-exact del PCM del máster. Anuncios intermedios invalidan el intento.
Publicidad inicial se espera o se usa únicamente el botón normal de salto.

**Ampliación solicitada por el usuario:** `tonal_features.py` deriva datos para
una futura EQ por estilo a partir de los mismos espectros, sin más reproducción:
distribución tonal L/R/M/S, potencia normalizada por ventana, medias temporales y
ponderadas por potencia, percentiles, regiones 0–120/120–300/300–2000/2k–10k/
10k–Nyquist, evolución a 15 s, cambio espectral y rango RMS/crest de muestras.
No confundir RMS con LUFS/LRA ni pico de muestra con true peak. No desarrollar
todavía ese procesador. Se documenta que la entrega con pérdidas puede afectar
el extremo agudo: no afirmar que su espectro sea el del WAV original.

Pruebas actuales: 56 Python y 15 Node. `assemble_stereo_study.py` reúne solo
mediciones aceptadas, pesa cada canción igual y conserva los poderes/timelines
comprimidos en JSON (nunca PCM). `study-snapshot-01` muestra 3/50 en aquel
instante, no es el resultado final. Mono/silencio/nulos no se convierten en ceros
ficticios; cinco canciones no prueban un objetivo perceptivo universal.

### Checkpoint de las 23:00, campaña todavía en marcha

- `study-snapshot-05`: 20/50 aceptadas de forma independiente; Pop y Rock 5/5.
- La selección de código ya contiene SOLO las diez familias y 50 canciones. Se
  retiró la reserva editorial no utilizada de 30 familias; URLs, IDs y hashes de
  las 50 selecciones se comprobaron idénticos antes/después.
- `fixture-spectrum-v1`: ruta real Electron -> worklet -> Python con tonos Mid
  a 1 kHz y Side a 1,3 kHz. Error global S/M 0,00000668 dB; potencia Mid ~0,02 y
  Side ~0,005, >99,999999 % de energía en las bandas esperadas. Sin salida audible.
- Cola principal: `corpus-v3/queue-status.json`, cuatro procesos. Dos Pop se
  anticiparon manualmente en el mismo directorio y ya terminaron (`pop-2/3`).
- Cola auxiliar 1 acabada: `reggaeton-1/3`, `rnb-1/2`, cuatro candidatos válidos.
  Estado: `queue-status-extra1.json`. La principal los reutilizará sin repetir.
- Cola auxiliar 2 activa: `rnb-5`, `soul-funk-1/3`, `trance-5`, `trap-drill-1/2`,
  cuatro procesos como máximo; `queue-status-extra2.json`. Son trabajos del final
  de la cola principal, lejos de su posición actual, para evitar solapamiento.
  Vigilar ambas colas; no iniciar dos capturas del mismo ID a la vez.
- La asamblea compara cada canción con su URL seleccionada y controles; guarda
  rangos tonales, fiabilidad por banda, sensibilidad al gate temporal y archivos
  comprimidos de poderes/tiempos. Nunca confundir estos archivos JSON con audio.

## Lo siguiente (no detenerse después de otro piloto)

Checkpoint 23:18: **41/50 aceptadas en `study-snapshot-09`**, con más capturas
terminadas desde esa instantánea. Todas las 50 referencias ya han sido iniciadas
o completadas. Las colas auxiliares 1–4 ya terminaron; la 5 (`metal-1`) está
acabando. La cola principal recibió su archivo `STOP` para **cerrar solo nuevos
despachos**, evitando repetir la Metallica que terminaba otra cola. Esto no pausa
la investigación: sus capturas activas continúan y las últimas tres referencias
se iniciaron individualmente en `corpus-v3`: `latin-pop-3`, `metal-2`,
`latin-pop-1`. El agregado `study.json` es el contador de cobertura autoritativo,
no el número de trabajos terminados de una única cola.

Pruebas actualizadas: **58 Python + 15 Node**. También pasó
`fixture-isolation-v1`: una segunda ventana del propio medidor reproduce otros
tonos en silencio y no contamina la fuente seleccionada. Se agregó auditoría del
reloj a partir de eventos de progreso cada 30 s para las dos capturas iniciales
Pop; no se inventaron datos de reloj por segundo que no se registraron.

1. Verificar duración, datos y flags tras el cambio del worklet; probar nulos,
   secuencia, huecos y cronología con oráculos. No convertir un fallo de captura
   en un supuesto master mono.
2. Terminar la resolución de las 50 fuentes. Verificar artista/versión/álbum y
   duración. Conservar fuente, edición/remaster y fecha de observación. Si hace
   falta sustituir una referencia inaccesible, documentarlo ANTES de medirla.
3. Ejecutar lote de 50 canciones, con concurrencia moderada, reanudable, sin
   repetir éxitos. Capturas fallidas/cuarentena no cuentan para alcanzar cinco.
4. Medir M/S por potencia, balance y correlación L/R, espectro Side por bandas,
   fracción Side bajo 120/200/250/300 Hz, percentiles y evolución temporal.
   `stereo_features.py` NO calcula aún coherencia cuadrática ni etiquetas
   semánticas de secciones; no afirmar lo contrario.
5. Comparar por canción, no por miles de ventanas como observaciones
   independientes. Publicar resultados de la entrega streaming, no decir que se
   tuvo acceso al WAV original ni que cinco temas demuestran un óptimo universal.
6. Actualizar el plan de Stereo Image con conclusiones y límites; no implementar
   DSP de la app hasta cerrar esta investigación.

## Comandos

Desde `E:\Code\Projects\JA-DAW\QuickMaster-Integration`:

```text
python -B -m unittest discover -s docs/research -p "test_stereo*.py" -q
node --test docs/research/stereo-lab/test-lab.mjs
node docs/research/stereo-lab/launch.mjs
node docs/research/stereo-lab/launch.mjs --search --music
node docs/research/stereo-lab/launch.mjs --watch JOB.json OUTPUT_DIR
node docs/research/stereo-lab/catalog.mjs export docs/research/selected-corpus dist/stereo-research-20260928/catalog-v1
```

No cambios nuevos a QuickMaster ni a su portable en esta campaña de investigación.
No commit, push o release. Se preserva el árbol con trabajo previo existente.

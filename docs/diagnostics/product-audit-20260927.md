# Auditoría integral de QuickMaster

Estado: **auditoría técnica completada dentro del alcance verificado**:
A01–A16 entregados; aceptación funcional y rendimiento instalados aprobados.
Encargo posterior a 1.3.1,
27-09-2026. Base de código: `0981c71`; instalado auditado `cb874287…`.
No se utilizan skills de orquestación. No se publicará otra release como efecto
implícito de esta auditoría. Se conservan el audio, los respaldos y el checkout
principal con cambios previos del usuario.

## Criterio de cierre

Revisión explícita de todas las áreas siguientes, reproducción de cada defecto
confirmado con un test que falle antes de corregirlo, resolución de los defectos
críticos/altos encontrados y registro de límites. Ejecutar suites completas,
corpus autorizado, pruebas adversariales y aceptación con JAR/EXE reales. Medir
rendimiento y memoria en condiciones reproducibles, sin sacrificar corrección.
Generar portable, copiar a Program Files, comprobar archivos y arranque limpio.
No confundir estos criterios con una garantía imposible de ausencia absoluta
de fallos o de reconocimiento perfecto de intención musical.

## Cobertura y orden

| Área | Revisión / oráculo | Estado |
|---|---|---|
| Entrada/salida y datos | WAV entero/float, MP3, precisión, metadatos, archivos truncados, fallos de escritura, cancelación, colisiones | A01/A11 entregados; regresiones instaladas PASS |
| Cadena y exportación | Orden, bypass, latencia/colas, preview-export, oversampling, SRC, fades y trims | A02/A06/A08/A12/A15 entregados; matrices instaladas PASS |
| DSP dinámico | Leveler, Beat/Peak/Punch, limitadores, clipping: límites, enlace estéreo, particiones y transitorios | A10/A14 entregados; corpus y Beat instalados PASS |
| DSP tonal | EQ estática/dinámica, Auto EQ, fase, respuesta y cachés | A09/A13/A14 entregados; regresiones instaladas PASS |
| Medición/análisis | LUFS/true peak/LRA, espectro, tempo, mono/estéreo y valores no finitos | A04/A16 entregados; 94 lecturas oficiales PASS, alcance limitado al perfil indicado |
| Transporte | Seek, pause/stop, finales de pista, cambios durante playback, buffers y dispositivo indisponible | A03/A08/A12/A14 entregados; backend simulado PASS |
| UI y estado | Undo/redo, A/B, presets, lotes, carga/cierre, sliders y errores | A05/A07/A13/A16 entregados, FXML real PASS; accesibilidad no certificada |
| Rendimiento/memoria | Perfiles de carga, gestos, exportación y pistas largas; trabajos obsoletos y retención | 30 ediciones estables, 20 ajustes cronometrados y PCM frío idéntico: PASS |
| Distribución | Dependencias/runtime, configuración/logs, permisos, portable e identidad instalado-build | 201/201 archivos idénticos; EXE arranca limpio como usuario normal |

## Método

1. Inventario de implementación y cobertura existente, seguido de búsqueda de
   huecos y pruebas independientes, no solo repetir los tests favorables.
2. Priorizar integridad del audio y de los archivos, resultados deterministas,
   límites físicos y errores visibles; después latencia y coste de memoria.
3. Agrupar las correcciones coherentes, probarlas focalmente y completar la
   validación y entrega obligatorias antes de considerar un bloque terminado.
4. Mantener por hallazgo: reproducción, impacto, cambio, tests, mediciones y
   resultado en el paquete real. No incluir audio privado ni autorización EBU.

## Hallazgos y registro cronológico

Las observaciones de candidatos y corridas interrumpidas siguientes son
históricas. El resultado de entrega actual está al final del documento; los
snapshots antiguos no sustituyen la aceptación del JAR final.

### Límites de la evidencia

- Las sondas de transporte capturan un dispositivo simulado. Se ha verificado el
  arranque del EXE instalado, pero no se ha hecho escucha humana ni certificación
  de drivers/DAC, dispositivos exclusivos, macOS o Linux.
- La salida JavaSound actual es PCM16 con TPDF; el DSP y el master offline no se
  reducen a 16 bits. La exportación WAV permite 24 bits y float32. No se presenta
  la escucha como un transporte bitperfect de 24/32 bits hasta el hardware.
- Los techos true peak auditados corresponden al PCM procesado/remuestreado.
  MP3 es con pérdida y su decodificación puede cambiar picos y añadir retardo o
  padding; estas pruebas no certifican techo postcódec ni reproducción gapless.
- El perfil oficial de sonoridad aplicable a archivo no es una certificación
  completa de EBU Mode, emisión en vivo o LRA. El reconocimiento de intención
  musical y del pulso métrico sigue siendo inferencia, no verdad garantizada.
- No se certifican todos los tamaños de archivo, sistemas de archivos o señales
  posibles. Se conservan las cotas y formatos concretos de cada oráculo; el
  reemplazo de archivo falla conservando el destino si falta soporte atómico.
- La timeline visual de EQ tiene resolución aproximada de bloque/~50 Hz, no
  sustituye una medición normativa. La accesibilidad completa y la calidad
  perceptiva requieren validación adicional fuera de esta evidencia mecánica.

La inspección inicial identifica rutas a comprobar, todavía no defectos cerrados:
exportación escribe directamente sobre el destino; metadata WAV modifica el
archivo en sitio; WAV float admite valores no finitos; trim necesita pruebas de
NaN/infinito/overflow. Se reproducirán sobre fixtures generadas, nunca sobre
archivos originales del usuario.

### A01 — integridad de importación/exportación (entregado)

- Siete casos fallaron contra el código previo: NaN/overflow en trim, WAV float
  no finito, WAV truncado, exportaciones inválidas, cancelación ignorada,
  extremo negativo de PCM32 y padding RIFF de data impar. Reproducción:
  `dist/product-audit-io-red-20260927.log`.
- MP3 reprodujo tres fallos adicionales de validación/cancelación. Metadata
  reprodujo duplicación de ID3, autocopia que duplicaba etiquetas, tamaño ID3
  inválido aceptado y sobrescritura del nombre fijo `.tagtmp` de otro archivo.
- Implementado: staging único en el mismo directorio, reemplazo atómico sin
  fallback destructivo, validación previa, cancelación entre bloques, encoding
  WAV/MP3 por bloques, metadata por rangos acotados y commit posterior a etiquetas.
  Los destinos que no soporten reemplazo atómico deben fallar conservando el
  archivo anterior. No se afirma durabilidad frente a cualquier fallo físico.
- Lotes: preflight de nombres y de colisiones con todos los originales;
  confirmación explícita antes de reemplazar outputs existentes. Exportación
  individual captura la identidad de fuente antes de lanzar el worker.
- Pruebas focales aprobadas, incluida matriz WAV de 40 combinaciones y dither
  de 16/24 bits sin sesgo ni correlación estéreo detectable con el umbral fijado.
  Primer snapshot compilado: suite completa 745 tests, 0 fallos/errores/skips,
  106 variantes musicales y 94 lecturas oficiales PASSED, 27:55 min.
  JAR `cb8f1df694b076b91d31767b30fe88219aa1885f1926c92449118c45968a14e0`,
  conservado con su evidencia en `dist/product-audit-io-compiled-snapshot`.
  Ese snapshot contiene SOLO A01; las correcciones siguientes requieren otra
  compilación completa. No está desplegado; el instalado sigue siendo ec80915f….
- Referencias: [RIFF de Microsoft](https://learn.microsoft.com/en-us/windows/win32/xaudio2/resource-interchange-file-format--riff-),
  [ID3v2.4](https://id3.org/id3v2.4.0-structure),
  [contrato de Files.move](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)).

### A02 — techo true peak con oversampling (resuelto y entregado)

Reproducido sobre los JAR instalados de 1.3.1 con
`tools/diagnostics/OversamplingCeilingAudit.java`: 270 combinaciones sintéticas de
3 tasas, mono/estéreo, factores 1/2/4/8/16, tono/ruido/impulsos y clipping.
100 superan el techo de −1 dBTP por más de 0,005 dB; peor exceso 2,742802637 dB.
Log: `dist/product-audit-oversampling-ceiling-baseline-20260927.log`.
El render oversampled utiliza el normalizador analizado a tasa base antes de
las no linealidades reales y de decimar. Además, `syncLiveAnalysis` mide siempre
el render base: revisar equivalencia entre audio de escucha, medidores y export.
No cambiar únicamente el indicador ni relajar el test.

Corrección candidata: normalizar después de decimar, sin clipping oculto en el
escalar; reusar el análisis base para el render real de medición/preview; solicitar
análisis cuando cambia oversampling. La prueba vuelve a ejecutar las 270 variantes:
0 violaciones, exceso máximo 0,000000497 dB (redondeo float). Se añade oráculo de
coeficientes independientes en JUnit. Aún pendiente suite completa/instalado.

### A03 — transporte y propiedad del worker (resuelto y entregado)

`stopInternal()` hace join(500) mientras los métodos públicos conservan el mismo
monitor que requiere el worker al avanzar posición; después elimina su referencia
aunque siga vivo. Investigar pausa/stop/seek/carga y callbacks antiguos, aperturas
fallidas y cierre del dispositivo. `tools/diagnostics/PlaybackStopAudit.java`,
sin abrir hardware, midió 501,935 ms bloqueados en FX y dejó el worker vivo
sin referencia. Log `dist/product-audit-playback-stop-baseline-20260927.log`.

Candidato reestructurado: worker serial, sesión propietaria de fuente/dispositivo,
cancelación sin join en FX, cierre fuera de FX, callbacks generacionales y tratamiento
de escritura parcial/formatos indisponibles/fallos DSP. Nueve pruebas con backend
simulado, sin hardware, pasan. La cadena publica listas inmutables: una sustitución
durante un bloque antes producía IndexOutOfBoundsException, ahora el bloque termina
con su snapshot. Falta aceptación del paquete y revisión del seek/loop con DSP con estado.

### A04 — espectro y medición mono (resuelto y entregado)

`tools/diagnostics/SpectrumAudit.java` contra el JAR instalado: excepciones por
índices fuera de rango tanto en LTAS como en LiveSpectrum a 8/16/22,05/32 kHz.
Invertir la polaridad del canal derecho de un tono reduce erróneamente el
espectro 134,99 dB al promediar canales antes de FFT. La medición mono informa
midPower=0 con un tono de potencia 0,125. Once fallos en total; log
`dist/product-audit-spectrum-baseline-20260927.log`. Revisar además concurrencia
del ring, asignaciones por tick y cobertura de los bordes de la pista.

Candidato: espectro por energía de canales, límites Nyquist, ventanas que incluyen
bordes, mono M/S correcto, scratch reutilizado y copia coherente del ring fuera de
FFT. Prueba diagnóstica 0/11 fallos; siete tests JUnit adicionales aprobados.

### A05 — presets no transaccionales (resuelto y entregado)

`tools/diagnostics/PresetAudit.java` carga el FXML real contra el JAR instalado,
con APPDATA aislado y sin reproducción. Cinco variantes inválidas cambian
parcialmente la configuración; null en una banda deja además el editor EQ
bloqueado en updatingEqEditor=true. NaN, versión futura y una clave de orden
desconocida no se rechazan. Log `dist/product-audit-preset-baseline-20260927.log`.
Validar todo antes de tocar controles, mantener compatible el esquema válido y
comprobar aplicación/rechazo/undo mediante la misma ruta de UI.

Validación pura antes de modificar controles, comprobación de versión, enums,
colecciones, capacidad, rangos y finitud; límite 1 MiB al leer presets. Los cinco
casos ahora se rechazan con estado completo intacto. Cinco tests unitarios cubren
también todos los campos numéricos. Pendiente paquete/aceptación final.

### A06 — posición de envolventes tras latencia (entregado)

El cursor de los procesadores posteriores no descontaba la latencia acumulada.
Delay analítico de 137 frames + fades: error live/offline de 0,07135418 en amplitud;
tras corregir el reloj por etapa, 0. JUnit incluye dos retardos encadenados y cuatro
tamaños de bloque. `PipelineAlignmentAudit.java` conserva la reproducción.

### A07 — wiring de limitadores y undo (entregado)

`ControlWiringAudit.java`: los controles multiband/broadband Push y Limit enable
no cambiaban la generación de análisis (0→0); un solo gesto Leveling no se podía
deshacer. Ahora los cuatro casos pasan (generación 0→1 y restauración completa
del preset). Se elimina el executor de requests que ya no hacía DSP y no se cerraba;
el worker común reconstruye/adopta toda la cadena. Undo conserva el estado ANTERIOR
al primer cambio, incluye el gesto pendiente al pulsar undo y registra reordenaciones.

### A08 — bloques parciales en oversampling de escucha (entregado)

El oversampler devuelve scratch del tamaño máximo aunque se pida un bloque final
más pequeño. El reproductor procesaba también la cola obsoleta, corrompiendo estado
antes del flush. Una comparación dispositivo simulado/export a 2x falló con error
0,005161927. Ahora solo se procesan las muestras válidas. Preview/export con clipping,
fades y normalización pasa a factores 1/2/4/8/16, incluidos 8193 frames y cola, dentro
de 0,00006 de amplitud (conversión de escucha PCM16 con dither).

### Medición de asignaciones A01 (no equivale a benchmark de toda la app)

`IoAllocationAudit.java`, PCM generado estéreo de 60 s / 48 kHz; bytes asignados
en el hilo durante save, excluyendo creación de PCM. Baseline instalado frente
a clases candidatas: PCM16 11.543.808 → 34.760; PCM24 17.300.848 → 39.600;
float32 23.062.336 → 47.592. Se elimina el buffer codificado de pista completa.
Tiempos observados 25–96 ms; una pasada con la suite concurrente, no inferir
una aceleración general ni un percentil de latencia. Logs `dist/product-audit-io-allocation-*-20260927.log`.

### A09 — Auto EQ / STFT (entregado)

Ocho fallos reproducidos contra el JAR instalado: excepciones a 8/16/22,05/32 kHz
y cambios de corrección por inversión de polaridad a 44,1–192 kHz (error hasta
0,209 de amplitud). `AutoEqAudit.java` pasa ahora los ocho casos con error cero.
Análisis por energía enlazada, bandas válidas bajo Nyquist, parámetros/PCM finitos,
Amount=0 exacto, cancelación, borrado de render al analizar vacío y publicación
atómica de PCM/mapa/formato. STFT usa denominador WOLA periódico (hop samples),
no un double por muestra de pista; se elimina además el mixdown mono completo.
Trece pruebas de Auto EQ/STFT, incluidas cinco nuevas, pasan en clases aisladas.

### A10 — cola y reloj multibanda (entregado)

El máximo del filtro se medía sobre su salida causal truncada al tamaño de la
fuente. Un impulso final de 257 frames / 8 kHz daba pico LOW 0,000003179 en vez
de 0,023998123. El nuevo oráculo independiente extiende y alinea la convolución;
no basta comprobar que el indicador alcanza -3 dB, pues podía hacerlo incluso
usando un pico equivocado. Se vacía el retardo, mapas en reloj fuente y lectura
compensada por la latencia real de cada tasa. Catorce tests focales pasan; el
oráculo anterior de splitWhole se actualiza para incluir la cola alineada.

### A11 — E/S, presets y lote (entregado)

Decodificación PCM con scratch acotado y cancelación por bloque; WAV ya no retiene
un byte[] entero además de dos PCM. RIFF verifica frames incompletos antes de que
Java Sound los descarte. MP3 no publica formato/bitrate parcialmente al fallar.
Diecisiete pruebas focales de decodificación/formatos/MP3 aprobadas.
JSON se lee con límite real de 1 MiB y se escribe por reemplazo atómico. Los
configs inválidos o de versión futura se conservan sin sobrescribirlos; preferencias
solo en memoria en ese caso. Trece pruebas de JSON/config pasan. Los lotes capturan
una cadena independiente en FX, reutilizada en su worker con el análisis de cada
canción, sin consultar controles mientras exportan.

Metadata BWF conserva descripción/procedencia, pero el nuevo master arranca en
tiempo cero y no hereda mediciones de sonoridad del original: los cinco campos
se marcan no disponibles (0x7fff), según [EBU Tech 3285 §2.4](https://tech.ebu.ch/docs/tech/tech3285.pdf).
No se afirma conformidad completa BWF ni preservación de todos los chunks privados.
Siete pruebas de metadata pasan, incluida fuente intacta y destino recargable.

Las correcciones A09–A11 se compilan/prueban en `dist/product-audit-a09-classes`,
sin alterar `target/classes` de la suite A01–A08. Esta terminó a las 11:12:07,
con 772 tests, 0 fallos/errores/skips y 94 lecturas oficiales PASSED (29:17 min).
JAR `7f52536ce8eee6a35bda417873a9888c71d53f10150f6a7fada63a9d481edc82`,
archivado con su evidencia en `dist/product-audit-a01-a08-compiled-snapshot`.
No contiene A09–A12, que requieren otra suite/paquete completos antes de entrega.

### A12 — límites de etapa, seek y loop (entregado)

Comparación real del PCM entregado al dispositivo simulado frente al export:
EQ lineal + multibanda + fades + normalizador divergían hasta 0,00017047 en el
inicio. Streaming dejaba entrar pre-ringing fuera del intervalo fuente en la
etapa siguiente, mientras offline recortaba ese intervalo. Ahora ambos respetan
el mismo dominio temporal: pruebas mono/estéreo 1/2/4x pasan dentro de 0,00007,
sin relajar el umbral y con residual de cuantización/dither PCM16.

Seek y loop fallaban con filtros con estado: un seek de 7003 frames entregaba
4458 bytes extra de arranque; el primer frame de loop debía ser -0,8882 y era
prácticamente cero. Se reconstruye el historial en el worker, se compensa el
retardo y no se vacía la cola de dispositivo al cerrar cada loop. Doce pruebas
del reproductor pasan, incluidas continuidad export/seek/loop y stop no bloqueante.
El preroll exacto tiene coste proporcional a la posición buscada; falta medirlo
con pistas largas. No es una garantía de latencia constante ni de ausencia de
underruns con todo dispositivo/cadena. A/B y edición durante escucha siguen
en revisión antes del cierre. Se añade también compensación de reloj al pasar
live→render A/B y preroll al volver: las dos transiciones reproducen el export
sin saltar ni duplicar muestras. Catorce tests de transporte pasan. La prueba
focal combinada de todas las áreas salvo la matriz musical larga pasa 245 tests.

### A13 — creación EQ y presets del propio editor (entregado)

`EqControlAudit.java`, FXML real: doble clic crea una banda sin invalidar el
análisis (generación 0→0); la validación introducida en A05 rechazaba 6 dB/oct,
opción real del selector y la rueda. Se corrigen ambos y se recorren los extremos
de todos los knobs y todas las pendientes para verificar su representabilidad.
La tercera suite A01–A12 se interrumpió a las 11:16 antes de terminar, sin
desplegar su candidato. No se cuenta como aprobada ni como entrega.

### A14 — publicación coherente y escucha del render aprobado (entregado)

La prueba FXML `LiveEditPublicationAudit` captura el AudioPlayer mediante un
dispositivo simulado. Con normalizador -6 dBTP, el pico de muestra inicial era
-6,215206 dBFS; modificar +18 dB de EQ sin permitir aún el análisis lo llevaba
a 0 dBFS (clipping en dispositivo). Red contrastado contra el JAR archivado
A01–A08. La primera variante sin activar normalizador daba -20→-2,08 dBFS:
demostraba la mezcla de estados, pero no un incumplimiento de techo normalizado.

La escucha de la aplicación ahora consume el PCM completo aprobado por el worker,
el mismo usado en sus estadísticas, con identidad de fuente y generación. Los
controles pendientes no lo mutan. Cada actualización incorpora una transición
convexa de 20 ms en el mismo reloj fuente. Seek y loop sobre ese PCM no ejecutan
DSP ni reconstruyen historial, independientemente del factor de oversampling.
Se conserva la ruta streaming para otros consumidores/tests, no se elimina.

Los medidores usan los procesadores del snapshot audible. EQ dinámica conserva
una historia visual compacta (~50 Hz, resolución efectiva por bloque), capturada
durante el render y compartida con el cache tonal; no se vuelve a procesar audio
en FX. No se presenta esta historia visual como medición normativa de pico.
A/B reutiliza PCM y snapshot juntos, cancela generaciones previas y restaura
controles al cancelar, sin adoptar un render de otra fuente.

Dieciocho tests de transporte + un test de timeline EQ (1/2/4x) pasan. El oráculo
FXML normalizado ahora permanece en -6,215206 dBFS antes/durante la edición.
`SlotPublicationAudit` pasa A/B cacheado y rollback por cancelación sin hardware.
La suite A01–A13 iniciada 11:18 se interrumpió a las 11:28 para incluir este
defecto confirmado. No se considera aprobada ni entregada.

DSPark: 122 tests propios pasan; upstream sigue en
9330f1cd29164f6d33e7876bc422627a200ec919, sin revisión nueva desde el port auditado.
Decodificación de WAV de 60 s: asignación de hilo 69,17→46,14 MB (16-bit),
97,99→46,14 MB (24-bit), 92,24→46,17 MB (float32). No se afirma aceleración CPU
de E/S con esta medida concurrente y de una sola pasada.

### A15 — remuestreo de exportación y DSPark 0.2.1 (entregado)

La longitud fija de 128 taps de entrada de ULTRA no conservaba la transición
al reducir la tasa. El oráculo del método de exportación real fallaba nueve
casos: 192→44,1 kHz perdía 3,127017 dB a 20 kHz; 192→48 kHz dejaba alias de
28 kHz a -60,532860 dB. También 48→44,1 kHz caía 0,1365 dB a 20 kHz.
La suite A01–A14 de las 11:48 se interrumpió a las 11:57 para corregirlo;
no se declara aprobada.

DSPark 0.2.1 escala el soporte del filtro con la reducción; ULTRA emplea
256 taps por muestra objetivo, Kaiser beta 14,5 y corte 95% de Nyquist.
El diseño sigue la relación longitud/transición normalizada descrita en
[Kaiser de SciPy](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.kaiserord.html)
y el filtrado antes de decimar de
[resample_poly](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.resample_poly.html).
No se usa SciPy en ejecución. Lectura interleaved directa elimina los arrays
planarios de entrada/salida. Valida PCM, frames y desbordamiento de tamaño,
permite cancelación y conserva el conversor anterior si falla prepare.

Seis tests nuevos reproducen cinco fallos antes de la corrección; los 128 tests
del vendor pasan después. El oráculo de exportación espectral pasa 30 tonos
(error máximo de banda observado 0,000018 dB, alias probado < -155 dB).
El oráculo de 50 combinaciones 8–192 kHz mono/estéreo pasa: error de RMS máximo
0,001981851 dB y techo final -0,999999562 dBTP con kernel independiente.
Son medidas de esos tonos y formatos, no una garantía de toda frecuencia.

Pin explícito 0.2.1:
`4f8759e3334ce1970382076cfe2015c44dd7f1378f625eeff831e70915fd4382`.
42 clases del vendor son idénticas a 0.2.0. Solo cambia `Resampler` y la tabla
de líneas de su enum `Quality` (sus declaraciones/bytecode ejecutable son
idénticos por javap). FFT, true peak, DspMath y todas las dependencias del
Leveler permanecen byte a byte iguales. Se conservan los binarios y contratos
históricos 0.1/0.2.0, sin aprender permisos de bytes arbitrarios del candidato.
Se añade regresión también a la suite principal sobre el método real de UI.

La regresión principal detecta además un frame añadido por redondear primero
el cociente 48000/176400. Se calcula la extensión como ceil(frames*destino/origen),
con producto entero exacto para los formatos soportados. Las 36 combinaciones
de longitud se comparan con aritmética entera independiente; pasan 129 tests de
vendor. El hash anterior del candidato 0.2.1, `5c09c120…`, queda sustituido por
el de arriba antes de cualquier entrega. La suite interrumpida A01–A15 contiene
ese fallo de longitud y no es válida como aceptación.

Matriz adicional `ExportSrcBandAudit`: 135 casos de subida/bajada entre las seis
tasas de entrega, tonos con fase no nula y proyección cuadratura independiente.
Máximo error de ganancia 0,000086499 dB; residuo/distorsión medido < -105,3 dB;
rechazo probado a Nyquist+500 Hz < -145,3 dB. No es una certificación de cada
frecuencia continua ni incluye los bordes de zero-padding en esa métrica.

### A16 — estadísticas coherentes al comparar A/B (entregado)

El FXML real reproducía audio A (-1 dBTP) mientras el medidor conservaba B
(-7 dBTP). `SlotPublicationAudit` ahora activa realmente el normalizador y
espera también las estadísticas, no solo que haya PCM disponible. La prueba
falla antes del cambio y pasa después. Los resultados de medición se guardan
ligados al PCM de cada slot, se invalidan juntos y se restauran al alternar.
Si faltan, se miden en el worker sin repetir DSP ni retrasar la adopción de audio;
se conservan las guardas de generación/fuente y la cancelación.
La corrida A01–A15 se interrumpió a las 12:13 para incluir esta corrección.

### Historial de validación previa a la entrega final

Reanudación 20:32: el handle de la corrida A01–A16 desapareció y el inventario
de procesos confirma que no queda Maven/Surefire. El log termina sin resultado
final y no existe `target/quickmaster.jar`; no se considera aprobada. La imagen
instalada sigue siendo `ec80915f…`. Se repite la suite completa, sin cambios
adicionales de aplicación, en
`dist/product-audit-a01-a16-resumed-package-20260927.log`.

Actualización 12:16: nueva suite completa A01–A16 en
`dist/product-audit-a01-a16-full-package-20260927.log`. Previamente pasan 87 tests
focales (incluidas guardas del nuevo pin), FXML de A/B, edición durante playback,
presets, undo de controles, EQ y las tres matrices SRC. No modificar el target
durante esta corrida. Ninguna imagen auditada se ha desplegado todavía.

Barrido de memoria A14 con JAR auténtico (`58a0cb25…`) y Leveler activo PASSED:
30 ediciones, retención de ~1278,72→1278,74 MiB, sin OOM; salida final bitexacta
al controlador frío. Sus tiempos coinciden con otra suite y **no** son benchmark
aislado. La nueva aceptación comparará directamente el PCM publicado al player,
no un segundo render hecho desde procesadores adoptados.

Corpus A14 completo: By Now, Billie Jean y Wicked Game dan corrección positiva,
protección de regiones y 0% exactos. Quiet Gold original supera 0 dBTP y conserva
la abstención de seguridad PEAK_UNSAFE ya documentada antes de esta auditoría;
no se oculta como un caso positivo. Con headroom solo en memoria y defecto +4 dB,
la diferencia baja 4,383214→1,507755 LU. Los WAV originales conservan sus hashes.

Actualización 11:48: suite completa en
`dist/product-audit-a01-a14-qualified-package-20260927.log`. La corrida anterior
A01–A14 se interrumpió a las 11:45 tras detectar cinco rechazos del guard estático:
la nueva API escalar de metering faltaba en la tabla cerrada. Se declara un sucesor
explícito, preservando el JSON/hash históricos y añadiendo una whitelist de siete
llamadas de lectura; se prohíben escrituras, asignaciones, callbacks y detours.
Cinco mutantes del nuevo getter (publicación, meter, cursor, array y adopción)
son rechazados. Los 60 tests Active/Async del contrato actualizado pasan.

La sonda de carreras se ajusta para llamar `player.prepare` después de reemplazar
el array por reflexión, como hace realmente `afterAudioEdit`; sin eso la nueva
guardia rechazaba correctamente la fuente distinta y el harness esperaba una
publicación imposible. Conserva las mismas verificaciones de carga, última edición,
tempo manual, cancelación de play, recuperación y cierre; todas pasan.

Resultados suplementarios del candidato (no confundir con entrega instalada):

- `FullChainAdversarialAudit`: 120 combinaciones (8–96 kHz, mono/estéreo, 1–16x,
  dos órdenes, diez etapas activas más normalizador). PCM finito, fuente intacta,
  longitud idéntica y máximo -0,999999442 dBTP con kernel independiente.
- «By Now», JAR de aceptación auténtico `f0ae02a9…`: 8.255.793 muestras modificadas,
  mínimo -0,313163 dB en original; error +4 dB solo en memoria reducido 2,446704 LU;
  regiones protegidas y Leveling=0 exactos; fuente con hash inalterado.
- **Mediciones preliminares descartadas como aceptación del Leveler activo:**
  `a14-latency` y `a14-memory` usaban `target/classes`, sin la evidencia embebida
  y autenticada de un JAR. Leveler publicaba STANDARD_VALIDATION_FAILED. Sus
  2,78–3,17 s / ~1278,74 MiB no son benchmark de cadena completa válida, aunque
  el resto de módulos estuviera encendido. `InteractionLatencyProbe` ahora exige
  explícitamente conformance PASSED antes de registrar cualquier resultado.
  Se repetirán ambas pruebas en el paquete instalado; no se trasladan esas cifras
  a conclusiones de rendimiento o memoria del producto final.

Checklist histórico: suite completa, medición aislada de interacción/retención, aceptación de
corpus real Leveler/Beat, imagen portable/despliegue y repetición de sondas sobre
el JAR instalado. La captura de transporte es simulada; no sustituye escucha
humana ni certifica todos los drivers de audio. Ninguna evidencia demuestra
perfección absoluta ni certificación completa de EBU/ITU.

## Entrega final y aceptación instalada — 27-09-2026

- Suite limpia completa A01–A16 terminada a las 21:01:20: **798 tests en 120
  informes, cero fallos, errores u omisiones**, 106 variantes musicales y 94
  lecturas oficiales aprobadas. El JAR final se reabre y verifica su evidencia
  autenticada. [Log completo](product-audit-evidence/full-suite.txt).
- DSPark Java 0.2.1: **129 tests aprobados**.
  [Log de biblioteca](product-audit-evidence/dspark-suite.txt).
- JAR final e instalado:
  `cb874287c83acc4a3f8a6cce582cca3f19980d533c6b5b5b76d0fcab917c5564`.
  Única dependencia DSPark instalada, 0.2.1:
  `4f8759e3334ce1970382076cfe2015c44dd7f1378f625eeff831e70915fd4382`.
- Imagen Windows Java 25 en `dist/product-audit-final-20260927/QuickMaster`,
  copiada íntegra a `C:/Program Files/QuickMaster`, sin instalador.
  Despliegue **DEPLOYED_AND_VERIFIED** a las 21:03:12; 201/201 archivos
  idénticos por SHA-256. Respaldo recuperable:
  `C:/Program Files/QuickMaster-backup-20260927-210305`.
  [Recibo](product-audit-evidence/deployment.json),
  [identidad completa](product-audit-evidence/installed-file-identity.json).
- Arranque adicional del EXE como usuario normal a las 21:10:38–39: JavaFX,
  controlador y restricción nativa inicializados, sin ERROR/SEVERE. Solo se
  cerró el proceso propio de comprobación.
  [Log](product-audit-evidence/installed-startup.txt).
- **23 sondas funcionales instaladas aprobadas**, incluidas 248 regresiones
  JUnit cargando producción exclusivamente de Program Files, no target/classes.
  [Resumen](product-audit-evidence/functional-summary.json).
  La primera selección del harness incluía cuatro clases diseñadas para fuente
  sin autorización embebida o para construir su propio JAR: sus precondiciones
  no son aplicables al instalado autenticado. Se documentan y excluyen solo de
  esta repetición; sus 20 tests sí pasan en la suite completa, sin cambiar
  aserciones ni producto. El Leveler instalado se comprueba positivamente abajo.
- Leveler UI real: carga asíncrona de By Now, 8.255.793 muestras modificadas,
  PCM publicado bitexacto al procesado y **Leveling=0 con las 26.880.002 muestras
  crudas idénticas al original**. En el defecto regional conocido +4 dB,
  la diferencia baja 4,962374→2,515670 LU; protección y fuente intactas.
  Billie Jean y Wicked Game también corrigen el original y reducen el error
  regional conocido. Quiet Gold original mantiene PEAK_UNSAFE por su pico
  superior a cero: no se presenta como caso positivo. Con headroom solo en
  memoria y +4 dB, baja 4,383214→1,507755 LU. Los cuatro archivos conservan
  sus hashes. [UI](product-audit-evidence/functional-leveler-ui.txt),
  [By Now](product-audit-evidence/functional-by-now.txt),
  [Quiet Gold](product-audit-evidence/functional-quiet-gold.txt).
- Beat Comp sobre Quiet Gold: objetivo −1 dB, mínimo aplicado −1,000000226 dB
  (redondeo float), **cero muestras fuera de tolerancia entre 19.765.741**;
  error máximo de enlace 1,183e−7. Bloques 257 y 1024 dan el mismo SHA-256
  de salida `6b6bd0bed83231b69c09a0c2b52ecd4dc22e8957b5accf9f280d8ed5a40e0b5d`.
  Tempo 82,25 BPM/confianza 0,65; se mantiene advertencia de ambigüedad métrica,
  no se garantiza detección inequívoca de mitad/doble.
  [Log](product-audit-evidence/functional-beat-257.txt).
- DSP instalado: 120 combinaciones adversariales de cadena; 270 de techo OS
  sin violaciones; SRC de exportación con 30 casos espectrales, 135 de banda y
  50 de formatos/canales, todos aprobados. La cadena adversarial no sustituye
  las pruebas largas separadas de Leveler. Medidores A/B, presets, undo,
  creación EQ, carreras carga/cierre, OS, waveform y tempo pasan en FXML real.
  Capturas instaladas de Leveler, UI general, zoom y pan inspeccionadas: no hay
  solapamiento observado y el pan cambia la vista sin tocar transporte.

Los logs funcionales conservados están junto al resumen. Las capturas y perfiles
aislados permanecen en `dist/installed-audit-functional-20260927-210740-019b88ae`.
No se incluye audio privado/oficial ni documentos de autorización en Git.
No se modifica la release pública 1.3.1; esto es una nueva entrega local auditada.

### Rendimiento y memoria del instalado

By Now intacto, 280 s / 48 kHz estéreo, Temurin 25.0.4.7, heap del **harness**
limitado a 4 GB, perfil de usuario aislado. Cadena completa significa los módulos
activados con sus controles iniciales, cambiando Leveling mediante listeners
reales; no todos los ajustes posibles ni oversampling 16x. No había otras suites
o sondas DSP concurrentes. Los tiempos terminan al publicar audio o estadísticas,
no al llegar físicamente al DAC.

| Medida | Resultado |
|---|---:|
| Solo Leveler, mediana de tres ajustes sucesivos hasta audio | 1,712990 s |
| Solo Leveler, rango de esos tres ajustes | 1,679117–2,085675 s |
| Cadena completa, mediana de veinte ajustes hasta audio | 3,565904 s |
| Cadena completa, p95 observado / máximo hasta audio | 3,812585 / 3,863189 s |
| Cadena completa, mediana / p95 hasta estadísticas | 4,628517 / 4,855111 s |
| Memoria retenida, primer / último de treinta ajustes separados | 1278,485 / 1278,502 MiB |

Percentil por rango más próximo (posición 19/20), no estimación poblacional.
El primer ajuste de la sonda corta de cadena completa tardó 4,995498 s hasta
audio: no se oculta como si todas las respuestas fueran de 3,57 s. La carga
inicial de esa sonda tardó 14,511930 s hasta audio y 15,602131 s hasta estadísticas;
no se promete carga instantánea. Máximo un trabajo de salida concurrente en
las sondas; tras cada ráfaga comprobada, el **PCM publicado al reproductor**
coincide bit a bit con una referencia fría independiente, sin bypass del Leveler.

El barrido de memoria fuerza GC solo en el harness, nunca en producción ni
dentro de los intervalos usados para la tabla de tiempos. Rango retenido de los
treinta checkpoints: 1278,484–1278,502 MiB; sin OOM. No es RSS ni pico total de
RAM. El render aprobado y sus snapshots añaden aproximadamente 210 MiB frente
al resultado previo de cadena completa (~1068 MiB) para esta pista. Se acepta
ese coste explícito para conservar audio coherente, seek/loop sin volver a
procesar el prefijo y medidores del estado realmente audible. No se presenta
esta auditoría como reducción global de RAM o aceleración adicional de toda la
cadena: la mediana anterior comparable era 3,495691 s. Se conserva la mejora
grande frente al diagnóstico original de ajustes lentos, con corrección ahora
verificada también para publicación, colas, picos y cambios A/B.

[Resumen de rendimiento](product-audit-evidence/performance-summary.json),
[estadísticas calculadas](product-audit-evidence/performance-statistics.json),
[veinte ajustes](product-audit-evidence/performance-latency-full-chain-20.txt),
[treinta checkpoints](product-audit-evidence/performance-memory-30.txt).
Todas las sondas terminan con aceptación positiva del audio instalado. No
quedan pendientes las correcciones A01–A16 ni los pasos de entrega obligatoria.

### Conservación y límites de cierre

Fuentes y evidencia guardadas en el checkout de integración sobre `0981c71`,
sin alterar el checkout principal con cambios previos, sin nueva rama y sin
push/release. El binario corresponde al último build completo; después de ese
build solo cambiaron documentación y herramientas de aceptación. Los scripts,
tests y logs permiten repetir las verificaciones. Los límites de hardware,
escucha perceptiva, reconocimiento musical, formatos y conformidad normativa
declarados arriba siguen vigentes: **este cierre no afirma perfección absoluta**.

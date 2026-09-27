# Validación de la corrección general del Leveler

Fecha: 2026-09-27. Estado: corrección entregada y comprobada en Program Files; no es
autorización para publicar un release. El historial del fallo permanece en
`by-now-leveler-2026-09-25.md`.

## Cambios y alcance

- Identidad de análisis `QM-LEVELER-S002-ARRANGEMENT-V1`; los cachés anteriores
  no se reutilizan como si contuvieran las nuevas decisiones.
- Las mesetas no cuentan como pasos de un crescendo/decrescendo. Un salto único
  a silencio al final no protege falsamente un cuerpo musical anterior.
- Una ruta adicional compara distribuciones espectrales y tonales de cuerpos
  estables, sin requerir duración idéntica o una interpretación sincronizada.
  Mantiene todos los vetos y las referencias previamente válidas. Los grupos
  requieren compatibilidad entre todos sus miembros; no basta una cadena de
  coincidencias parciales. Referencia mediana con pesos iguales por sección.
- Fragmentos no medibles de cualquier duración junto al borde no eliminan
  la protección de la primera/última sección musical. Regresión PCM: outro
  repetido seguido de un frame, un segundo o cinco segundos silenciosos. Antes
  del arreglo recibía +1,653 dB; después tiene objetivo cero. Hay pruebas de
  preservación de cada combinación de bits, a 44,1/48/96 kHz.
- No cambian render, rampas, límites de ganancia, enlace de canales ni seguridad
  de pico. No se añade seguimiento rápido de sonoridad dentro de las secciones.
- La UI distingue nivelación efectiva, falta de comparación, tolerancia y
  limitación de seguridad; no presenta una curva plana como nivelación activa.

## Oráculo de música real

`tools/diagnostics/LevelerCorpusAcceptance.java` ejecuta el procesador del JAR
con su propia conformidad verificada. No utiliza clases sueltas para simular una
entrega válida. Requiere muestras realmente cambiadas en los casos positivos.

Además reserva margen en una copia en memoria, selecciona automáticamente un
cuerpo comparable y le añade +4 dB. Mide la reducción posterior de su diferencia
de sonoridad frente a los otros cuerpos. No se codifican nombres, tiempos ni
umbrales por canción. La selección de comparables depende del algoritmo; este
ensayo demuestra corrección de un error conocido, no una anotación musical
humana independiente. Las pruebas PCM con partes anotadas cubren ese otro nivel.

Se comprueba igualdad bit a bit de las regiones protegidas, identidad de salida
con Leveling al 0 %, límites de ganancia y hash inalterado de cada WAV. No se
escribe ni distribuye audio.

El candidato `c588bd8704a125907a2772478189af0cdf8cbde0c0d25918caca6f1eb23afb0f`
incluye la protección de silencio de cualquier duración. Los cuatro ensayos
terminan con código 0 y con todas las comprobaciones descritas aprobadas:

| Archivo | Muestras modificadas, original | Ganancia original, dB | Desnivel con error +4 dB, antes → después (LU) |
|---|---:|---:|---:|
| By Now | 8.255.793 / 26.880.002 | −0,313 a 0 | 4,962 → 2,516 |
| Billie Jean (80s Glam Metal) | 3.669.946 / 20.918.586 | −0,115 a +0,138 | 4,641 → 2,305 |
| Wicked Game (80s Synthwave) | 6.369.338 / 29.537.278 | −0,083 a +0,329 | 4,329 → 2,284 |
| Quiet Gold | 0 / 19.808.780 (`PEAK_UNSAFE`) | 0 | 4,383 → 1,508 |

La columna del error conocido usa margen previo añadido en memoria en todos
los archivos. No debe confundirse con la actuación sobre el original: Quiet
Gold original no recibe corrección. Wicked Game se añadió como contraste no
utilizado para ajustar umbrales. Los WAV conservan sus hashes originales.

Los logs con hash de fuente y JAR están en
`by-now-evidence/{by-now,billie-jean,wicked-game,quiet-gold}-final.txt`.
El hash de entrega y las pruebas instaladas se registran al final de este documento.

## Integración de interfaz

`tools/diagnostics/LevelerUiAcceptance.java` carga el FXML/CSS auténtico y usa
`loadAudioFile(File)`, la misma entrada asíncrona del botón y de drag-and-drop.
No inyecta PCM en `loadedFile` ni fuerza generaciones: espera a que la carga,
el análisis de pista y el análisis de salida terminen y se adopten normalmente.
No abre el selector nativo ni una salida de audio.

Sobre el candidato anterior: `actualAsyncFileLoad=true`, generación 2 adoptada,
diagnóstico `Leveling · comparable sections`, 8.255.793 muestras cambiadas en
la cadena viva de By Now y ningún otro procesador habilitado. Control al 0 %:
`Leveling off (0%)`. Captura revisada: nombre/datos del archivo, waveform,
controles y diagnóstico visibles sin solapamientos. El medidor instantáneo
marca 0 estando detenido; no se usa esa captura como evidencia de ganancia.

Las preferencias y el log de este ensayo usan un APPDATA aislado bajo `dist`.
La comprobación se repitió con el JAR de Program Files el 27 de septiembre:
generación 2, 8.255.793 muestras cambiadas, carga asíncrona real y control al 0 %
aprobados. Captura instalada revisada visualmente, SHA-256
`1ced316a6cda87d4130c0f2f4850c69309cecd2bd833571f10a5480e7c1f9058`.

## Salvaguardas y límites explícitos

- Quiet Gold original tiene pico de muestra +0,433 dBFS y pico verdadero
  +0,492 dBTP. Su entrada original conserva el estado `PEAK_UNSAFE`: el Leveler
  no reemplaza un limitador y no se ha eludido la protección. El ensayo con
  margen en memoria es un caso diferente y se identifica como tal.
- Los umbrales de comparación son decisiones de ingeniería, no una certificación
  de intención artística. Las mediciones y la UI automatizada no sustituyen una
  escucha humana ni permiten afirmar calidad subjetiva infalible.
- Los contratos estructurales de prueba se amplían de forma explícita para la
  clase sin estado, el nuevo motivo (añadido al final para conservar ordinales),
  la identidad del algoritmo y la protección de bordes. No se regeneran ni
  sustituyen las referencias históricas para aceptar arbitrariamente el código.
  Los mutantes de retención de PCM, creación de hilos y publicación siguen
  rechazándose. La ruta de seguridad de pico y la conformidad no se relajan.

## Ejecución de pruebas

- La ejecución completa desde `clean` ejecutó **696 pruebas** y aprobó las
  **106 variantes musicales**. Terminó con tres fallos de expectativa en
  `LevelerProcessorTest`; no se presenta esa invocación como verde.
- Dos esperaban `STRUCTURAL_READY` en planes sin corrección, donde el diagnóstico
  nuevo y correcto es `NO_COMPARABLE_SECTIONS`. Se conserva la exigencia de
  igualdad bit a bit del audio de esas intros.
- La tercera suponía una transición desde 0 dB a un recorte. La ruta nueva también
  nivela los versos: esa frontera pasa ahora de −1,75 a −1,000123 dB. A los 44,5 s,
  Speed mínimo da −1,237389 y máximo −1,123756 dB: el rápido está más cerca del
  destino, no más recortado. El oráculo corregido exige acercamiento al mismo
  plateau medido en audio, igualdad del destino entre velocidades y ataque
  negativo inicial más rápido. No se cambió el DSP para satisfacer el test.
- Se repitieron los **ocho tests del procesador** contra el mismo JAR con todas
  sus clases comparadas contra las compiladas: 8/8 aprobados. Los 97 informes
  vigentes cubren 696 casos, 0 fallos, 0 errores y 0 omisiones. Los otros 688
  casos corresponden a la ejecución completa; no se cuentan dos veces los ocho
  repetidos. Ningún código de aplicación cambió entre esas ejecuciones.
- Logs: `dist/leveler-verification-20260925/full-clean-package.log`,
  `speed-diagnostic.log` y `processor-current-oracle.log`. El paquete definitivo
  repite los ocho tests y la validación oficial de sonoridad.

## Paquete y entrega

- `package` terminó con código 0 tras repetir los ocho tests del procesador y
  generar/verificar las 94 evidencias oficiales. Conformidad de archivo `PASSED`.
- JAR raíz definitivo:
  `1cc8d872b1436f9554473429c5a77644cc8e1184662db63cb8d3fa980ec96524`.
  Sus 166 clases `com.quickmaster` son byte-idénticas a las del candidato C588…
  probado en la suite, los cuatro audios y el ensayo de carga/UI.
- Copiado explícitamente el JAR raíz a `target/app`; imagen nueva generada en
  `dist/leveler-fixed-20260925/QuickMaster` con Temurin 25.0.4.7. Mismo hash,
  201 archivos y resultado `IMAGE_VALIDATED`. No es un instalador.
- La elevación fue cancelada el 25 a las 10:42. Tras autorización del usuario,
  la copia mediante UAC normal terminó el 27 a las 02:40:55:
  `DEPLOYED_AND_VERIFIED`, 201 archivos comprobados y hash instalado 1CC8….
  Respaldo: `C:\Program Files\QuickMaster-backup-20260927-024046`, JAR F1207….
  Arranque elevado y arranque adicional como usuario normal a las 02:41:32–33:
  JavaFX, controlador y restricción nativa inicializados, sin errores de aplicación.
  Solo se cerraron los procesos propios de verificación.
- El ensayo UI se repitió contra la imagen portable final 1CC8…: carga asíncrona
  real aprobada, generación 2, 8.255.793 muestras cambiadas y control al 0 %
  correcto. Después se repitió contra Program Files, con el mismo resultado.
- El ensayo de corpus contra Program Files termina con código 0: By Now original
  cambia 8.255.793 muestras, ganancia mínima −0,313163 dB. Desnivel artificial
  +4 dB: 4,962374 → 2,515670 LU; protección exacta, 0 % idéntico y WAV intacto.
  UI de tempo, zoom y desplazamiento de waveform sin cambiar reproducción pasan
  también sobre los JAR instalados. Los harness de JavaFX emiten avisos conocidos
  de módulos/acceso nativo y Unsafe; no fallos de aceptación ni errores de arranque.
- Evidencias instaladas: `by-now-evidence/deployment-20260927.json`,
  `by-now-installed-fixed-20260927.txt`, `ui-installed-20260927.txt` y
  `waveform-installed-20260927.txt`. No cambió código de aplicación entre el
  paquete validado y esta entrega; no se recompiló durante la copia.
- v1.3.1 y su tag siguen retirados. No se ha creado otro release ni enviado los
  cambios nuevos a GitHub. Solo existe `main`; el checkout original se conserva.

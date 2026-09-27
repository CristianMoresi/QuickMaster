# P0: planificación, bypass y memoria

Entregado localmente el 27 de septiembre de 2026. No es un release ni el cierre
de la optimización global. Fuentes en QuickMaster-Integration, sin commit/push.

## Paquete y pruebas

- `clean package`: 705 pruebas en 99 informes, cero fallos, errores u omisiones;
  incluye 106 variantes musicales. Ejecución completa verde de 30:30 minutos.
- JAR final reabierto con evidencia oficial PASSED, 94 mediciones del perfil
  QM-OFFICIAL-LOUDNESS-FILE-V1; no certifica EBU Mode completo, live o LRA.
- SHA-256 del paquete e instalado:
  `b01ed4faf56ac0a275b59a560bef6572378ae29306195b9ff16d5cf8c1fdd5c3`.
- La primera ejecución falló por la frontera AudioProcessor y se interrumpió;
  no se cuenta como aprobada. Revisión explícita en
  [auditoría de la frontera](audio-processor-boundary-p0.md).
- Los snapshots conservan ahora las cuatro curvas de fade. El nuevo probe falla
  en la versión anterior con `Snapshot lost fade type EXPONENTIAL` y pasa en
  el paquete nuevo. Esta corrección es distinta de la optimización de bypass.

## Rendimiento reproducido

By Now, 280 s, 48 kHz estéreo; Ryzen 9 9950X3D, Temurin 25.0.4.7, heap 4 GB,
JFR `profile`. Mediciones finales sin otra suite/benchmark en paralelo.
La línea base tiene una ejecución; la interacción nueva tiene tres procesos
independientes y se informa su mediana. No son percentiles p95 ni garantías
para cualquier canción o equipo. Se mide adopción del plan y estadísticas,
no la latencia del dispositivo de audio.

| Ensayo | Antes (s) | P0 (s) |
|---|---:|---:|
| Cadena + medición, todo apagado | 12,282 | 1,856 |
| Cadena + medición, Leveler caliente | 6,075 | 3,294 |
| Cadena completa activa + medición | 23,063 | 22,636 |
| UI: carga con Leveler | 16,246 | 10,924 |
| UI: cambio de Leveling | 7,347 | 3,988 |
| UI: ráfaga, desde el último ajuste | OOM / sin resultado | 3,772 |

La primera pasada Leveler activa de P0 tarda 9,972 s en el ensayo por etapas:
ahora el bypass anterior no construye su análisis. No compararla con la fila
Leveler de la línea base, que ya estaba caliente.

Cambios individuales nuevos: 3,907 / 4,062 / 3,988 s. Retraso máximo de pulso
FX observado: 27,795 / 12,523 / 27,676 ms, frente a 251,931 ms antes. Las tres
ráfagas de seis cambios cada 350 ms terminan sin OOM, con máximo un worker de
análisis observado. El audio final coincide bit a bit con un Leveler nuevo
analizado en frío al último valor (0,8): 8.255.770 muestras modificadas.

La presión de memoria sigue siendo importante. JFR observa máximos antes de GC
de 3451,49 / 3613,29 / 3462,56 MiB en los procesos nuevos, que incluyen también
el análisis frío independiente de verificación. No equivalen a memoria viva
retenida o RSS. El proceso anterior alcanzó 4095,73 MiB y falló; los tres nuevos
completan con el mismo límite. Esto no demuestra que pistas arbitrariamente
largas quepan en 4 GB ni cierra la reducción de buffers del multibanda activo.

## Audio e interfaz

Los cuatro renders comparados conservan SHA-256 PCM raw-float idéntico respecto
a la entrega anterior: bypass, Leveler frío/caliente y cadena completa activa.
La comparación de la versión anterior hecha junto a la suite se usa únicamente
como evidencia de equivalencia; sus tiempos no sustituyen la línea base aislada.

En el JAR de Program Files:

- By Now original: 8.255.793 muestras modificadas; mínimo −0,313163 dB.
- Desnivel +4 dB inyectado solo en memoria: 4,962374 → 2,515670 LU;
  mejora 2,446704 LU. No se altera el WAV.
- Regiones protegidas bitexactas, control al 0 % idéntico al original.
- Carga asíncrona UI: generación actual adoptada y mismo render; captura revisada.
- Waveform: Ctrl+rueda zoom, rueda pan, reproducción y selección intactas;
  también pasa la comprobación de UI de tempo.
- SHA-256 de la fuente antes/después:
  `dcfb61d5d419bf6044bb0dd42fd7639659f33564c4b7ef9219cbf66798bf8e91`.

Son pruebas numéricas y de integración. No se simula una escucha humana.

## Entrega recuperable

- Imagen portable de 201 archivos, Temurin 25.0.4.7, sin instalador:
  `dist/performance-fixed-20260927/QuickMaster`.
- Copia completa y todos los hashes verificados en `C:/Program Files/QuickMaster`.
  Resultado DEPLOYED_AND_VERIFIED a las 03:48:28.
- Respaldo: `C:/Program Files/QuickMaster-backup-20260927-034821`;
  conserva el JAR anterior 1cc8d872… sin borrar documentos ni audio.
- Arranque del EXE adicional como usuario normal: 03:49:34–35, JavaFX,
  controlador y restricción nativa de tamaño sin ERROR/SEVERE. Solo se cerraron
  los procesos propios de comprobación, nunca una sesión del usuario.
- Recibo y logs en `performance-evidence/p0-*`; JFR y capturas locales en
  `dist/performance-20260927`. No hay rama, tag ni release nuevo.

## Límites y siguiente trabajo

Se ha serializado el análisis de salida, no batch/export ni las cargas de
fuente concurrentes. Los algoritmos sin sondeo interno se cancelan entre etapas.
La adopción sigue esperando las estadísticas finales. Faltan reutilización por
etapas, remapeos fuera de FX, menor memoria de multibanda y actualización trazable
de DSPark (todavía Java 0.1.0). La cadena completamente activa conserva su coste.
El port tiene una línea base de 114 pruebas aprobadas y un nuevo oráculo FFT
independiente, pero no se ha sustituido todavía la biblioteca.

# Rendimiento interactivo y actualización de DSPark

Fecha: 2026-09-27. Estado: **P1 y DSPark Java 0.2 entregados y comprobados en
Program Files**, tras suite completa y QA del portable. Nueva autorización del
usuario resuelve la cancelación previa de UAC; entrega a las 09:10:57, hash/árbol
de 201 archivos, arranque normal y aceptación instalada PASS.
La corrección anterior del Leveler se conserva y se vuelve a comprobar.
No se publica otro release. No se utilizan skills ni agentes
de orquestación. Este documento es el plan de trabajo, no una certificación.

## Versiones inspeccionadas

- QuickMaster instalado al iniciar el diagnóstico: JAR SHA-256
  `1cc8d872b1436f9554473429c5a77644cc8e1184662db63cb8d3fa980ec96524`.
  Fuentes en `QuickMaster-Integration`, corrección aún sin commit ni push.
  `origin/main` de QuickMaster sigue en `eedf83575aa51158b6ce19a7b97b5f00289fef24`.
- Dependencia real: `com.dspark:dspark:0.1.0`, incluida como JAR en `libs`.
  SHA-256 `de368f326f668267d0cba20f34135efb466dafc53f84a4aee32d8af4347e4141`.
  Coincide con `E:/Code/Libs/java/dsp/target/dspark-0.1.0.jar`.
  Fuentes disponibles en `E:/Code/Libs/java/dsp/src`; esa carpeta no es un
  repositorio Git. No hay trazabilidad de commit C++ fijada en el POM Java.
- DSPark C++: `https://github.com/CristianMoresi/DSPark`.
  `main` remoto comprobado y obtenido por fetch:
  `9330f1cd29164f6d33e7876bc422627a200ec919`.
  La copia de trabajo C++ permanece limpia en `c90ac85`; no se ha hecho pull
  ni modificado sus fuentes. La comparación se realiza contra el objeto remoto.
  El changelog de main incluye 1.8.0 (26 de septiembre) y cambios posteriores;
  no se encontró `refs/tags/v1.8.0` durante esta inspección. No confundir main
  con el último release público ni el número Java 0.1.0 con una versión C++.

## Reproducción y resultados

Fuente local intacta: By Now, 280 s, 48 kHz, estéreo, 13.440.001 frames.
Ryzen 9 9950X3D (16 núcleos/32 hilos), 31,04 GiB de RAM física informada.
Temurin 25.0.4.7, JAR instalado, procesos separados con APPDATA aislado,
`-Xmx4g`, Java Flight Recorder en configuración `profile`. No exportación,
reproducción física, cambios en preferencias del usuario ni audio distribuido.

`tools/diagnostics/PipelineLatencyProbe.java` instancia la cadena auténtica del
controlador y mide sus analizadores, render y medición final por separado. El
envoltorio de cronometraje delega los métodos del contrato actual. Las cifras
son una ejecución diagnóstica con instrumentación, no percentiles ni un benchmark
universal. La pasada fría incluye calentamiento del JIT y construcción de cachés.

| Configuración | Cadena (s) | Medición final (s) | Total (s) |
|---|---:|---:|---:|
| Todos los módulos desactivados, primera pasada | 11,196 | 1,087 | 12,282 |
| Solo Leveler, análisis estructural ya disponible | 4,843 | 1,056 | 5,899 |
| Solo Leveler, repetición caliente | 4,992 | 1,082 | 6,075 |
| Cadena completa activa, parámetros iniciales | 21,812 | 1,251 | 23,063 |

En la primera fila, el Leveler desactivado consume 7,469 s de análisis;
multibanda desactivado 1,823 s, broadband desactivado 0,521 s y normalizador
desactivado 0,472 s. Todos reciben además una copia/render de la pista aunque
su salida sea identidad. La fuente conserva su SHA-256.

El muestreo CPU de esas cuatro pasadas atribuye 28,16 % a
`FFTComplex.butterflyPass` y 19,80 % a `TruePeak.process`. No son porcentajes
de toda posible sesión de la aplicación. En asignaciones muestreadas,
`ProcessingPipeline.renderStage` representa 47,58 %, `LimiterEnvelope` 19,24 %
y el análisis multibanda 11,10 %. No confundir presión de asignación con memoria
viva máxima o memoria total del sistema.

`tools/diagnostics/InteractionLatencyProbe.java` usa carga asíncrona real y los
listeners reales del knob. Espera adopción de la generación vigente y fin de
trabajos; no mide la latencia del dispositivo de salida:

- Carga con solo Leveler activo: 16,246 s.
- Un cambio de Leveling: 7,347 s; retraso máximo del pulso FX observado 251,931 ms.
- Seis cambios separados 350 ms: **fallo**, `OutOfMemoryError: Java heap space`
  dentro de `MultibandCrossover.splitWhole`, llamado por el análisis multibanda
  desactivado. No hay un tiempo final válido ni resultado de aceptación aprobado.
  Se guardó JFR y se terminó únicamente el proceso propio de prueba tras el
  error; su espera de éxito no podía completarse. El EXE del usuario no estaba
  abierto. El límite de 4 GB identifica este ensayo, no un límite universal de
  la instalación ni una afirmación de que toda máquina vaya a fallar igual.
  El harness se ha endurecido después para terminar inmediatamente cuando la
  generación vigente falla o se cancela; no esperar el timeout de éxito.

Logs archivados en `performance-evidence/`; JFR y logs completos locales en
`dist/performance-20260927`. La reproducción fallida es evidencia del defecto,
no un resultado de validación verde.

## Causas y riesgos observados

1. `ProcessingPipeline.analyzeAndRender` analiza todo `usesAnalysis()` sin
   comprobar el bypass. `renderStage` copia la pista y crea bloques también
   para esos módulos. Algunas medidas de módulos apagados sí alimentan UI:
   los rangos Peak/Beat y el pico del normalizador. No omitirlas ciegamente.
2. Cada vencimiento del debounce crea una instantánea completa y un nuevo hilo.
   La generación impide adoptar resultados obsoletos, pero no cancela su CPU
   ni libera sus grandes buffers mientras trabajan. No hay límite de concurrencia.
3. La adopción de envolventes espera además a LUFS, true peak y espectro de toda
   la salida. Esas estadísticas no deberían bloquear la disponibilidad de un
   plan de audio cuya seguridad ya esté demostrada.
4. Solo hay caché del prefijo tonal. Los analizadores posteriores se reconstruyen
   incluso ante ediciones locales posteriores en la cadena. Ampliar caché sin
   límite de memoria podría agravar el problema, no resolverlo.
5. Los setters y la adopción de los limitadores remapean envolventes densas
   desde UI. La inspección detallada confirma que Peak/Beat/Punch ya separan
   setters y análisis; la atribución inicial a todos ellos era demasiado amplia.
6. El Leveler local aporta su propio análisis estructural y núcleo de sonoridad;
   no todo el tiempo observado pertenece a DSPark ni se arregla sustituyendo el JAR.

## Comparación del port con DSPark actual

| Área usada por QuickMaster | Java inspeccionado | C++ main y trabajo requerido |
|---|---|---|
| FFT | Radix-2, bit reversal, datos complejos intercalados | Stockham radix-4, datos separados y SIMD; port y pruebas numéricas/rendimiento JVM |
| Sobremuestreo | FIR de bajada recorre el kernel completo | Decimación polifásica y `SimdOps`; preservar filtro, fase, estado y latencia |
| Conversiones dB | `pow` y `log10` | `exp`/`log`; medir en JVM y conservar el contrato Java de valores extremos |
| Campanas EQ | `peakMatched` usa la fórmula anterior de Orfanidis | Vicanek corregido y nuevo default; validar respuesta y compatibilidad de presets |
| Suavizado | Rampa lineal por velocidad fija; mínimo 0,1 ms | Duración por salto y tiempo cero instantáneo; probar continuidad y duración |
| Limitación/saturación | Adaptaciones Java y envolventes offline propias | Revisar ganancia, ADAA y transiciones; no reemplazar por semejanza de nombres |
| Sonoridad/true peak | Port más núcleo local con pruebas oficiales | Conservar conformidad y recalificar cada cambio; no rebajar precisión para ganar CPU |

Referencias fijadas: [changelog C++](https://github.com/CristianMoresi/DSPark/blob/9330f1cd29164f6d33e7876bc422627a200ec919/CHANGELOG.md)
y [migración 1.8.0](https://github.com/CristianMoresi/DSPark/blob/9330f1cd29164f6d33e7876bc422627a200ec919/docs/migration-v1.8.0.md).
Las aceleraciones SIMD publicadas para C++ no se prometen para Java. No se
importarán reverbs, sintetizadores o adaptadores de plugins que QuickMaster no usa.

La suite actual de la biblioteca Java se ha ejecutado sin cambiar sus fuentes:
114 pruebas, cero fallos/errores/omisiones. Log local
`dist/performance-20260927/dspark-java-baseline-tests.log`. Esto es una línea base,
no evidencia de paridad con C++: FFTReal tiene solo DC, ida/vuelta de 1024 puntos
y localización de un coseno; falta DFT independiente y cobertura directa de
FFTComplex. SmoothedValue no prueba duración lineal por salto ni tiempo cero.

Se ha añadido `tools/diagnostics/DsparkFftAcceptance.java` como oráculo independiente
de migración. El JAR actual pasa DFT compleja directa para tamaños 2–1024, con
tres entradas distintas por instancia; FFT real 4–65536 con ida/vuelta, impulso,
DC, Nyquist y fase de seno. Tolerancias fijadas antes de ejecutar: RMS relativa
compleja ≤1e-6, error absoluto espectral ≤3e-6·sqrt(N), ida/vuelta ≤2e-6.
No usa el resultado de otra FFT como única referencia ni modifica la biblioteca.
Resultados de biblioteca y oráculo archivados en `performance-evidence/dspark-*`.

## Orden de desarrollo

### P0 entregado (27 de septiembre, 03:48)

- El análisis de salida utiliza un único worker físico y conserva como máximo
  la última petición pendiente. Una tarea cancelada que aún esté saliendo no
  puede solaparse con la siguiente. La prueba fuerza este caso y sustituye cien
  peticiones; solo se ejecuta la última.
- El controlador transmite cancelación al Leveler, comprueba generaciones al
  publicar y al autorizar reproducción, y cancela al cerrar la aplicación.
  Los demás analizadores se interrumpen entre etapas; no se promete cancelación
  instantánea dentro de todos los algoritmos ni serialización de batch/export.
- Sin copia PCM por evento de control. Las ediciones de fuente sustituyen el
  array; la caché tonal exige también identidad del array de origen.
- Bypass offline sin análisis/render inútil, con opt-in explícito de metadatos
  de Peak, Beat y normalizador. El procesamiento activo no cambia de algoritmo.
- Se ha corregido una omisión encontrada en snapshots: ahora conservan la curva
  de fade seleccionada, además de sus duraciones.
- La primera suite se detuvo al detectar tres fallos de hash de la misma frontera
  AudioProcessor. Es una ejecución fallida/incompleta, aunque el wrapper al
  terminar el proceso imprimió `PACKAGE_EXIT=0`; no llegó a `BUILD SUCCESS`.
  Se revisó la nueva interfaz y se declaró un sucesor activo literal sin tocar
  el contrato histórico. Después pasan las pruebas focales y un build limpio completo.
  Auditoría: `audio-processor-boundary-p0.md`.
- La comparación de hashes PCM anterior se ejecutó junto a la suite y sirve
  únicamente para equivalencia, no para comparar tiempos. Los nuevos tiempos
  se midieron después sin otra suite compitiendo por CPU.

Entrega comprobada: 705 pruebas, cero fallos/errores/omisiones, 106 variantes
musicales y 94 mediciones oficiales del perfil de archivo. JAR instalado
`b01ed4faf56ac0a275b59a560bef6572378ae29306195b9ff16d5cf8c1fdd5c3`.
Tres ráfagas reales completan sin OOM y con audio bitexacto al último ajuste.
El cambio individual de Leveling pasa de 7,347 s a mediana 3,988 s; todo apagado
de 12,282 a 1,856 s. La cadena completa activa aún tarda 22,636 s.
Detalle, límites y recibo: [validación P0](performance-p0-validation.md).
En aquella entrega P0, DSPark Java permanecía en 0.1.0. Los puntos siguientes ya
están implementados, probados y entregados en el portable P1/0.2:
publicación del plan antes de estadísticas, reutilización por etapas, cargas
concurrentes de fuente, remapeos fuera de FX, buffers multibanda y actualización
trazable de FFT/sobremuestreo. Resultado: [validación P1](performance-p1-validation.md).

### 1. Planificación y memoria de la aplicación

- Un solo análisis vigente en ejecución, una sola petición más reciente pendiente,
  cancelación cooperativa de trabajo obsoleto y ninguna instantánea PCM completa
  por cada evento de rueda/knob. No esperar en el hilo FX a que termine un worker.
- Omitir análisis/render inútiles de módulos apagados manteniendo explícitamente
  las medidas necesarias para sus controles. Probar apagado→edición→encendido.
- Mantener publicación atómica y comprobación de generación también con carga,
  cambio de canción, trim, presets, A/B, errores, cancelación y cierre.
- La ráfaga que ahora agota memoria debe terminar con el último ajuste correcto,
  sin OOM y con concurrencia acotada. Comparar audio antes/después bit a bit cuando
  solo cambie la planificación. No desactivar seguridad ni procesadores activos.

### 2. Latencia de escucha y reutilización

- Separar plan de audio preparado y seguro de las estadísticas finales de salida.
  Señalar mediciones pendientes sin etiquetar como actuales datos anteriores.
- Sacar remapeos costosos del hilo FX. Separar controles solicitados de estado de
  audio publicado; no simular respuesta rápida reproduciendo un bypass accidental.
- Caché de etapas por fuente/parámetros/orden y con presupuesto de memoria; invalidar
  desde la primera etapa afectada. El coste de cambiar Leveling no debe incluir
  analizadores apagados ni repetir estructura si su entrada es idéntica.
- Medir tiempos repetidos de cambio→plan aplicable, respuesta FX, CPU, GC y memoria,
  con pistas de varias duraciones y sobremuestreo. Informar mediana/p95 y hardware.

### 3. Actualización trazable de DSPark Java

- Versionar fuentes y tests del port y fijar revisión C++ de referencia; el JAR
  debe ser reconstruible sin depender de una carpeta externa sin historial.
- Actualizar primero FFT y sobremuestreo por su coste medido. Comparar con DFT
  independiente y vectores C++: impulso, seno, ruido, DC/Nyquist, ida/vuelta,
  tamaños, particiones, estéreo y factores de sobremuestreo.
- Después EQ, suavizado, conversiones y dinámica, separando optimización equivalente
  de cambios de sonido. Mantener comportamiento de presets o migrarlo explícitamente.
- Reejecutar conformidad oficial, pico verdadero, aliasing, respuesta, latencia,
  estabilidad y regresiones Leveler/Beat Comp. No sustituir pruebas por hashes nuevos.

### 4. Entrega de cada cambio de aplicación

- Suite completa, paquete, imagen Windows con runtime soportado, copia portable
  completa a Program Files, hash y arranque limpio. Pruebas interactivas y de audio
  sobre el paquete entregado, no solo clases de desarrollo.
- Conservar respaldo y audio original. Sin nueva rama, release ni autor adicional;
  commits `[TIPO] resumen` de un máximo de dos líneas cuando se soliciten.
- No declarar acabada la optimización global por mejorar únicamente un benchmark.

# QuickMaster — desarrollo y cierre

Actualizado: 2026-09-25. Este es el plan vigente. El desarrollo continúa directamente, sin skills de orquestación.

## Resultado buscado

1. Leveler offline que ajuste diferencias de nivel entre secciones comparables, conservando intros, breaks, outros y crescendos intencionales.
2. Beat Comp con límite real de reducción, tempo fiable, incertidumbre visible y comportamiento continuo y enlazado entre canales.
3. Zoom horizontal de waveform con Ctrl + rueda en Windows/Linux y el modificador de acceso rápido de macOS, anclado al cursor.
4. Entrega del árbol portable completo en `C:\Program Files\QuickMaster`, con hash y arranque verificados. No se usa instalador.

## Fuente e integración

- Rama: `codex/leveler-beat-zoom`, worktree `E:\Code\Projects\JA-DAW\QuickMaster-Integration`.
- El repositorio principal y sus cambios locales se conservan en `E:\Code\Projects\JA-DAW\QuickMaster`.
- GitHub se comprobó el 25 de septiembre: `HEAD...origin/main = 0/0`; no había cambios remotos para incorporar.
- Los 288 archivos iniciales de `src` y `pom.xml` se copiaron desde la versión validada de `QuickMaster-Rapid/product` con identidad de hashes. La integración añade recursos portables de pruebas, documentación y entrega, además de las correcciones de contrafase y ponderación rítmica encontradas en la revisión final.
- Los commits seguirán `[TIPO] resumen breve`, máximo dos líneas. La directiva queda también en `AGENTS.md`.

## Verificado en la versión de producto

- Suite completa: **665 pruebas, 0 fallos, 0 errores, 0 omitidas**.
- JAR validado: SHA-256 `3324690A73518E7FF2A04B615F01CE8746F5B1DA0703310B86CFE63434289FC0`.
- Conformidad de sonoridad de archivo ITU/EBU: `PASSED`, 94 mediciones. No equivale a certificación de todo EBU Mode ni a evaluación subjetiva del sonido.
- Leveler: memoria segmentada y eliminación de asignaciones evitables; pruebas de equivalencia numérica, protección de dinámica, corrección de secciones comparables, canales enlazados y límites de rampa aprobadas.
- Recursos del JAR final, para 1/10/60 minutos: incremento de RSS de 30,72/48,50/127,65 MiB; análisis de una hora en 111,76 s. Todos los límites del ensayo pasan. La memoria del PCM fuente se mide aparte en la línea base.
- Beat Comp sobre «Quiet Gold»: 82,25 BPM frente a 82 BPM del proyecto; confianza 0,65. Objetivo −1 dB: ningún exceso fuera del redondeo en 19.765.741 muestras. Bloques de 997/4096/65536 frames producen el mismo SHA-256 de audio, con diferencia entre ganancias L/R inferior a 1,2e−7.
- «Quiet Gold» original: Leveler se abstiene con `PEAK_UNSAFE`; en una copia atenuada solo en memoria analiza 17 secciones y mantiene la dinámica sin cambios injustificados. El WAV original conserva su hash. Este caso prueba protección, no una corrección positiva de una canción real.
- Zoom: pruebas de coordenadas, cache de picos e integración del controlador aprobadas. La interfaz JavaFX real se cargó con audio sintético y recibió eventos de rueda: 16 s → 5,358 s visibles, ancla de 4 s estable, rueda sin modificador sin zoom y retorno exacto a vista completa. Capturas revisadas visualmente con fades y selección alineados. Falta repetir el smoke con los JAR instalados.
- Imagen portable generada con Temurin 25.0.4.7 y `jpackage --type app-image`. El EXE de esa imagen arranca desde el workspace: log nuevo con inicio y controlador inicializado, sin errores.
- La copia directa a `Program Files` fue denegada por Windows. La imagen instalada anterior sigue intacta, con JAR `7FDA545AFA0FF85FD71549F04D36009E294E5A2C9B6C6338EBEBF4186CE2EC23`.

## Trabajo pendiente, en orden

Hallazgos de la revisión final ya corregidos y aprobados en pruebas focales:

- La mezcla L+R previa al detector de ataques anulaba material estéreo en contrafase. `StereoOnsetDetector` combina energía espectral por canal antes del flujo, sin cancelación. Pasa inversiones independientes de polaridad, intercambio de canales y ataques alternados a 44,1/48/96 kHz.
- La raíz cuadrada de los productos de saliencia favorecía demasiadas subdivisiones débiles. Usar el producto de saliencias conserva los acentos y devuelve 82,25 BPM en «Quiet Gold» con el nuevo detector. La confianza tiene en cuenta la ambigüedad mitad/doble de tempo sin confundir la preferencia de rango con evidencia musical.
- El lookahead alcanza el pico del golpe fuerte sin desplazar audio; el release medido coincide con la nota seleccionada. El archivo real conserva el límite de −1 dB. El resultado definitivo se volverá a comprobar sobre el JAR empaquetado.
- Un ensayo PCM con secciones a 80 y 120 BPM descubrió que el detector de respaldo anulaba el rechazo del detector principal. Se conserva ahora ese rechazo explícito: no se impone un tempo único cuando las secciones son incompatibles. La prueba reproduce el fallo antes del cambio y pasa en mono y estéreo después. La UI marca estimaciones inciertas con `auto?` y explica el override manual y el release de respaldo; la interacción JavaFX real está comprobada.

### 1. Cerrar la reproducibilidad de la rama

- Extraer los seis contratos históricos exactos a `src/test/resources/leveler/contracts` y preservar sus hashes, incluidas terminaciones de línea.
- Eliminar rutas absolutas de las pruebas; recibir los corpus oficiales y autorización mediante propiedades Maven. Los audios permanecen externos y no se redistribuyen.
- Escribir toda nueva evidencia de pruebas bajo `target`. Mantener las carpetas históricas como archivo hasta comprobar que ya no son dependencias.
- Ejecutar una suite completa y build limpios en la rama integrada, sin la carpeta de orquestación presente. La repetición anterior sobre bytes idénticos se canceló al detectar estas dependencias; no se cuenta como resultado completo.

### 2. Completar la revisión de producto

- Revisar los límites de Beat Comp también durante cambios de parámetros, solapes, lookahead, release, bypass y desplazamiento del transporte. Las pruebas nuevas deben cubrir riesgos concretos, sin duplicar la implementación.
- Confirmar el zoom visual, el anclaje, los límites y la coherencia de selección, playhead y fades. Si la sesión no permite controlar la aplicación nativa, dejar claramente pendiente la verificación visual.
- Mantener separadas las pruebas objetivas del audio y una escucha humana. No afirmar calidad subjetiva por una suite automática.

### 3. Entregar e integrar

- Generar la imagen portable desde el JAR final de la rama integrada y comprobar la identidad de las clases de aplicación frente al artefacto ya medido. Repetir mediciones de audio/recursos solo si cambian esas clases o aparece una regresión.
- Ejecutar `tools/Deploy-Portable.ps1`: prepara una copia, compara todos los archivos, conserva la instalación anterior, copia a `C:\Program Files\QuickMaster` y verifica el EXE instalado y el log. Requiere los permisos de administrador de Windows.
- Cerrar únicamente el proceso abierto por la comprobación y conservar la carpeta de respaldo para poder recuperar la versión anterior.
- Registrar el hash instalado y el resultado `DEPLOYED_AND_VERIFIED` en `target/deployment-result.json` y en este plan.
- Revisar el diff y guardar commits breves con el formato exigido. No publicar audio oficial, resultados temporales ni archivos personales de autorización.

## Criterio de fin

La tarea no está cerrada mientras queden regresiones conocidas, una build sin verificar o la copia instalada pendiente. La conformidad de medición y los ensayos automáticos son evidencia objetiva; la valoración auditiva y la interacción visual se informan con su alcance real.

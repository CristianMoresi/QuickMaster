# QuickMaster — desarrollo y cierre

Actualizado: 2026-09-25. La ampliación de desplazamiento de waveform está implementada, validada, publicada en GitHub y entregada en Program Files, con hash y arranque comprobados. Este es el registro vigente; no se utilizan skills de orquestación.

## Resultado buscado

1. Leveler offline que ajuste diferencias de nivel entre secciones comparables, conservando intros, breaks, outros y crescendos intencionales.
2. Beat Comp con límite real de reducción, tempo fiable, incertidumbre visible y comportamiento continuo y enlazado entre canales.
3. Zoom horizontal de waveform con Ctrl + rueda en Windows/Linux y el modificador de acceso rápido de macOS, anclado al cursor. Rueda sin modificador para desplazar solo la vista, sin alterar la reproducción.
4. Entrega del árbol portable completo en `C:\Program Files\QuickMaster`, con hash y arranque verificados. No se usa instalador.

## Ampliación: desplazamiento de la vista con la rueda

- Rueda arriba hacia el inicio y abajo hacia el final, a un 10 % de la duración visible por paso normal; admite scroll horizontal y fracciones de paso, con límites en ambos extremos.
- Ctrl/Command + rueda conserva el zoom. La rueda normal no cambia los fades aunque pase por sus tiradores; esa edición pasa a Alt + rueda y se indica en la waveform y en README.
- Añadidas pruebas de dirección, límites, entradas inválidas, reversibilidad y transformación coherente de coordenadas. Las 15 pruebas focales de waveform pasan.
- La prueba JavaFX falló antes de implementar el cambio y pasa después: la vista pasa de 2,660408 s a 1,052898 s conservando 5,358368 s visibles, la selección y el transporte. Un reproductor de prueba rechaza cualquier llamada a seek/play/pause/stop; no se utiliza una salida de audio física.
- Suite completa limpia y empaquetado aprobados: **677 pruebas, 0 fallos, 0 errores, 0 omitidas**, en 94 informes, con 106 variantes de la matriz musical. Conformidad ITU/EBU de archivo: `PASSED`, 94 mediciones.
- Imagen portable generada con Temurin 25.0.4.7, 201 archivos. SHA-256 de JAR e imagen: `F1207AAA1A4CF7C638F8B9E3453038A31BF6E024260D5D398B53C135E86F16F6`. La imagen anterior se conserva en `dist/QuickMaster-before-pan-20260925`.
- La prueba JavaFX también pasa sobre el paquete final (`WAVEFORM_PAN_PASS`), con transporte protegido, dirección, límites, scroll horizontal, zoom y Alt + rueda sobre fades comprobados. Las 131 clases de audio, DSP y reproducción son idénticas a la entrega anterior.
- Copia instalada: tras la cancelación inicial de la elevación, el usuario autorizó expresamente reintentar. Entrega completada a las 08:07 mediante la elevación normal de Windows, sin instalador: `target/deployment-result.json` registra `DEPLOYED_AND_VERIFIED`, 201 archivos y el JAR instalado con el SHA-256 F1207… indicado arriba. Respaldo recuperable de la versión anterior: `C:\Program Files\QuickMaster-backup-20260925-080705`, con JAR `1196DE105D24FF8E3B094627EA33719709314DB3BE75C1171063E566C1BC7603`.
- EXE de Program Files probado también como usuario normal durante siete segundos: log nuevo a las 08:09:26–27 con inicio JavaFX, controlador inicializado y restricción nativa de aspecto activada, sin errores. Solo se cerró el proceso abierto para esta comprobación.
- Prueba JavaFX repetida contra los JAR instalados: `WAVEFORM_PAN_PASS`, `WAVEFORM_UI_PASS` y `TEMPO_UI_PASS`. Conserva transporte y selección; las cuatro capturas de desplazamiento, zoom, vista restaurada y Dynamics coinciden por SHA-256 con las del paquete validado.
- Publicación completada: commit `8ccff3c` — `[FEAT] pan waveform with mouse wheel without seeking`, enviado a `origin/codex/leveler-beat-zoom`. Se conserva intacto el checkout principal con cambios pendientes.
- Destino definitivo solicitado por el usuario: `origin/main`, mediante avance directo desde la rama validada, sin reescribir historial. Autor y committer únicos: Cristian Moresi; sin coautores ni atribución a asistentes. Mensajes breves `[TIPO] resumen`, máximo dos líneas.

## Fuente e integración

- Rama: `codex/leveler-beat-zoom`, worktree `E:\Code\Projects\JA-DAW\QuickMaster-Integration`.
- Commit de implementación: `9b06e16` — `[FIX] add structural leveling, bounded Beat Comp and waveform zoom`.
- El repositorio principal y sus cambios locales se conservan en `E:\Code\Projects\JA-DAW\QuickMaster`.
- GitHub se comprobó el 25 de septiembre: `HEAD...origin/main = 0/0`; no había cambios remotos para incorporar.
- Los 288 archivos iniciales de `src` y `pom.xml` se copiaron desde la versión validada de `QuickMaster-Rapid/product` con identidad de hashes. La integración añade recursos portables de pruebas, documentación y entrega, además de las correcciones de contrafase y ponderación rítmica encontradas en la revisión final.
- Los commits seguirán `[TIPO] resumen breve`, máximo dos líneas. La directiva queda también en `AGENTS.md`.

## Entrega anterior verificada (06:40)

- Suite completa limpia y empaquetado Maven terminados con código de salida 0: **674 pruebas, 0 fallos, 0 errores, 0 omitidas**, en 94 informes. Incluye 106 variantes de la matriz musical.
- JAR final, imagen portable e instalación: SHA-256 idéntico `1196DE105D24FF8E3B094627EA33719709314DB3BE75C1171063E566C1BC7603`.
- Conformidad de sonoridad de archivo ITU/EBU: `PASSED`, 94 mediciones. No equivale a certificación de todo EBU Mode ni a evaluación subjetiva del sonido.
- Leveler: memoria segmentada y eliminación de asignaciones evitables; pruebas de equivalencia numérica, protección de dinámica, corrección de secciones comparables, canales enlazados y límites de rampa aprobadas.
- Recursos medidos para 1/10/60 minutos: incremento de RSS de 30,72/48,50/127,65 MiB; análisis de una hora en 111,76 s. Todos los límites del ensayo pasan. La memoria del PCM fuente se mide aparte en la línea base. Las 81 clases del núcleo Leveler, su procesador y su render compartido son idénticos a los de aquella medición; los cambios posteriores afectan al tempo y su UI.
- Beat Comp sobre «Quiet Gold», repetido con el JAR final: 82,25 BPM frente a 82 BPM del proyecto; confianza 0,65, 737 ataques. Objetivo −1 dB: ningún exceso fuera del redondeo en 19.765.741 muestras. Ganancia aplicada mínima −1,000000226 dB por redondeo del PCM flotante; medidor −0,999999709 dB. Bloques de 997/4096/65536 frames producen el mismo SHA-256 de audio `6b6bd0bed83231b69c09a0c2b52ecd4dc22e8957b5accf9f280d8ed5a40e0b5d`, con diferencia entre ganancias L/R inferior a 1,2e−7.
- «Quiet Gold» original: Leveler se abstiene con `PEAK_UNSAFE`; en una copia atenuada solo en memoria analiza 17 secciones y mantiene la dinámica sin cambios injustificados. El WAV original conserva su hash. Este caso prueba protección, no una corrección positiva de una canción real.
- Zoom: pruebas de coordenadas, cache de picos e integración del controlador aprobadas. La interfaz JavaFX real se cargó con audio sintético y recibió eventos de rueda: 16 s → 5,358 s visibles, ancla de 4 s estable, rueda sin modificador sin zoom y retorno exacto a vista completa. Capturas revisadas visualmente con fades y selección alineados. Se repitió el ensayo con los JAR instalados: `WAVEFORM_UI_PASS` y `TEMPO_UI_PASS`; las capturas de Dynamics y waveform coinciden byte a byte con las revisadas.
- Imagen portable de 201 archivos generada con Temurin 25.0.4.7 y `jpackage --type app-image`; no se creó instalador.
- Copia a `C:\Program Files\QuickMaster` completada mediante la elevación normal de Windows. Resultado `DEPLOYED_AND_VERIFIED` en `target/deployment-result.json`, con todos los archivos copiados comprobados por hash.
- EXE instalado probado también como usuario normal durante siete segundos: log nuevo a las 06:39:36–37, inicio JavaFX, controlador inicializado y restricción nativa de aspecto activada, sin errores. Solo se cerraron los procesos abiertos para estas comprobaciones.
- Respaldo recuperable: `C:\Program Files\QuickMaster-backup-20260925-063849`. Conserva el JAR anterior `7FDA545AFA0FF85FD71549F04D36009E294E5A2C9B6C6338EBEBF4186CE2EC23`. No se ha borrado audio ni documentación del usuario.

## Revisión final completada

Hallazgos de la revisión final ya corregidos y aprobados en pruebas focales:

- La mezcla L+R previa al detector de ataques anulaba material estéreo en contrafase. `StereoOnsetDetector` combina energía espectral por canal antes del flujo, sin cancelación. Pasa inversiones independientes de polaridad, intercambio de canales y ataques alternados a 44,1/48/96 kHz.
- La raíz cuadrada de los productos de saliencia favorecía demasiadas subdivisiones débiles. Usar el producto de saliencias conserva los acentos y devuelve 82,25 BPM en «Quiet Gold» con el nuevo detector. La confianza tiene en cuenta la ambigüedad mitad/doble de tempo sin confundir la preferencia de rango con evidencia musical.
- El lookahead alcanza el pico del golpe fuerte sin desplazar audio; el release medido coincide con la nota seleccionada. El archivo real conserva el límite de −1 dB, confirmado sobre el JAR empaquetado.
- Un ensayo PCM con secciones a 80 y 120 BPM descubrió que el detector de respaldo anulaba el rechazo del detector principal. Se conserva ahora ese rechazo explícito: no se impone un tempo único cuando las secciones son incompatibles. La prueba reproduce el fallo antes del cambio y pasa en mono y estéreo después. La UI marca estimaciones inciertas con `auto?` y explica el override manual y el release de respaldo; la interacción JavaFX real está comprobada.

### Reproducibilidad

- Los seis contratos históricos exactos están en `src/test/resources/leveler/contracts`, con hashes y terminaciones de línea preservados también en Git.
- Las pruebas reciben los corpus oficiales y la autorización mediante propiedades Maven, sin rutas personales fijas. Los audios permanecen externos y no se redistribuyen.
- Los resultados nuevos se escriben bajo `target`. La suite y el build limpios han pasado sin carpetas de orquestación en este worktree. Los archivos históricos externos se conservan; no son instrucciones ni dependencias de ejecución.
- Las ejecuciones interrumpidas para corregir dependencias y fallos de tempo no se cuentan como validaciones completas; el resultado de 674 pruebas pertenece a una única ejecución limpia posterior a todas las correcciones.

### Alcance de la revisión

- Límites de Beat Comp comprobados durante cambios de parámetros y con solapes, lookahead, release, bypass y cambios de posición.
- Zoom comprobado mediante eventos sobre la interfaz JavaFX real y capturas revisadas. No equivale a una sesión manual de manejo con ratón físico ni valida un ejecutable nativo de macOS/Linux; esos modificadores se resuelven mediante el shortcut de JavaFX.
- Las pruebas objetivas de audio no sustituyen una escucha humana. No se declara una certificación subjetiva de calidad ni reconocimiento infalible de intención musical.

### Entrega y continuación

- La versión de uso está en `C:\Program Files\QuickMaster\QuickMaster.exe`; la imagen fuente queda en `dist/QuickMaster` dentro de este worktree.
- Para cambios futuros, trabajar en `QuickMaster-Integration`, consultar este documento y seguir `docs/VALIDATION.md` y `AGENTS.md`. Repetir suite, paquete, imagen y entrega cuando cambie la aplicación.
- La publicación definitiva es `origin/main`; se conserva también la rama de integración `codex/leveler-beat-zoom`. El checkout principal local, con cambios pendientes, se mantiene intacto: no se fuerza su actualización ni se mezclan sus archivos sin guardar.
- No se han incorporado a Git audio oficial, autorizaciones personales ni resultados temporales.

## Criterio de fin

La ampliación está implementada, validada, publicada y entregada en la carpeta de uso. La rueda desplaza únicamente la vista y Ctrl/Command + rueda conserva el zoom; las pruebas de eventos JavaFX comprueban que el transporte no cambia. La escucha musical por parte del usuario sigue siendo una valoración distinta de las comprobaciones técnicas.

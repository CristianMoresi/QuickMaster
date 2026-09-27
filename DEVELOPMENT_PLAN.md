# QuickMaster — desarrollo y cierre

## Encargo vigente: Leveler macro, contrato revisado

El usuario exige que Amount 100 % iguale el RMS de los pasajes musicales
sostenidos, incluidos intros y breaks musicales. Esto reemplaza la protección
incondicional anterior. Se conserva silencio y microdinámica. Casos de uso,
arquitectura, fuentes técnicas y pruebas independientes en
`docs/diagnostics/macro-leveler-plan.md`. **Implementado y entregado localmente**
el 27-09-2026. Resultados y límites: `docs/diagnostics/macro-leveler-results.md`.

By Now al 100 %: dispersión central RMS de 9,954 a 0,561 dB, 87 ventanas de
3 s con subida >1 dB; al 50 % queda en 5,155 dB. No se afirma igualdad exacta
en transiciones/fades ni escucha humana. 819 tests de aplicación y 129 DSPark
aprobados; 26 sondas funcionales instaladas (269 tests reejecutados), 48 casos
macro y tres sondas de rendimiento aprobados. Mediana 1,505 s hasta audio con
Leveler aislado y 4,656 s con cadena completa; memoria estable en 30 cambios.

JAR **actual**: `f801964aa7cd6de73999edfa8f5dfb0c5605b781c613aecc58e4df74129a4c04`.
Imagen Java 25 copiada en `C:/Program Files/QuickMaster`, 201 archivos idénticos
y EXE con arranque limpio. Respaldo: `C:/Program Files/QuickMaster-backup-20260927-224205`.
Checkout principal preservado; no se ha hecho push, release ni creado otra rama.

## Registro anterior: diagnóstico de la auditoría posterior a 1.3.1

Este bloque registra el estado previo al reemplazo macro descrito arriba.

**REABIERTO — Leveler no aceptado como producto.** El usuario confirma que la
primera estrofa de By Now sigue sin nivelarse. Reproducido sobre el mismo JAR
instalado: ganancia exactamente cero hasta 175 s; solo −0,313162 dB en el bloque
final al 100 %. El criterio anterior de «alguna muestra cambia» no cubría esta
necesidad y se retira el cierre de producto. Diagnóstico y evidencia:
`docs/diagnostics/leveler-product-failure-20260927.md`.
No se ha modificado la aplicación ni desplegado otra corrección en este diagnóstico.

Las correcciones A01–A16 están entregadas en Program Files. Suite limpia:
798 tests de aplicación, 129 de DSPark, 106 variantes musicales y 94 lecturas
oficiales aprobados; 23 sondas funcionales instaladas y las tres de rendimiento
aprobadas. Se conserva el alcance limitado de cada prueba, no se afirma
perfección absoluta ni escucha humana. Plan, cobertura, resultados y recibos:
`docs/diagnostics/product-audit-20260927.md`.

JAR instalado en aquella auditoría (ya sustituido):
`cb874287c83acc4a3f8a6cce582cca3f19980d533c6b5b5b76d0fcab917c5564`;
DSPark Java 0.2.1. Portable Java 25 de 201 archivos, todos idénticos al instalado,
arranque limpio del EXE como usuario normal. Respaldo:
`C:/Program Files/QuickMaster-backup-20260927-210305`.
Fuentes en `E:/Code/Projects/JA-DAW/QuickMaster-Integration`, sobre `0981c71`;
se conserva intacto el checkout principal con sus cambios previos. No se crea
otra rama, no se hace push ni se modifica la release pública 1.3.1.

Recheck final aislado: mediana de ajuste de Leveler 1,713 s (tres repeticiones),
cadena completa 3,566 s hasta audio, p95 observado 3,813 s (veinte ajustes).
Treinta ediciones: 1278,485→1278,502 MiB retenidos tras GC del harness, sin OOM;
PCM publicado idéntico a referencia fría. Este cache cuesta ~210 MiB más que la
línea base para esta pista: no se afirma menor RAM global ni respuesta instantánea.

## Línea base publicada — registro histórico de 1.3.1

La publicación y entrega descritas abajo preceden a la auditoría y mantienen
su evidencia original; sus hashes y dependencias no son los instalados actuales.

Actualizado: 2026-09-27, 09:42. Leveler, optimización P1 y DSPark Java 0.2
**entregados y comprobados en Program Files**. Suite completa, portable, copia
de los 201 archivos por hash, arranque normal y aceptación instalada aprobados.
La autorización «Continúa» permitió resolver la cancelación anterior de UAC.
Este era el registro de entrega previo a la auditoría; no se utilizan skills de orquestación.
Tras la autorización `releasepush`, **1.3.1 publicada** a las 09:41:35 CEST sobre
el commit `77cf486`, subido a `main`. Los tres paquetes se comprobaron antes de
publicar. El ZIP de Windows contiene la imagen local de 201 archivos validada con
Temurin 25, no la imagen alternativa de CI con Java 21. Registro de publicación:
`docs/releases/1.3.1-validation.md`. Solo existe la rama `main` y se conserva
Cristian Moresi como único autor y committer de estos cambios.

## Rendimiento y DSPark — entrega completada

- Diagnóstico reproducido en el JAR entregado: 12,282 s de cadena/medición con
  todos los módulos apagados; carga asíncrona con solo Leveler 16,246 s y un
  cambio de Leveling 7,347 s. Seis ajustes seguidos agotan el heap de 4 GB del
  proceso de prueba en el análisis multibanda apagado. No se tocó audio del usuario.
- En la versión diagnosticada, los trabajos obsoletos no se cancelaban y cada uno conservaba instantáneas y buffers.
  La FFT y el detector true peak son también puntos calientes del port Java.
- DSPark C++ remoto inspeccionado en `9330f1c`, con cambios de 1.8.0 y posteriores;
  P0 utilizaba Java 0.1.0. La entrega actual incorpora Java 0.2.0, con fuentes
  versionadas y oráculos C++/DFT. No se modifica el checkout C++ ni se afirma
  paridad de funcionalidades con toda la biblioteca nativa.
- Plan y evidencias: `docs/diagnostics/performance-and-dspark-plan.md`.
  Orden: limitar trabajos/memoria y omitir cálculo inútil; acelerar disponibilidad
  del audio y reutilización; actualizar el port con equivalencia y trazabilidad;
  suite completa y entrega portable de cada cambio de aplicación.
- P0 implementado: análisis de salida serializado y cancelable, cola de último
  ajuste, omisión de análisis/render bypass salvo metadatos de controles y sin
  clon PCM en cada gesto. Incluye cierre del worker y clave de fuente exacta
  para la caché tonal. Corregida también la curva de fade omitida en snapshots.
- P0 entregado el 27 a las 03:48:28: 705 pruebas, cero fallos/errores/omisiones,
  106 variantes musicales, paquete con 94 mediciones oficiales aprobadas.
  JAR instalado `b01ed4faf56ac0a275b59a560bef6572378ae29306195b9ff16d5cf8c1fdd5c3`.
  Copia portable de 201 archivos, hash verificado y arranque normal limpio.
- UI real: cambio de Leveling pasa de 7,347 s a mediana 3,988 s (tres procesos).
  Las tres ráfagas que antes agotaban 4 GB terminan con un worker y el audio
  correcto del último ajuste. Cuatro configuraciones conservan audio bitexacto.
  Todo apagado pasa de 12,282 a 1,856 s; cadena completa activa aún 22,636 s.
- Sobre Program Files vuelven a pasar By Now original/+4 dB/0 %, protección,
  carga asíncrona UI y waveform zoom/pan sin mover reproducción. Respaldo:
  `C:/Program Files/QuickMaster-backup-20260927-034821`.
- P1 implementado, validado y entregado: plan de audio antes
  de estadísticas, controles de limitadores sin remapeo FX, cargas/análisis de
  fuente acotados y protegidos contra resultados obsoletos, render sin doble
  buffer entero ni asignación por bloque, multibanda por bloques FFT óptimos y
  mapas de picos streaming, caché de características con SHA-256 de toda la
  entrada. Auto EQ ya no acepta una huella parcial que omitía muestras y tasa.
- DSPark Java 0.2: FFT Stockham/doble precisión, bajada polifásica, latencia de
  bloques pequeños, suavizado, campana Vicanek (sin alterar presets RBJ), exp/log,
  true peak optimizado con cola finita. 122 pruebas de biblioteca pasan; 9566 bins
  y 240 campanas se contrastan con C++ compilado. Suite limpia completa aprobada:
  715 pruebas, cero fallos/errores/omisiones en 102 informes, 106 variantes
  musicales y 94 lecturas oficiales en el paquete reabierto. Las 170 clases
  finales coinciden con las del benchmark. Entrega y QA instalada aprobadas.
  Detalle: `docs/diagnostics/dspark-java-0.2-migration.md`.
- Rendimiento P1, By Now/280 s y heap 4 GB: Leveler solo, audio preparado en
  mediana 1.742 s por ajuste (estadísticas 2.504 s; antes 7.347 s). Cadena completa,
  veinte ajustes: mediana 3.496 s, p95 3.580 s hasta audio; resultado final
  bitexacto a controlador frío. Treinta ediciones con memoria retenida estable,
  sin OOM. Configuración y límites en `performance-p1-validation.md`.
- JAR final `ec80915fe0e5fc773de33fffde3eb82f9ccaf640e0f38ee2fc137f7c112ca73a`;
  portable de 201 archivos en `dist/performance-p1-fixed-20260927/QuickMaster`.
  Validado con Temurin 25.0.4.7; contiene únicamente DSPark 0.2, hash `5a9e6d8e…`.
- A las 06:12 se canceló UAC y se conservó P0. Tras nueva autorización del usuario,
  entrega completada a las **09:10:57**, `DEPLOYED_AND_VERIFIED`. Los 201 archivos
  instalados coinciden con la imagen; JAR `ec80915f…` y DSPark `5a9e6d8e…`.
  Respaldo recuperable: `C:/Program Files/QuickMaster-backup-20260927-091050`,
  que conserva el JAR P0 `b01ed4fa…`. No se ha usado instalador ni borrado audio.
- Arranque adicional del EXE instalado como usuario normal a las 09:11:40–41:
  JavaFX, controlador y restricción nativa inicializados sin errores. Solo se
  cerraron los procesos propios de comprobación. Recibo y log en
  `docs/diagnostics/performance-evidence/p1-deployment-20260927.json` y
  `p1-installed-startup-20260927.txt`.
- Las pruebas de UI/Beat antes externas están ahora en `tools/diagnostics`,
  con instrucciones locales. No se depende de carpetas de orquestación.
- QA final del portable terminada: cuatro canciones reales, carga fallida/cierre,
  Leveler UI con corrección real y 0 % idéntico, waveform zoom/pan y Beat Comp
  aprobados; capturas inspeccionadas y WAV intactos. Todo repetido sobre los JAR
  instalados. Las capturas instaladas de Leveler y zoom son byte-idénticas a las
  de la imagen validada. Beat conserva el límite de −1 dB y el mismo hash de PCM
  con bloques 997/4096/65536. Carga fallida, Stop pendiente y cierre pasan.
- Cadena completa instalada, un ajuste y seis rápidos: audio listo en 3.736 y
  3.583 s desde el último gesto; sin OOM ni trabajos de salida solapados. El
  resultado final es bitexacto a un controlador nuevo (cero diferencias crudas).
  Recibo final: `performance-evidence/p1-installed-acceptance-20260927.json`.
- La entrega local precede a la autorización `releasepush`; la publicación de
  estas correcciones se registra por separado en `docs/releases/1.3.1.md`.
- Evidencia actual: `docs/diagnostics/performance-p1-validation.md`;
  historial P0: `docs/diagnostics/performance-p0-validation.md`.

## Release 1.3.1

### Publicación corregida (27 de septiembre, 09:41 CEST)

- Release pública y marcada como última: https://github.com/CristianMoresi/QuickMaster/releases/tag/v1.3.1
- Tag `v1.3.1` sobre `77cf4864c51a55b4662340964a2fe763838d5629`; workflow
  `36303709090` aprobado en las tres plataformas. Núcleo exacto `ec80915f…`.
- ZIP Windows x64 descargado de GitHub idéntico al portable instalado y probado;
  su EXE descomprimido vuelve a arrancar limpiamente. Linux x64 y macOS arm64
  comprobados por contenido, hashes, runtime, permisos y arquitectura, sin
  afirmar pruebas interactivas nativas en esas plataformas.
- Se adjuntan los tres portables, el JAR central y `1.3.1-SHA256SUMS.txt`.
  Todas las sumas se verificaron contra descargas del borrador antes de publicarlo.
- Se conserva localmente el ZIP Windows de CI sustituido antes de la publicación;
  no se han borrado audio, respaldos ni cambios del checkout principal.

### Historial: corrección entregada localmente (27 de septiembre)

- Trabajo autónomo sobre el Leveler general, sin ajustes por nombre de canción,
  timestamps particulares ni skills de orquestación. La entrega anterior no
  constituía evidencia insuficiente; en esa fase no se preparó otro release.
- Implementados: corrección del falso macro-build-up por mesetas; comparación
  complementaria de distribuciones de arreglo para cuerpos de distinta duración;
  conservación de protección en bordes con fragmentos residuales; diagnóstico
  de UI que distingue nivelación efectiva y abstención.
- Las pruebas nuevas detectaron y permitieron corregir una regresión real:
  un outro repetido seguido de un solo frame silencioso perdía su protección.
- El candidato modifica el audio original de By Now, Billie Jean y Wicked Game.
  El ensayo de +4 dB inyectados exclusivamente en memoria reduce el desnivel en
  las cuatro canciones ensayadas, incluido Quiet Gold con margen previo.
  Esto no equivale a una escucha humana ni a reconocimiento perfecto de intención.
- Verificación: 696 casos en 97 informes, 0 fallos pendientes, 0 errores y 0
  omisiones; 106 variantes musicales aprobadas. La ejecución completa detectó
  tres oráculos obsoletos del test del procesador: se corrigieron las expectativas
  y se repitieron sus ocho casos, sin cambiar código de aplicación. El detalle
  está en `docs/diagnostics/leveler-correction-validation.md`.
- Paquete definitivo generado y reabierto con conformidad `PASSED`, 94 mediciones.
  Las 166 clases son byte-idénticas al candidato usado en los ensayos reales.
  JAR: `1cc8d872b1436f9554473429c5a77644cc8e1184662db63cb8d3fa980ec96524`.
  Imagen de 201 archivos: `dist/leveler-fixed-20260925/QuickMaster`, Temurin
  25.0.4.7, sin instalador. Validación previa `IMAGE_VALIDATED`.
- Tras la cancelación de UAC del 25 de septiembre, el usuario autorizó reintentar.
  Entrega completada el 27 a las 02:40:55: `DEPLOYED_AND_VERIFIED`, 201 archivos
  y hash instalado 1CC8… idéntico al paquete. Respaldo recuperable:
  `C:\Program Files\QuickMaster-backup-20260927-024046` (JAR F1207…).
  Arranque adicional como usuario normal a las 02:41:32–33 sin errores.
  Solo se cerraron los procesos propios de comprobación.
- Pruebas repetidas sobre el JAR de Program Files: By Now original cambia
  8.255.793 muestras, ganancia mínima −0,313163 dB; el desnivel artificial de
  +4 dB baja de 4,962374 a 2,515670 LU. Regiones protegidas exactas, 0 % idéntico
  y WAV original intacto. La carga asíncrona de UI adopta generación 2 y produce
  el mismo render. Zoom, desplazamiento sin tocar transporte y UI de tempo pasan.
  Evidencia: `docs/diagnostics/by-now-evidence/*20260927*`.
- La imagen portable final también supera la carga asíncrona real de By Now,
  adopción de generación 2, render con 8.255.793 muestras modificadas y control
  al 0 %. Evidencia: `dist/leveler-verification-20260925/ui-final-image.log`.
  Quiet Gold original conserva una restricción independiente: supera 0 dBTP y
  la protección de picos rechaza el plan; no se ha debilitado esa salvaguarda.
- Diseño y límites: `docs/diagnostics/leveler-correction-plan.md`.
  Reproducción con JAR real: `tools/diagnostics/LevelerCorpusAcceptance.java`.

### Historial de la retirada

- **RETIRADO por indicación del usuario.** Eliminados el borrador de GitHub, sus
  cuatro adjuntos y el tag `v1.3.1` local/remoto. No llegó a publicarse ni a
  copiarse a Program Files. Código conservado en `main` (`eedf835`) y artefactos
  locales conservados. En ese momento el último release público era `v1.3.0`.
- **Regresión funcional confirmada en «By Now» el 25 de septiembre.** El JAR entonces instalado devolvía
  `STRUCTURAL_READY` con una curva completamente plana: 0 muestras modificadas
  de 26.880.002. Corregida y entregada localmente el 27 antes de la publicación
  posterior autorizada mediante `releasepush`.
  Diagnóstico: `docs/diagnostics/by-now-leveler-2026-09-25.md`.
- Preparación desde `origin/main`, sin crear ramas adicionales. La antigua rama
  `codex/leveler-beat-zoom` fue eliminada localmente y en GitHub por indicación
  del usuario; el worktree de integración se conserva en HEAD separado.
- Versión Maven y changelog actualizados a 1.3.1. Notas públicas en
  `docs/releases/1.3.1.md`; procedimiento en `docs/VALIDATION.md`.
- El empaquetado multiplataforma usa el JAR validado localmente y comprueba su
  SHA-256, versión e informe de conformidad ligado a sus propias clases. No se
  suben corpus oficiales ni autorizaciones a GitHub.
- Build limpio 1.3.1 completado: 677 pruebas, 0 fallos, 0 errores y 0 omitidas
  en 94 informes; 106 variantes musicales aprobadas. Conformidad de archivo
  ITU/EBU `PASSED`, 94 mediciones. Las 165 clases de producto son idénticas a
  la entrega anterior: cambia la versión y el empaquetado, no el DSP.
- JAR de release: `25f11f5681fdfb470a9ddd705507e42d2cf01a308fac8eb69592979461726770`.
  Imagen Windows local en `dist/release-1.3.1/QuickMaster`, generada con Temurin
  25.0.4.7; EXE con versión de archivo y producto 1.3.1.
- El verificador de release acepta el JAR validado y rechaza por separado
  hash incorrecto, versión incorrecta y evidencia de sonoridad ausente.

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

- Única rama: `main`. Worktree de integración conservado en HEAD separado:
  `E:\Code\Projects\JA-DAW\QuickMaster-Integration`.
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
- La publicación definitiva es `origin/main`; no existen ramas auxiliares. El checkout principal local, con cambios pendientes, se mantiene intacto: no se fuerza su actualización ni se mezclan sus archivos sin guardar.
- No se han incorporado a Git audio oficial, autorizaciones personales ni resultados temporales.

## Criterio de fin

La regresión del Leveler, la revisión de Beat Comp, zoom/pan, la optimización P1
y la migración auditada DSPark 0.2 están implementadas, probadas y entregadas
localmente. Program Files contiene el portable final y supera la aceptación
instalada. No queda trabajo obligatorio de este alcance pendiente de entrega.
No hay nuevo release, commit ni push. La escucha humana y el reconocimiento
infalible de intención musical no se sustituyen ni se afirman mediante estas pruebas.

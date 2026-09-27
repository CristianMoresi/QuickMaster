# P1: validación y entrega (2026-09-27)

**Entregado y comprobado en Program Files el 27 de septiembre a las 09:10:57.**
JAR instalado `ec80915f…`, idéntico al paquete validado; aceptación instalada PASS.
La cancelación de UAC de las 06:12 se resolvió tras nueva autorización del usuario.
No hay release, commit ni push nuevos. No skills/agentes de orquestación.

## Implementación

- Publicación del plan actual antes de estadísticas; cada entrega comprueba
  generación/cancelación. Los medidores parados muestran pendiente mientras se
  calculan los nuevos resultados. Reproducción solicitada espera al plan y Stop
  cancela esa espera; se evita iniciar otro análisis sobre procesadores vivos.
- Carga y tempo/onsets: un worker propio con una sola petición pendiente,
  objetos TrackAnalysis separados, rechazo de resultados obsoletos, conservación
  del BPM manual vigente y cierre cancelable. No copia PCM completa en FX.
- Render offline: reutilización de bloque, escritura directa de la ventana
  compensada de latencia, sin segundo buffer de salida completo. Un contrato
  opcional de tamaño de bloque permite usar el hop óptimo de la FFT multibanda;
  no cambia AudioProcessor, las clases congeladas del Leveler ni el buffer live.
- Multibanda: picos enlazados por bloques, sin materializar cuatro pistas estéreo.
  Mismo eje causal del crossover; comparación de mapas con splitWhole a 44.1/48/96k.
- Limitadores: petición de UI de coste constante, adopción explícita sin remapeo
  incluso apagados, medidores ligados al ajuste realmente publicado. Una sola
  caché de características por instancia, compartida sin copia; SHA-256 sobre
  todas las muestras/tasa/canales invalida cambios de cualquier etapa anterior.
  No se conserva un PCM extra por cada etapa ni una caché histórica creciente.
- Auto EQ: huella de todas las muestras y tasa; una modificación fuera de los
  512 puntos de la antigua huella ya no puede reproducir una salida obsoleta.
- DSPark 0.2: ocho archivos de núcleo cambiados; fuentes, pruebas, referencia
  C++ y límites en `dspark-java-0.2-migration.md`. 122 pruebas de biblioteca pasan.

## Estado exacto de pruebas

1. `p1-full-package.log`: intento completo **interrumpido**, PACKAGE_EXIT=1.
   Se detectó en revisión el remapeo FX de un snapshot apagado con picos previos.
   No se cuenta como suite aprobada. Corregido con adopción no-remap y regresión.
2. `p1-focused-package.log`: selección de tests nuevos, limitadores y guardas
   aprobada, paquete y 94 lecturas oficiales PASS. **No es la suite completa**.
   JAR candidato `4a9dc924f3ea49c8d86534c8507abf8bea24948ed4c6f59cedc0080a0610499e`.
   DSPark `5a9e6d8e3797bc55d4dcbf462927db6918176c5f06beb942c9ecc2e271d2edc0`.
3. SourceAnalysisRaceProbe sobre clases compiladas: última carga/formato, dos
   ediciones rápidas, TrackAnalysis independiente, BPM manual 143, Stop revoca
   reproducción pendiente y cierre no publica después. Debe repetirse instalado.
4. InteractionLatencyProbe, candidato empaquetado, By Now intacto, heap 4 GB:
   solo Leveler, cambio 2.457 s hasta estadísticas / 1.664 s hasta audio; ráfaga
   2.487 / 1.773 s desde último gesto. Audio bitexacto a referencia fría, 8255770
   muestras modificadas. Una ejecución preliminar, no percentiles.
5. Primera comparación full-chain falló: el harness activaba los procesadores
   directamente pero no todos los checkboxes de clip/normalización. El preset
   frío no describía la misma configuración. No es aceptación válida. Se ha
   corregido el harness y añadido comprobación de toggles y JSON de preset.
6. Full-chain corregida: carga 15.542 s, gesto 4.475 s hasta estadísticas /
   3.698 s hasta audio; ráfaga 4.292 / 3.561 s. No OOM, un trabajo de análisis;
   dos hilos vivos corresponden a salida y fuente (este último inactivo después
   de la carga), no dos cálculos de salida solapados. La cadena viva completa
   coincide **bit a bit** con controlador/preset fríos; 26880002 muestras cambian.
   Retraso máximo FX observado 38.519 ms en carga y 28.106 ms en ráfaga.
7. `p1-full-package-final.log`: segunda ejecución detenida con salida 1 tras
   detectar dos oráculos históricos incompatibles con la migración. No pasa.
   FiniteTruePeakStreamTest esperaba la antigua medición sin cola de DSPark;
   ahora conserva expresamente el testigo sin cola (0.7042242288589478) y exige
   0.7410990583266539 en la medida finita, sin cambiar tolerancia ni núcleo Leveler.
   HannBitIdentityTest conserva igualdad exacta; su mutante float de comparación
   dejaba de distinguirse en la señal corta al cuantizar a 16 bits con la nueva FFT.
   HannMutationProbe localiza una señal determinista más larga (8k mono, 24007
   frames), que vuelve a matar ese mutante en short 74/37 (-10057 frente a -10056).
   Nueve tests focales pasan. No se ha modificado producción por estos dos casos.
8. Cuatro canciones reales pasan con el JAR candidato: By Now, Billie Jean,
   Wicked Game y Quiet Gold. Las tres primeras corrigen el original; Quiet Gold
   original se abstiene por pico inseguro y su prueba positiva usa margen solo
   en memoria. Originales intactos, zonas protegidas exactas, 0 % idéntico.
9. `p1-full-package-qualified.log`: detenido al reproducir mediante el harness
   de UI un bloqueo en un camino de error de carga. Tampoco cuenta como suite
   completa. La biblioteca se había reconstruido con hash idéntico.
10. Candidato empaquetado: SourceAnalysisRaceProbe, LevelerUiAcceptance y
    WaveformUiProbe pasan; capturas inspeccionadas. La captura Leveler muestra
    plan listo mientras las estadísticas siguen pendientes, como está previsto.
11. PackagedDspCompatibilityProbe contrasta P0 con P1 mediante PCM generado,
    44.1/48/96k, mono/estéreo y EQ de fase mínima/lineal: doce casos aprobados.
    Incluye Auto EQ, EQ con campana +2.5 dB, fades, Dynamics, ambos clips,
    multibanda y normalizador. Excluye broadband porque su alineación/cola de
    true peak se corrigió intencionalmente. Máximo error absoluto 8.345e-7 y
    RMS 8.732e-8, por debajo de límites prefijados 1e-5/1e-6. No exporta audio.
12. Revisión de carga fallida: cancelar el análisis de una edición y después
    fallar al abrir el nuevo archivo dejaba la canción retenida sin plan vigente.
    SourceAnalysisRaceProbe --failure lo reproduce con timeout (log red-corrected-
    harness; un primer harness tenía un cast KeyFrame/Timeline incorrecto).
    Corregido el callback de error: reanaliza el archivo retenido antes del diálogo,
    con generación y PCM actuales y sin perder BPM manual. La misma prueba pasa
    después (SOURCE_FAILURE_RECOVERY_PASS) y 29 tests focales pasan.
13. Beat Comp, candidato y Quiet Gold: 82.25 BPM, confianza .65, 737 ataques;
    objetivo -1 dB, 0 excesos en 19765741 muestras por encima del umbral de
    medición. Tres bloques 997/4096/65536 conservan el hash PCM histórico exacto.
14. Iniciado `p1-full-package-delivery.log`, clean package completo tras la
    recuperación de carga fallida. No editar producción/tests/configuración
    hasta que termine. Pendiente repetir la recuperación con el JAR entregado.
15. Barrido de memoria separado, candidato, By Now, cadena completa y heap 4 GB:
    treinta ajustes completos más seis rápidos. Checkpoints con GC explícito
    exclusivamente en el harness, fuera de los intervalos de edición; ~1067 MiB
    retenidos estables. Este ensayo comparte CPU con la suite: **sus tiempos no
    son el benchmark final**. No se añade GC explícito al producto. Log local
    `p1-memory-sweep.log`; un intento de arranque con argumentos PowerShell mal
    citados se conserva aparte como error de invocación, no resultado de producto.
16. `p1-full-package-delivery.log` termina toda la suite: 715 casos, 714 aprobados,
    un fallo y cero errores/omisiones; las 106 variantes musicales pasan. El
    fallo restante era PeakNormalizerTest: pedía pico de muestra = objetivo,
    aunque el normalizador es de pico verdadero. Su señal de dos frames tiene
    overshoot en la cola FIR. Se conserva la señal y se exige ahora el máximo
    independiente `.97216796875*L0 + .1373291015625*L1`, la ganancia exacta y
    pico verdadero de salida = objetivo usando el kernel literal independiente.
    No se toca producción ni se relaja tolerancia. Primer recheck incluyó por
    error las pruebas oficiales sin rutas; su fallo de configuración se conserva.
17. `p1-tail-qualified-package.log`: recheck con rutas oficiales y paquete PASS,
    JAR `9ab67a155d9e381e0323845abd0f8396dc15f2fe2eb52246113d96900356f7d2`.
    Se miden latencias aisladas con este paquete y se repetirá a continuación
    la suite completa con todos los oráculos corregidos antes de entregar.

18. Mediciones aisladas terminadas con `9ab67a15…`: tres procesos por modo,
    veinte ajustes adicionales de cadena completa y tres renders por etapa.
    Todas las comparaciones frías de la cadena completa exigen ahora también
    cero diferencias de bits crudos (incluido el signo del cero): pasan las cuatro.
    Iniciado `p1-full-package-approved.log`, sin filtro de tests, después de
    terminar los benchmarks. Código de aplicación y tests congelados en esta pasada.
19. `p1-full-package-approved.log` termina con **BUILD SUCCESS**, salida 0,
    a las 06:09:20, tras 27:56 min. **715 pruebas en 102 informes: cero fallos,
    errores y omisiones**. Las 106 variantes de la matriz musical pasan;
    el JAR reabierto declara `PASSED`, 94 mediciones oficiales. La biblioteca
    tiene además 122 pruebas aprobadas en 32 informes.
20. JAR definitivo:
    `ec80915fe0e5fc773de33fffde3eb82f9ccaf640e0f38ee2fc137f7c112ca73a`.
    Sus 170 clases son byte-idénticas al candidato de los benchmarks (`9ab67a15…`).
    Verificador de versión/hash/conformidad aprobado. Dependencias runtime nuevas
    desde Maven: una sola versión de DSPark, 0.2, con hash auditado `5a9e6d8e…`.
21. Imagen portable de 201 archivos, Temurin 25.0.4.7, generada y validada a las
    06:10:23 en `dist/performance-p1-fixed-20260927/QuickMaster`. No instalador.
    La llamada normal `Start-Process -Verb RunAs` para el despliegue devuelve
    «El usuario ha cancelado la operación» antes de ejecutar el script elevado.
    No se copió la imagen ni se creó un nuevo respaldo. La instalación permanece
    en `b01ed4fa…`. El recibo `target/deployment-result.json` sigue siendo
    **IMAGE_VALIDATED**, no `DEPLOYED_AND_VERIFIED`. No se ha reintentado UAC.
22. La QA que ya estaba en curso sobre la imagen definitiva termina aprobada:
    cuatro canciones reales, recuperación tras carga fallida y cierre, waveform
    con zoom/pan sin tocar reproducción, UI asíncrona de By Now y Beat Comp sobre
    Quiet Gold. By Now cambia 8.255.793 muestras desde la UI; 0 % sigue siendo
    identidad. El defecto +4 dB pasa de 4.962374 a 2.515670 LU. Beat mantiene el
    hash PCM histórico y cero excesos en 19.765.741 muestras. Capturas finales
    Leveler y zoom inspeccionadas; `git diff --check` sin errores. Los WAV siguen
    intactos. Ningún proceso propio de prueba ni QuickMaster permanece abierto.
23. Tras «Continúa», nueva elevación normal autorizada y entrega completada a las
    09:10:57: **DEPLOYED_AND_VERIFIED**, 201 archivos y hash instalado `ec80915f…`.
    Comparación adicional del árbol completo, verificador de versión/conformidad
    sobre el JAR instalado y arranque normal del EXE a las 09:11:40–41 pasan.
    Log nuevo sin ERROR/SEVERE; solo se cierra el proceso propio. Respaldo P0
    `C:/Program Files/QuickMaster-backup-20260927-091050`, hash `b01ed4fa…`.
24. Aceptación repetida sobre `C:/Program Files/QuickMaster/app/*`: cuatro canciones,
    Leveler UI/0 %, carga fallida y cierre, waveform/tempo, Beat en tres tamaños
    de bloque y cambios rápidos de cadena completa: todo PASS. Las capturas
    instaladas de Leveler y zoom son byte-idénticas a las de la imagen revisada.
    Cadena activa: audio disponible en 3.735815 s tras un ajuste y 3.583118 s desde
    el último de seis; estadísticas en 4.512529 y 4.311088 s. Un trabajo de salida
    máximo, retraso FX máximo 28.175 ms y 1068.886 MiB retenidos tras GC del harness.
    Referencia fría de controlador nuevo: **cero diferencias de bits crudos**.
    Estos son un recheck instalado, no nuevos percentiles. Fuentes WAV intactas.

## Rendimiento final del candidato

By Now intacto, 280 s, 48 kHz estéreo; Ryzen 9950X3D, Temurin 25.0.4.7,
heap de prueba 4 GB, APPDATA aislado. No suite concurrente durante estas medidas.
Tres procesos independientes por modo, con JFR `profile`; mediana en segundos:

«Cadena completa» activa los módulos con los controles iniciales del controlador
en un perfil de usuario aislado y cambia Leveling mediante sus listeners reales.
No representa todos los ajustes posibles de EQ/clip/limitación ni un render a 16x.
La distribución alterna Leveling .4/.7 y la ráfaga termina en .8.

| Interacción real de UI | Audio preparado | Estadísticas terminadas |
|---|---:|---:|
| Carga, solo Leveler | 8.062 | 8.855 |
| Un ajuste, solo Leveler | 1.742 | 2.504 |
| Seis ajustes, solo Leveler, desde el último gesto | 1.737 | 2.437 |
| Carga, cadena completa | 14.608 | 15.387 |
| Un ajuste de Leveling, cadena completa | 3.875 | 4.637 |
| Seis ajustes, cadena completa, desde el último gesto | 3.566 | 4.311 |

La carga completa varía entre 14.968 y 18.218 s hasta estadísticas: no se promete
una carga instantánea. El diagnóstico anterior medía 7.347 s por ajuste con solo
Leveler y 16.246 s para su carga. La disponibilidad de audio no incluye latencia
del dispositivo, ni es una medición de reproducción física. Retraso máximo FX
observado en los seis procesos: 40.833 ms; un solo trabajo de salida simultáneo.

Distribución adicional de veinte ajustes completos, sin GC forzado entre ellos:
audio mediana **3.495691 s**, p95 **3.580453 s**, máximo **3.657607 s**;
estadísticas mediana **4.232079 s**, p95 **4.294128 s**, máximo **4.402576 s**.
p95 por rango más próximo (posición 19 de 20 ordenadas), no estimación poblacional.
Tras la ráfaga final, el audio coincide bit a bit con otro controlador nuevo y
el mismo preset, incluyendo broadband. No se ha acelerado a costa de un bypass.

Renders de cadena (más medición final, sin carga/UI), medianas de tres procesos:
todo apagado **1.169222 s**, Leveler caliente **2.147634 s**, cadena activa
**14.186824 s**; las líneas base eran 12.282 / 6.075 / 23.063 s respectivamente.
Todas las curvas de fade se conservan en snapshots. Los hashes del WAV fuente
coinciden antes y después; no se exporta audio.

## Memoria y alcance de la medición

- Después de GC explícito únicamente en el harness y fuera de los intervalos
  cronometrados: 338.409–338.493 MiB con solo Leveler; 1068.631–1070.657 MiB con
  cadena completa, antes de construir el controlador frío de comparación.
- Barrido separado de treinta ediciones completas: checkpoints 1066.958–1066.966
  MiB, sin tendencia creciente; ráfaga posterior 1066.968 MiB. Esta prueba corrió
  junto a la suite y no aporta tiempos comparables al benchmark aislado.
- JFR registra máximos de heap ocupado cercanos a 4 GB, incluso después de
  algunas colecciones jóvenes. Eso no es memoria viva tras una colección
  completa ni RSS: también incluye buffers transitorios y el oráculo frío.
  No se declara una reducción del pico total de RAM basándose en estos datos.
- Ninguna de las seis ráfagas, el barrido de treinta ediciones o la distribución
  de veinte ajustes produce OOM con el límite de prueba de 4 GB. No es una
  garantía para cualquier duración de audio, máquina o factor de sobremuestreo.

## Entrega cerrada

Los logs/JFR y capturas están en `dist/performance-20260927`; los logs finales
pequeños se conservan también en `performance-evidence`. Suite, paquete, imagen,
copia portable, arranque y aceptación instalada completos. Recibos:

- [Despliegue](performance-evidence/p1-deployment-20260927.json).
- [Arranque normal](performance-evidence/p1-installed-startup-20260927.txt).
- [Aceptación instalada](performance-evidence/p1-installed-acceptance-20260927.json).
- [Identidad de las 170 clases](performance-evidence/p1-final-class-identity-20260927.json).

No hay cambios de aplicación posteriores al build completo: esta continuación
solo entrega, verifica y actualiza documentación. No se ha publicado otro release,
commit ni push. Las pruebas no equivalen a escucha humana ni certificación de
todo EBU Mode; conservan los límites de interpretación ya indicados.

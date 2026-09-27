# Leveler macro — entrega y verificación

**Registro histórico:** estos resultados pertenecen al motor bidireccional de
la entrega del 27-09-2026. El contrato posterior exige subida solamente, sin
atenuación global, y exclusiones manuales. Véase
[el registro vigente](upward-leveler-exclusions.md); no utilizar los valores de
true peak o ganancia negativa de este documento para describir esa revisión.

## Resultado y alcance

**Entregado localmente el 27-09-2026**, con el contrato revisado de
[casos de uso y arquitectura](macro-leveler-plan.md). El Leveler visible ya no
depende de encontrar estribillos equivalentes: nivela los pasajes musicales
sostenidos aunque cambien de timbre o función. A 100 % también corrige intros,
breaks y outros musicales; a porcentajes intermedios reduce proporcionalmente
su contraste en dB. No hay reglas específicas para By Now.

La compilación está copiada en `C:/Program Files/QuickMaster`, no solo en
`target`. Suite completa, imagen Java 25, arranque del EXE y aceptación sobre
el JAR instalado aprobados. No se ha publicado una release ni hecho push.
Los originales de las cuatro canciones no se han modificado ni redistribuido.

Esto **no equivale a RMS idéntico en cada instante ni a perfección universal**.
La aceptación distingue macro RMS, transitorios, transiciones y nivel residual.
No se ha realizado escucha humana ni validación acústica del dispositivo físico.

## Evidencia musical del instalado

Cuadrícula independiente: ventanas RMS de 3 s, paso de 1 s, entrada mayor que
−50 dBFS. No se usa la segmentación ni la máscara de actividad del motor para
elegir las ventanas. Se registran todas, incluidos los extremos. P90−P10 describe
la dispersión central, no el rango completo. Amount 100 %, Speed 50 %:

| Fuente original | P90−P10 entrada | P90−P10 salida | Ventanas con subida >1 dB | Rango completo de salida |
| --- | ---: | ---: | ---: | ---: |
| By Now | 9,954282 dB | **0,561364 dB** | **87 de 278** | 7,480587 dB |
| Quiet Gold | 6,723545 dB | 0,756863 dB | 11 de 202 | 19,910786 dB |
| Billie Jean (80s Glam Metal) | 5,207575 dB | 0,342538 dB | 12 de 215 | 0,937415 dB |
| Wicked Game (80s Synthwave) | 5,436618 dB | 0,523466 dB | 118 de 305 | 14,155989 dB |

En By Now, P95−P05 pasa de 13,820587 a **1,086509 dB**. Al 50 %, P90−P10
queda en **5,154539 dB**: aproximadamente la mitad del contraste original.
El true peak de salida al 100 % es −0,008690 dBTP. Las 87 ventanas se solapan:
no son 87 secciones musicales distintas.

Las mayores diferencias residuales no se ocultan. En By Now están en el tramo
muy débil alrededor de 170 s y transiciones alrededor de 56–57 s. Los extremos
de Quiet Gold (199–201 s) y Wicked Game (303–304 s) corresponden a finales/fades
muy bajos. La actividad, el límite de corrección y el suavizado impiden prometer
que esos extremos alcancen exactamente el RMS objetivo.

La matriz instalada de **48 casos** (cuatro canciones × Amount 25/50/75/100 %
× Speed 0/50/100 %) pasa los controles de contraste y true peak. Al 100 % se
exige P90−P10 ≤1,5 dB y ≤35 % del original. A intensidades parciales se exige
una reducción proporcional con tolerancia explícita de 1,5 dB; además, en By Now
a partir de 50 % se exigen al menos cinco ventanas con subida >1 dB. Esto
complementa los oráculos sintéticos más estrictos, no los sustituye.
[Resultados de las 48 combinaciones](macro-leveler-evidence/matrix-metrics.json).

### Audio realmente publicado y medidor

La carga asíncrona real por FXML/MainController da **el mismo resultado musical**
en el PCM publicado: P90−P10 = 0,561364 dB y 87 ventanas positivas. El PCM
coincide bit a bit con el procesador adoptado. A 0 %, los **26.880.002 samples**
de By Now coinciden bit a bit con la fuente.

En una prueba de transporte real con dispositivo de salida simulado, ventanas
de 1 s en 10/20/40 s reciben +4,128861 / +3,527258 / +1,912028 dB; en
80/130/180/200/240 s hay reducciones de más de 1 dB. El medidor AnimationTimer
se contrasta con la curva en la posición real del reproductor, no con una
posición impuesta manualmente ni con el promedio de una ventana diferente.
Se verifican cuatro ventanas positivas y cinco negativas. No se confunde este
backend simulado con haber escuchado una tarjeta de sonido física.

La captura revisada muestra el diagnóstico y ajuste global sin solapamientos.
El medidor a cero en la captura parada no se utiliza como prueba de playback.
[UI/PCM](macro-leveler-evidence/functional/leveler-ui.txt),
[transporte/medidor](macro-leveler-evidence/functional/leveler-meter.txt),
[captura](macro-leveler-evidence/functional/leveler-ui.png).

## Arquitectura entregada

- `MacroLevelerProcessor` es el único Leveler del controlador, snapshots y
  adopción del producto. El motor estructural anterior permanece para regresión
  histórica; sus pruebas/certificados no acreditan el contrato musical nuevo.
- Potencia media enlazada por canales, sin suma mono que cancele señales en
  antifase. Bloques de 100 ms con peso real de frames, también al final.
- Cambios persistentes de nivel delimitan contextos de estimación. Nunca son
  condiciones para permitir corrección. Una curva continua cubre crescendos y
  pasajes sin fronteras claras; no hace falta etiquetarlos como verso/estribillo.
- Referencia robusta P75 de potencia musical a 3 s, independiente de Speed.
  Ganancia ideal = Amount × (referencia − nivel) en dB. Speed controla el
  contexto macro de 6–2 s y las transiciones suavizadas.
- Curva inmutable de aproximadamente 10 puntos por segundo, interpolación
  continua y misma ganancia en ambos canales. En By Now: 2.802 puntos para
  13.440.001 frames; el detector no retiene PCM ni una envolvente por muestra.
- Seguridad sobre el render float con FIR true-peak DSPark y su cola. Si falta
  margen, atenuación global y nueva comprobación; no clipping, limitador rápido
  ni veto selectivo de subidas que destruya la convergencia.
- Cancelación, publicación generacional y referencia fría verificadas. El audio
  final tras una ráfaga de cambios coincide bit a bit con un controlador nuevo.

El suelo residual es max(−65 dBFS, P90 de potencia de 100 ms −35 dB), con rodilla
de 6 dB. La corrección ideal está limitada a ±24 dB antes de Amount y del ajuste
global de seguridad. El diagnóstico comunica el límite y la atenuación global:
en By Now al 100 %, −4,962590 dB de ajuste común y tres puntos limitados.

Por ello Amount mide reducción de contraste, **no una subida absoluta monótona
de cada fragmento** si cambia el margen necesario. RMS igual tampoco implica
LUFS igual para espectros distintos. El suelo residual no es un clasificador
semántico infalible de ruido. Estos límites forman parte del contrato público.

## Pruebas y entrega

| Verificación | Resultado |
| --- | --- |
| Suite limpia de aplicación y paquete | **819 tests**, 121 informes, cero fallos/errores/omitidos |
| Nuevos tests específicos macro | **21**, incluidos en los 819 |
| DSPark Java 0.2.1 | **129 tests**, cero fallos/errores/omitidos; sin cambios de biblioteca en esta entrega |
| Regresión histórica musical | 106 variantes PASS; no sustituyen la aceptación macro |
| Lecturas oficiales ITU/EBU | 94 PASS, solo perfil QM-OFFICIAL-LOUDNESS-FILE-V1 |
| Aceptación funcional instalada | **26 sondas PASS**, incluida reejecución de **269 tests** contra el JAR instalado |
| Matriz macro instalada | **48 casos PASS**, cuatro fuentes intactas |
| Cadena adversarial con MacroLeveler | **120 combinaciones PASS**, techo final respetado |
| Memoria y latencia instaladas | Tres sondas PASS; audio final idéntico a referencia fría |
| Imagen Windows Java 25 | **201/201 archivos idénticos** al destino, sin archivos extra |
| EXE instalado | Arranque limpio como usuario normal, registrado |

Las 94 lecturas oficiales no certifican el EBU Mode completo, toda la UI,
toda medida de LRA/true peak ni la decisión musical del Leveler. Las suites
solapadas no se suman como si fueran casos independientes. Los procesos JavaFX
de sonda emiten avisos conocidos de classpath/Unsafe; no hubo fallo de prueba.
El arranque normal del EXE se registra por separado.

Dos defectos detectados durante desarrollo quedaron cubiertos antes del paquete:
ruido residual a −60 dBFS que inicialmente subía 6,168 dB, ahora **0,000 dB**;
y una muestra final ponderada indebidamente como 100 ms, que introducía
−0,234 dB en un tono estable, ahora dentro de **0,005 dB**. Dos suites anteriores
fueron interrumpidas para corregirlos; no se contabilizan como aprobadas.
La ejecución final completa terminó con BUILD SUCCESS a las 22:41:06.

JAR final (build e instalado):
`f801964aa7cd6de73999edfa8f5dfb0c5605b781c613aecc58e4df74129a4c04`.

DSPark:
`4f8759e3334ce1970382076cfe2015c44dd7f1378f625eeff831e70915fd4382`.

Imagen: `dist/macro-leveler-20260927/image/QuickMaster`.
Respaldo anterior intacto:
`C:/Program Files/QuickMaster-backup-20260927-224205`.

La copia se realizó con elevación normal del sistema. Se lanzó el EXE instalado
y se comprobó arranque a las 22:42 y de nuevo como usuario normal a las 22:43.
El proceso oculto propio de esa segunda comprobación, sin audio cargado, se
terminó tras no disponer de ventana visible para CloseMainWindow. El recibo
no afirma un cierre normal que no se haya observado.

## Rendimiento observado, sin promesas de inmediatez

Misma pista de 280 s / 48 kHz estéreo, JDK 25, heap de sonda 4 GB. Tres cambios
medidos por caso, sin GC forzado en las sondas de tiempo; los medidores de
salida terminan aproximadamente otro segundo después de disponer del audio.

| Caso | Mediana hasta PCM disponible | Rango observado |
| --- | ---: | ---: |
| Nuevo Leveler aislado | **1,504514 s** | 1,151609–1,599656 s |
| Cadena completa nueva | **4,656398 s** | 4,431907–4,863739 s |
| Cadena anterior, repetida en esta sesión | 4,635117 s | 4,605788–4,667380 s |

No se presenta como mejora de latencia de toda la cadena: la diferencia mediana
es ~0,5 %. El antiguo dato histórico de 3,566 s no es un control contemporáneo;
por eso se volvió a ejecutar el JAR de respaldo con su sonda versionada. Tres
repeticiones no justifican percentiles de cola ni una comparación estadística
fuerte. La cadena actual sí contiene el Leveler macro actuando, comprobado.

El perfil separado de etapas, sin caché tonal de la UI, registra análisis
Leveler caliente de 0,398 s nuevo frente a 0,819 s anterior. En cadena completa
fría registra 7,971 s de render total nuevo frente a 13,582 s anterior;
Auto EQ y limitación multibanda siguen siendo costes importantes. No se mezclan
esos tiempos de render frío con los de ajustes interactivos con caché.

Treinta ediciones con toda la cadena, en prueba de memoria separada:
**1275,486 → 1275,503 MiB** retenidos entre primera y última repetición;
1275,506 MiB después de la ráfaga final. Sin OOM, máximo dos workers y un trabajo
de análisis contabilizado; el PCM final tiene cero diferencias de bits frente
al render frío. Con Leveler aislado: 494,981 MiB retenidos. El GC forzado pertenece
al harness de memoria, no se ha añadido al producto.

[Mediciones de tiempo](macro-leveler-evidence/performance/timing-summary.json),
[memoria](macro-leveler-evidence/performance/memory-30.txt).

## Evidencia reproducible y estado durable

- [Suite completa](macro-leveler-evidence/full-suite.txt) y
  [DSPark](macro-leveler-evidence/vendor-tests.txt).
- [Resumen funcional instalado](macro-leveler-evidence/functional/summary.json),
  [matriz](macro-leveler-evidence/matrix-summary.json) y
  [rendimiento](macro-leveler-evidence/performance/summary.json).
- [Recibo de entrega](macro-leveler-evidence/deployment-result.json),
  [201 hashes de imagen](macro-leveler-evidence/image-integrity.json),
  [arranque normal](macro-leveler-evidence/normal-startup-result.json),
  [fuentes congeladas](macro-leveler-evidence/source-freeze.json).
- El directorio de evidencia contiene logs de texto, métricas y captura, **no
  audio privado**. Los 48 logs completos adicionales permanecen en el directorio
  local indicado por matrix-summary.json.
  Las copias de texto versionadas normalizan espacios finales y EOF cuando
  hace falta; los logs crudos originales permanecen intactos bajo dist.
- Repetición instalada: `tools/Verify-InstalledAudit.ps1`, modos Functional,
  MacroMatrix y Performance, con ExpectedJarSha256 y PrivateAudioDirectory
  explícitos. Nunca usar target/classes como sustituto del JAR entregado.
- Desarrollo en `E:/Code/Projects/JA-DAW/QuickMaster-Integration`; checkout
  principal y cambios previos del usuario preservados. La publicación pública
  1.3.1 y main remoto no se han modificado en este encargo.

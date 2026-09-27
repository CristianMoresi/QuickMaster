# Leveler: aceptación musical fallida y diagnóstico reabierto

Registro histórico del diagnóstico del motor estructural. Posteriormente el
usuario cambió explícitamente el contrato: a 100 % también se igualan intros y
breaks musicales. Las condiciones de protección más abajo describen el encargo
anterior, no la aceptación vigente. Véanse `macro-leveler-plan.md` y
`macro-leveler-results.md` para el reemplazo entregado y su evidencia instalada.

27-09-2026. El usuario informa de que By Now sigue sin nivelar la primera
estrofa, mucho más baja, y no ve movimiento en el indicador durante su prueba.
Se retira la conclusión previa de producto terminado. No se ha cambiado DSP,
instalado otro binario ni publicado una release durante este diagnóstico.

## Identidad y reproducción

- Instalado: `C:/Program Files/QuickMaster/app/quickmaster.jar`, SHA-256
  `cb874287c83acc4a3f8a6cce582cca3f19980d533c6b5b5b76d0fcab917c5564`.
- Fuente: By Now.wav, 48 kHz estéreo / 280 s, SHA-256
  `dcfb61d5d419bf6044bb0dd42fd7639659f33564c4b7ef9219cbf66798bf8e91`.
  El log normal de la aplicación confirma que se cargó ese archivo tras la
  entrega. No es una explicación basada en un ejecutable antiguo.
- `LevelerSongDiagnostic` al 100 % / Speed 50 %: conformidad autenticada PASSED,
  STRUCTURAL_READY, 19 segmentos. **Objetivo cero para todos los segmentos
  anteriores a 175 s**. La única corrección es −0,313162 dB en 175–261 s,
  con rampas; después vuelve a cero.
- Regiones estables 7–17 s (−18,933 LUFS) y 37,5–54,5 s (−16,756 LUFS)
  quedan NOT_COMPARABLE. Las referencias adicionales solo agrupan los cuerpos
  61,5–112,5 / 116,5–156 / 175–261 s, entre −12,085 y −11,003 LUFS.
  La primera estrofa no es corregida: no es solo un indicador detenido.

## Separación entre DSP y medidor

Nueva sonda `tools/diagnostics/LevelerPlaybackDiagnostic.java`: FXML real,
carga asíncrona, listeners reales de controles y reproducción, mismo AudioPlayer
con su AnimationTimer original. Solo se sustituye la fábrica de dispositivo
por un SourceDataLine simulado; no hay escucha humana ni prueba de DAC.
La lectura del Label se hace después de pulsos naturales, sin llamar a la
actualización del medidor ni modificar manualmente la posición publicada.

| Tiempo muestreado | Ganancia del PCM publicado | Indicador animado |
|---|---:|---:|
| 10, 20, 40, 80, 130 s | 0,000000 dB; cero muestras cambiadas | +0,0 dB |
| 180, 200, 240 s | −0,313162 dB | −0,3 dB |
| 270 s | 0,000000 dB; cero muestras cambiadas | +0,0 dB |

Se confirma el fallo funcional en la primera parte. En este recorrido controlado
sí se mueve el indicador al final; no se ha reproducido todavía el cero en toda
la canción bajo la configuración exacta de la sesión del usuario. No se debe
contradecir esa observación ni presentar el pequeño cambio tardío como solución.

## Causa y fallo de aceptación

El diseño restringe la corrección a conjuntos con referencias consideradas muy
similares. `BodyContextGate` rechaza las parejas estables de esta canción por
contexto; `ArrangementReferencePlanner` solo recupera tres cuerpos posteriores.
El resto no recibe referencia ni ganancia aunque sea música sostenida más baja.
La zona muerta y la ponderación de confianza reducen la única diferencia final
a ~0,31 dB. No es un fallo de autorización normativa ni de carga de la canción.

`LevelerCorpusAcceptance --positive` exigía únicamente algún cambio de muestras
en el original. La prueba +4 dB seleccionaba una región que el propio algoritmo
ya reconocía. Ambas pueden pasar mientras una estrofa completa queda sin tratar.
La sonda de UI verificaba identidad entre el PCM publicado y ese mismo resultado,
pero no la idoneidad musical del resultado. Son comprobaciones útiles de
integridad, **no una aceptación suficiente de un nivelador**.

## Condiciones de la corrección pendiente

1. Revisar la generación de referencias y el tratamiento de cuerpos/estrofas
   sin réplica espectral casi idéntica. No introducir excepciones por nombre,
   hash o minuto de una canción en el código de aplicación.
2. Separar protección de intro/break/outro/crescendo de una simple diferencia
   de instrumentación o nivel. No convertir toda la canción en una sonoridad
   plana para que un medidor se mueva.
3. Definir aceptación por secciones y ganancia útil antes/después, con referencias
   independientes de las regiones que el candidato decida seleccionar; incluir
   estrofa baja, variaciones de interpretación y negativos de dinámica intencional.
4. Repetir PCM publicado, indicador durante playback, límites de pico,
   estéreo, 0 %, controles y corpus. Solo tras ello, suite completa y entrega
   obligatoria. El número de tests aprobados no cierra este defecto.

Logs sin audio: `dist/leveler-reopened-20260927-212713/by-now-sections.log` y
`by-now-meter.log`; copias en `leveler-product-failure-evidence/`.
El instalado permanece sin cambios y el fallo sigue abierto.

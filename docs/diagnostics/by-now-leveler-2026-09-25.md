# Leveler inactivo en «By Now»: reproducción y diagnóstico

Registro histórico del fallo original. La corrección general está implementada
y entregada el 27 de septiembre; véase `leveler-correction-validation.md`.
Los resultados siguientes pertenecen al JAR anterior, no al candidato corregido.
El release 1.3.1 y su tag se han retirado. La aplicación entonces instalada se
conserva en un respaldo; no se cerró ninguna sesión abierta por el usuario.

## Identidad y reproducción

- Archivo: `D:\StyleShift AI\SSAI Masters\POST-YOUTUBE\By Now.wav`.
- SHA-256 antes y después: `dcfb61d5d419bf6044bb0dd42fd7639659f33564c4b7ef9219cbf66798bf8e91`.
- 48 kHz, estéreo, 13.440.001 frames; 280,000020833 s.
- JAR probado: `C:\Program Files\QuickMaster\app\quickmaster.jar`.
- SHA-256: `f1207aaa1a4cf7c638f8b9e3453038a31bf6e024260d5d398b53c135e86f16f6`.
- Reproducción independiente de UI y de otros módulos: Leveler activado,
  Leveling 100 %, Speed 50 %, análisis y procesado del archivo completo en bloques
  de 4096 frames. No se escribe audio. La escala y el recorte de contraste se
  realizan exclusivamente en memoria.

## Resultados

| Ensayo | Estado | Curva | Muestras modificadas | Ganancia aplicada |
|---|---|---|---|---|
| Original, Speed 50 % | STRUCTURAL_READY | plana | 0 / 26.880.002 | 0 dB |
| Escala 0,5 en memoria, Speed 100 % | STRUCTURAL_READY | plana | 0 / 26.880.002 | 0 dB |
| Original sin el último frame, solo en memoria | STRUCTURAL_READY | plana | 0 / 26.880.000 | 0 dB |

La conformidad ligada al JAR es `PASSED`. La seguridad de pico devuelve `PROVEN`:
entrada original −0,100000 dBTP y entrada escalada −6,120600 dBTP. Por tanto, en
este caso no bloquean ni la validación de sonoridad ni la seguridad de pico.

## Cadena causal

1. La segmentación produce 19 regiones. De 171 pares, 96 se rechazan por
   protección, 69 por ineligibilidad del cuerpo y 6 por contexto incompatible.
   No queda ningún par aceptado ni referencia de nivel: todos los objetivos son 0 dB.
2. Solo las regiones 2, 8, 10 y 12 superan la elegibilidad individual. Sus seis
   pares se rechazan en `BodyContextGate.compare`, antes de comparar contenido.
   El filtro exige signos y magnitudes de los saltos de sonoridad vecinos y
   novedades de frontera suficientemente parecidos. Esto elimina todos los
   candidatos en esta canción.
3. Hay un defecto concreto en `ProtectionClassifier.macroBuildup`: la actividad
   de cinco regiones alrededor de la región 16 es `[1, 1, 1, 1, 0]`. Cuenta los
   tres incrementos nulos como coincidencias de signo, admite 3 de 4 coincidencias
   y usa el valor absoluto del salto final. La región 16, de 175 a 261 s, queda
   marcada como macro-build-up (bit 128), aunque el cambio está en el extremo
   final de la canción. Retirar únicamente el último frame en memoria elimina la
   región residual y el bit 128 de esos mismos 86 segundos de música.
4. El ensayo anterior no arregla por sí solo el Leveler: la región 16 sigue
   siendo ineligible por el umbral rígido de variación de sonoridad (2,5725 LU,
   aunque su pendiente es 0,0412 LU/s). Su duración de 86 s también incumple la
   relación permitida respecto de las regiones de 51 y 39,5 s. No basta con
   desactivar una protección o aumentar un umbral.
5. `AnalysisDynamicsProcessor.publishStructural` publica `STRUCTURAL_READY`
   incluso cuando el plan es unitario. `MainController` lo convierte en
   «Ready · comparable sections only», ocultando que no hay ninguna corrección.

Contraste adicional: comparar directamente las regiones 10 y 12, saltando solo
el filtro previo para diagnóstico, produce H=0,3701, T=0,2147, A=0,1964 y C=0,2264;
no demuestra comparabilidad suficiente. Las comparaciones 10/16 y 12/16 son
rechazadas por duración. No se propone desactivar salvaguardas ni forzar una
corrección sobre cualquier par: hay que revisar segmentación, ventanas musicales,
criterios de comparabilidad y abstención como un conjunto.

## Por qué la validación anterior fue insuficiente

Las 677 pruebas y 106 variantes musicales pasaron, pero los casos positivos de
nivelación eran sintéticos y acordes con los filtros implementados. El caso real
«Quiet Gold» había demostrado abstención/protección, no corrección positiva. Las
pruebas de waveform tampoco validaban una actuación musical efectiva del Leveler.
Eso no justificaba dar el producto por cerrado. «By Now» prueba la omisión.

## Trabajo identificado tras el diagnóstico

- Convertir este caso en una regresión reproducible contra el JAR empaquetado,
  con tramos de referencia y una expectativa musical explícita de corrección.
- Añadir una regresión mínima del falso macro-build-up causado por una cola de
  un frame, sin alterar intros, breaks y crescendos realmente intencionales.
- Revisar la selección de secciones y el uso de contexto, así como las regiones
  excesivamente largas y las duraciones distintas de repeticiones musicales.
- Distinguir en la UI análisis terminado, abstención sin candidatos y nivelación
  realmente aplicada; registrar la causa de abstención.
- Exigir varias canciones reales con corrección positiva y casos negativos de
  protección, mediciones de ganancia y comprobación en la app entregada antes de
  otra publicación. No sustituir esta aceptación por el número de tests verdes.

## Evidencia local

Código del ensayo: `tools/diagnostics/LevelerSongDiagnostic.java`.
Resultados: `by-now-installed.txt`, `by-now-headroom-fast.txt` y
`by-now-without-last-frame.txt` en `docs/diagnostics/by-now-evidence/`. Código y
evidencia se conservan fuera de `target` para sobrevivir a `mvn clean`; no se han
publicado en GitHub. El WAV original no se modifica ni se incorpora a Git.

Ejemplo de reproducción con un JDK y los JAR instalados:

```powershell
java -Xmx2g -cp 'C:/Program Files/QuickMaster/app/*' `
  tools/diagnostics/LevelerSongDiagnostic.java `
  'D:/StyleShift AI/SSAI Masters/POST-YOUTUBE/By Now.wav' 1.0 0.5
```

# Corrección general del Leveler

Estado: implementado, entregado y verificado localmente el 27 de septiembre.
No autorizado para nueva publicación. Evidencia en `leveler-correction-validation.md`.

## Criterio de producto

Nivelar diferencias sostenidas entre cuerpos musicales comparables, sin exigir
que sean copias sincronizadas de la misma interpretación. No normalizar cada
ventana de sonoridad al mismo nivel. Mantener constantes las ganancias dentro de
las partes, con las rampas suaves y la prueba de pico existentes. Preservar las
trayectorias expresivas y las zonas protegidas.

«By Now» es una regresión de inactividad, no una fuente de umbrales específicos.
No se usan su nombre, duración ni posiciones para decidir ganancias.

## Implementación y aceptación

1. Corregir macro-dinámica: un plateau más un único salto no es una tendencia.
   Regresiones positivas y negativas, incluyendo colas silenciosas residuales.
2. Complementar el reconocimiento de repeticiones ordenadas con comparabilidad
   de arreglo: distribuciones de forma espectral, contraste y contenido tonal,
   independientes del nivel absoluto y de la duración de una repetición.
   Exigir acuerdo entre los rasgos, soporte temporal, cuerpo estable y grupos
   de enlace completo; no conectar partes por un único vecino intermedio.
3. Mantener los vetos de protección y de trayectoria expresiva. La magnitud
   del salto desde un break no determina por sí sola si dos cuerpos son iguales.
   Las referencias no deben modificar el contorno dinámico interno de una parte.
4. Presentar por separado corrección disponible, falta de partes comparables,
   diferencias dentro de tolerancia y corrección limitada por seguridad.
5. Validar de extremo a extremo con material real y con errores de nivel
   conocidos inyectados en memoria, además de pruebas adversarias: intro/break,
   outro, crescendo, otra instrumentación, distinto contenido, duración distinta,
   cambios de interpretación, cuantización, estéreo, controles y pico verdadero.
6. Ejecutar suite completa y paquete con conformidad oficial ligada al código;
   generar imagen portable, copiar carpeta completa, verificar hash, arranque y
   log. No declarar entrega ni publicar mientras falle una regresión relevante.

## Base técnica y límites

- AES, [Loudness Normalization](https://aes.org/resources/audio-topics/loudness-project/loudness-normalization/):
  distingue normalización de archivo y corrección variable; advierte que el
  control de nivel puede contrariar dinámicas intencionales. No prescribe los
  umbrales de segmentación de este producto.
- AudioLabs, [Music Structure Analysis](https://www.audiolabs-erlangen.de/resources/MIR/FMP/C4/C4.html):
  repetición, homogeneidad y novedad son evidencias distintas de estructura;
  una comparación de secuencias casi idénticas no cubre todo el problema.

Los umbrales y la política de referencia son decisiones de ingeniería que deben
superar las pruebas de producto. Estas fuentes no certifican el algoritmo ni
permiten prometer una inferencia perfecta de intención artística.

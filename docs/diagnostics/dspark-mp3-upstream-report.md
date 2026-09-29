# DSPark MP3 — informe para el equipo de desarrollo

Base comprobada: `IO/Mp3File.h` en
`a8556aa7066d52b2aa22b0078cc976f3344a4869` (blob
`4ce4fde5f4abe4bf839b8d74610444c4880ddae0`). Reproducción con MSVC C++20,
`/O2 /fp:precise`, fuente sin modificar. No se ha enviado este informe todavía.

Durante el port Java hemos reproducido estos problemas del decoder MPEG-1:

1. **Clamping destructivo en la síntesis.** La salida se limita a [-1, 1]. Un
   MP3 float puede tener overshoots válidos; un importador de mastering debe
   conservarlos y dejar el techo al procesamiento/exportación. En la señal
   sintética `stereo-48000-headroom`, se recortan 10.014 muestras; diferencia
   máxima 0,159552693 frente a la síntesis sin clamp. La salida sin clamp
   coincide con FFmpeg dentro de 3,160e-6 por muestra.

2. **CRC gapless mono de FFmpeg.** Para algunas cabeceras mono, la posición del
   propio CRC queda dentro de los 190 bytes empleados por FFmpeg. El encoder
   calcula con ese campo todavía a cero; la verificación actual incluye el CRC
   ya escrito. Algunas tramas de metadatos también son menores de 190 bytes:
   el padding virtual es cero, no audio de la trama siguiente. Los fixtures
   `mono-44100-vbr` y `mono-48000-quiet` devuelven respectivamente 19.584 y
   21.888 frames; los resultados gapless correctos son **18.213 y 19.824**.
   Referencia del cálculo del encoder:
   [FFmpeg mp3enc.c](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/mp3enc.c).

3. **Intensity stereo en bandas vacías interiores.** Una banda derecha a cero
   por debajo de otra banda derecha codificada no debe activar intensity.
   Hay que localizar la última banda no nula y, en bloques cortos, hacerlo por
   ventana; también conservar los casos mixed y MS+intensity. El bitstream
   sintético `intensity-long-3-3` no supera full scale y aun así difiere hasta
   **0,453725934** entre C++ actual y el resultado corregido. Este último
   coincide con FFmpeg dentro de **1,297e-6**. Hay 16 vectores long/short/mixed
   para evitar corregir únicamente el ejemplo largo.

4. **Truncamientos aceptados como éxito.** Al quitar el último byte de
   `stereo-48000-cbr.mp3`, C++ informa éxito y 19.824 frames. El importador debe
   distinguir un archivo completo de una trama incompleta; lo mismo se aplica
   a referencias al reservoir no disponible. Para mastering, devolver una
   señal aparentemente completa oculta un daño en el archivo.

Reproducción: generar fixtures con `tools/diagnostics/Generate-Mp3Fixtures.ps1`
y `tools/diagnostics/Mp3IntensityFixtures.java`; compilar
`tools/diagnostics/Mp3CppDecode.cpp` con el include de la revisión indicada;
comparar sus f32le con FFmpeg `mp3float` y los vectores del port. Solo se usan
señales matemáticas propias, disponibles con sus oráculos en el repositorio.

La API pública de QuickMaster se mantiene intacta. Las diferencias locales
están aisladas para incorporar las correcciones que se publiquen en DSPark.
No se solicita cambiar el encoder ni ampliar a MPEG-2/2.5 como parte de estos
cuatro defectos; esos formatos usan una compatibilidad separada en QuickMaster.

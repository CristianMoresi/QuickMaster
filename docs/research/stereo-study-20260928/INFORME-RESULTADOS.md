# No hay una única relación Mid/Side para todas las canciones

Informe de investigación de QuickMaster · 28 de septiembre de 2026

## Resumen de resultados

Se han medido **50 canciones: diez familias con cinco referencias cada una**, con 4 h 8 min 40 s de duración de las fuentes. El estudio cubre la reproducción pública en YouTube, no los masters originales sin pérdida.

- La mediana de Side respecto a Mid va de **−16,18 dB en hip-hop a −2,81 dB en orquestal/cine**: una diferencia de 13,37 dB entre estas selecciones.
- El género tampoco fija una anchura única: en acústico/folk/country, las cinco canciones abarcan **14,19 dB**. Un perfil debe orientar, no imponer el mismo objetivo a todo.
- Hay energía Side grave real en las referencias. Por debajo de 250 Hz representa una mediana del **11,9 % al 55,5 % del Side total**, según la familia. No equivale a un porcentaje de anchura ni demuestra que convenga generar Side nuevo en graves.

**Cinco referencias por familia sirven como punto de partida empírico, no como un estudio representativo ni como prueba de una relación «perfecta».**

## 1. Cuánto Side hay respecto al Mid

Mid = (L + R) / 2; Side = (L − R) / 2. La medida es 10 · log₁₀(energía Side / energía Mid). Un valor más negativo indica menos energía diferencial: −10 dB significa que el Side tiene una décima parte de la energía del Mid. No mide por sí solo calidad, anchura percibida ni seguridad mono.

La tabla muestra la mediana y los extremos de las cinco canciones de cada familia. Cada canción cuenta una vez; una canción larga no pesa más. Son relaciones calculadas agrupando las potencias espectrales de ventanas Hann de 3 segundos, no promedios de dB instantáneos.

| Familia | Mediana S/M (dB) | Mínimo–máximo (dB) |
|---|---:|---:|
| Electrónica | -10,05 | -15,16 a -3,60 |
| Pop | -10,36 | -14,82 a -7,27 |
| Rock | -7,67 | -9,58 a -5,83 |
| Metal | -7,15 | -8,35 a -5,58 |
| Hip-hop | -16,18 | -26,28 a -13,47 |
| R&B / soul / funk | -8,92 | -10,62 a -6,84 |
| Acústico / folk / country | -12,60 | -18,01 a -3,82 |
| Jazz / blues | -8,17 | -8,75 a -6,13 |
| Orquestal / sinfónico / cine | -2,81 | -4,67 a -1,26 |
| Latina | -13,64 | -19,62 a -6,15 |

## 2. Los graves del Side existente no deben eliminarse por defecto

Cada porcentaje indica qué parte de **toda la energía del Side** está por debajo del corte indicado. Las columnas son acumulativas y no se suman. Son medianas entre canciones, no objetivos de diseño.

Para Stereo Image, desmarcar **Generate Low Frequencies** debe impedir la generación nueva en graves, no convertir a mono los graves originales. Este estudio no decide si el corte óptimo es 200, 250 o 300 Hz; esa elección sigue necesitando comparar el generador y sus efectos audibles.

| Familia | Side <200 Hz | Side <250 Hz | Side <300 Hz |
|---|---:|---:|---:|
| Electrónica | 20,6 % | 29,1 % | 35,5 % |
| Pop | 19,0 % | 21,4 % | 23,2 % |
| Rock | 10,8 % | 11,9 % | 19,7 % |
| Metal | 22,0 % | 27,1 % | 34,8 % |
| Hip-hop | 12,6 % | 15,2 % | 21,2 % |
| R&B / soul / funk | 10,1 % | 14,0 % | 21,1 % |
| Acústico / folk / country | 37,0 % | 55,5 % | 58,2 % |
| Jazz / blues | 27,5 % | 36,0 % | 40,9 % |
| Orquestal / sinfónico / cine | 29,6 % | 44,0 % | 48,9 % |
| Latina | 20,2 % | 24,0 % | 27,2 % |

## 3. Datos adicionales para una futura EQ por estilo

También se guardaron espectros L/R/M/S normalizados, variación tonal por ventanas y bloques de 15 segundos, energía por bandas, rango RMS y factor de cresta. La tabla resume la energía estéreo en cinco bandas amplias: primero se normaliza cada ventana válida, se promedia dentro de la canción y después se da el mismo peso a cada canción.

Las bandas tienen anchos distintos: estos porcentajes son **energía acumulada, no altura de una curva EQ ni ganancia recomendada**. Suman aproximadamente 100 % por fila; puede haber diferencias por redondeo. Una futura EQ necesitará usar las curvas finas y su variabilidad, evitar realzar bandas sin señal fiable y conservar la intención del tema.

| Familia | 0–120 Hz | 120–300 Hz | 300 Hz–2 kHz | 2–10 kHz | 10–24 kHz |
|---|---:|---:|---:|---:|---:|
| Electrónica | 46,8 % | 18,0 % | 27,0 % | 7,2 % | 0,9 % |
| Pop | 50,7 % | 16,2 % | 26,9 % | 5,8 % | 0,4 % |
| Rock | 27,4 % | 23,7 % | 33,6 % | 14,6 % | 0,8 % |
| Metal | 33,3 % | 22,5 % | 30,0 % | 13,9 % | 0,5 % |
| Hip-hop | 51,5 % | 16,2 % | 24,3 % | 7,4 % | 0,7 % |
| R&B / soul / funk | 32,7 % | 19,7 % | 41,6 % | 5,7 % | 0,3 % |
| Acústico / folk / country | 34,6 % | 31,1 % | 31,7 % | 2,5 % | 0,2 % |
| Jazz / blues | 35,9 % | 22,9 % | 35,2 % | 5,7 % | 0,3 % |
| Orquestal / sinfónico / cine | 29,9 % | 19,6 % | 47,1 % | 3,4 % | 0,0 % |
| Latina | 35,8 % | 22,0 % | 34,3 % | 7,4 % | 0,5 % |

El rango RMS es P90 − P10 de niveles en ventanas de 400 ms. El factor de cresta compara el pico de muestra máximo de los canales con la potencia estéreo media. Aquí se muestra la mediana entre las cinco canciones. **No son EBU LRA, LUFS ni true peak.**

| Familia | Rango RMS (dB) | Factor de cresta (dB) |
|---|---:|---:|
| Electrónica | 6,01 | 13,98 |
| Pop | 6,42 | 11,53 |
| Rock | 7,45 | 12,98 |
| Metal | 5,95 | 11,81 |
| Hip-hop | 10,56 | 12,95 |
| R&B / soul / funk | 8,64 | 15,02 |
| Acústico / folk / country | 8,06 | 16,36 |
| Jazz / blues | 10,82 | 18,16 |
| Orquestal / sinfónico / cine | 20,30 | 19,18 |
| Latina | 9,70 | 13,12 |

## 4. Las 50 canciones analizadas

Esta es la lista medida, no una selección pendiente. Cada enlace abre la fuente concreta de YouTube. «S/M» usa la misma definición que la tabla de familias; «Side <250 Hz» es la fracción del Side total. La duración corresponde al reproductor de la fuente. Todas pasaron los controles de captura; la cobertura registrada supera el 99,5 %.

Los detalles de edición describen la entrega pública identificada. No certifican que coincida con una edición concreta del master original.

### Electrónica

| Artista — canción / fuente | Duración | S/M (dB) | Side <250 Hz |
|---|---:|---:|---:|
| [Daft Punk — Get Lucky](https://www.youtube.com/watch?v=4D7u5KF7SP8) | 6:10 | -15,16 | 29,1 % |
| [deadmau5 — Strobe](https://www.youtube.com/watch?v=9P5gQXslrR0) | 10:34 | -3,60 | 56,7 % |
| [Disclosure — Latch](https://www.youtube.com/watch?v=VNg3MxYKSi0) | 4:17 | -10,05 | 14,2 % |
| [Pendulum — Watercolour](https://www.youtube.com/watch?v=q9I01iNFcL4) | 5:04 | -13,11 | 15,3 % |
| [Tiësto — Adagio for Strings](https://www.youtube.com/watch?v=PyD4QQgJ6O4) | 7:23 | -8,78 | 30,6 % |

Ediciones identificadas:

- **Daft Punk:** Random Access Memories, album version
- **deadmau5:** For Lack of a Better Name; NOT Club Edit
- **Disclosure:** Latch feat. Sam Smith; verify album in public player metadata
- **Pendulum:** Watercolour Full Version, single edition
- **Tiësto:** Just Be, album version

### Pop

| Artista — canción / fuente | Duración | S/M (dB) | Side <250 Hz |
|---|---:|---:|---:|
| [Ariana Grande — Into You](https://www.youtube.com/watch?v=Faq-ODnfIPw) | 4:04 | -7,27 | 21,4 % |
| [Billie Eilish — bad guy](https://www.youtube.com/watch?v=ZD6rXLXZOEI) | 3:14 | -14,82 | 55,4 % |
| [Dua Lipa — Levitating](https://www.youtube.com/watch?v=OsfAnsMY21M) | 3:24 | -7,55 | 6,6 % |
| [Taylor Swift — Anti-Hero](https://www.youtube.com/watch?v=3YgtjHZyCIQ) | 3:21 | -10,36 | 15,2 % |
| [The Weeknd — Blinding Lights](https://www.youtube.com/watch?v=fHI8X4OXluQ) | 3:23 | -10,38 | 27,1 % |

Ediciones identificadas:

- **Ariana Grande:** Dangerous Woman
- **Billie Eilish:** WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?; solo, not Bieber remix
- **Dua Lipa:** Future Nostalgia; solo version, NOT DaBaby remix
- **Taylor Swift:** Anti-Hero; verify Midnights edition in public player metadata
- **The Weeknd:** Official audio, 2019 XO/Republic; not the music video

### Rock

| Artista — canción / fuente | Duración | S/M (dB) | Side <250 Hz |
|---|---:|---:|---:|
| [AC/DC — Back In Black](https://www.youtube.com/watch?v=9vWNauaZAgg) | 4:16 | -7,67 | 11,9 % |
| [Arctic Monkeys — Do I Wanna Know?](https://www.youtube.com/watch?v=pqrUQrAcfo4) | 4:32 | -8,10 | 11,9 % |
| [Foo Fighters — The Pretender](https://www.youtube.com/watch?v=BMMGwtklEeE) | 4:29 | -5,83 | 24,2 % |
| [Queen — Another One Bites the Dust](https://www.youtube.com/watch?v=b72gdhV_rXM) | 3:35 | -9,58 | 35,1 % |
| [The Killers — Mr. Brightside](https://www.youtube.com/watch?v=m2zUrruKjDQ) | 3:43 | -7,66 | 10,4 % |

Ediciones identificadas:

- **AC/DC:** Back In Black; studio song, not River Plate/Donington
- **Arctic Monkeys:** Do I Wanna Know?; public catalogue song, not music video
- **Foo Fighters:** Echoes, Silence, Patience & Grace
- **Queen:** The Game; 2011 phonogram, Bob Ludwig credit, catalogue publication 2026
- **The Killers:** Hot Fuss; not Thin White Duke remix

### Metal

| Artista — canción / fuente | Duración | S/M (dB) | Side <250 Hz |
|---|---:|---:|---:|
| [Bring Me The Horizon — Can You Feel My Heart](https://www.youtube.com/watch?v=H8Wx8GV1Oiw) | 3:48 | -8,08 | 23,9 % |
| [Gojira — Stranded](https://www.youtube.com/watch?v=zgychWIo6UA) | 4:30 | -7,15 | 30,0 % |
| [Metallica — Enter Sandman](https://www.youtube.com/watch?v=CHIWNDAwTqQ) | 5:32 | -6,78 | 62,0 % |
| [Rammstein — Deutschland](https://www.youtube.com/watch?v=vGtaDvhSxaI) | 5:22 | -5,58 | 27,1 % |
| [Slipknot — Duality](https://www.youtube.com/watch?v=B2lmOei7qfk) | 4:13 | -8,35 | 26,5 % |

Ediciones identificadas:

- **Bring Me The Horizon:** Sempiternal (Expanded Edition)
- **Gojira:** Magma
- **Metallica:** Metallica; studio song, mastering edition to record from player metadata
- **Rammstein:** Rammstein; studio album song, not nine-minute narrative video
- **Slipknot:** Vol. 3: The Subliminal Verses (Special Edition)

### Hip-hop

| Artista — canción / fuente | Duración | S/M (dB) | Side <250 Hz |
|---|---:|---:|---:|
| [Dr. Dre — Still D.R.E.](https://www.youtube.com/watch?v=Qeem6ZVr8Ic) | 4:31 | -15,10 | 12,0 % |
| [Future — Mask Off](https://www.youtube.com/watch?v=AMCwYdTJ_PE) | 3:25 | -26,28 | 14,9 % |
| [Kendrick Lamar — Alright](https://www.youtube.com/watch?v=67vr-3kpX3Q) | 3:39 | -13,47 | 16,8 % |
| [Nas — N.Y. State of Mind](https://www.youtube.com/watch?v=izhKFB0_2w0) | 4:54 | -26,24 | 77,1 % |
| [Travis Scott — SICKO MODE](https://www.youtube.com/watch?v=NQbkGDoD7B0) | 5:13 | -16,18 | 15,2 % |

Ediciones identificadas:

- **Dr. Dre:** Still D.R.E. feat. Snoop Dogg; NOT instrumental
- **Future:** FUTURE; NOT Marshmello remix
- **Kendrick Lamar:** Alright album song; not shortened official video/single
- **Nas:** N.Y. State of Mind; NOT Pt. II
- **Travis Scott:** SICKO MODE studio song

### R&B / soul / funk

| Artista — canción / fuente | Duración | S/M (dB) | Side <250 Hz |
|---|---:|---:|---:|
| [D’Angelo — Untitled (How Does It Feel)](https://www.youtube.com/watch?v=CRKXG5YleHU) | 7:11 | -10,62 | 14,0 % |
| [Frank Ocean — Pink + White](https://www.youtube.com/watch?v=9cHbvRUALrc) | 3:05 | -8,92 | 13,4 % |
| [Silk Sonic — Leave the Door Open](https://www.youtube.com/watch?v=IYFqc9gk4qI) | 4:02 | -6,84 | 16,8 % |
| [Stevie Wonder — Superstition](https://www.youtube.com/watch?v=ftdZ363R9kQ) | 4:26 | -10,26 | 7,8 % |
| [SZA — Good Days](https://www.youtube.com/watch?v=0BdlKkvjEgA) | 4:40 | -7,88 | 18,0 % |

Ediciones identificadas:

- **D’Angelo:** Voodoo, full album track; not music-video edit
- **Frank Ocean:** Pink + White, album song
- **Silk Sonic:** An Evening With Silk Sonic
- **Stevie Wonder:** Talking Book
- **SZA:** Good Days official audio, SOS edition 2022

### Acústico / folk / country

| Artista — canción / fuente | Duración | S/M (dB) | Side <250 Hz |
|---|---:|---:|---:|
| [Bon Iver — Holocene](https://www.youtube.com/watch?v=pvC5YD-IjL0) | 5:37 | -3,82 | 33,0 % |
| [Chris Stapleton — Tennessee Whiskey](https://www.youtube.com/watch?v=l6_w3887Rwo) | 4:53 | -14,83 | 75,5 % |
| [José González — Heartbeats](https://www.youtube.com/watch?v=B-c3PAENnLU) | 2:40 | -18,01 | 64,8 % |
| [Kacey Musgraves — Slow Burn](https://www.youtube.com/watch?v=vYZFN4INkhs) | 4:06 | -8,19 | 45,1 % |
| [Tracy Chapman — Fast Car](https://www.youtube.com/watch?v=IJ8i49EqgYI) | 4:57 | -12,60 | 55,5 % |

Ediciones identificadas:

- **Bon Iver:** Bon Iver, Bon Iver
- **Chris Stapleton:** Tennessee Whiskey, Chris Stapleton, NOT Joshua Patterson cover
- **José González:** Heartbeats, public catalogue song; verify Veneer edition
- **Kacey Musgraves:** Golden Hour; official UMG song with mastering credits
- **Tracy Chapman:** Tracy Chapman, self-titled album

### Jazz / blues

| Artista — canción / fuente | Duración | S/M (dB) | Side <250 Hz |
|---|---:|---:|---:|
| [B.B. King — The Thrill Is Gone](https://www.youtube.com/watch?v=kpC69qIe02E) | 5:25 | -7,77 | 47,8 % |
| [Miles Davis — So What](https://www.youtube.com/watch?v=KJEzFvXx3Xw) | 9:23 | -6,13 | 10,5 % |
| [Norah Jones — Don’t Know Why](https://www.youtube.com/watch?v=GtOcxj3NDBI) | 3:06 | -8,17 | 22,9 % |
| [Stevie Ray Vaughan & Double Trouble — Tin Pan Alley](https://www.youtube.com/watch?v=LNX4Bl4T1q0) | 9:12 | -8,72 | 36,0 % |
| [The Dave Brubeck Quartet — Take Five](https://www.youtube.com/watch?v=ryA6eHZNnXY) | 5:24 | -8,75 | 49,9 % |

Ediciones identificadas:

- **B.B. King:** Completely Well
- **Miles Davis:** So What feat. Coltrane/Adderley/Evans, NOT 1964 live Four & More
- **Norah Jones:** Don’t Know Why; NOT First Sessions Demo
- **Stevie Ray Vaughan & Double Trouble:** Tin Pan Alley, 9:12 version; studio, not 1982 alternate or live
- **The Dave Brubeck Quartet:** Time Out, Columbia/Legacy catalogue

### Orquestal / sinfónico / cine

| Artista — canción / fuente | Duración | S/M (dB) | Side <250 Hz |
|---|---:|---:|---:|
| [Gustavo Dudamel — Danzón No. 2](https://www.youtube.com/watch?v=LnHdDKi6gYE) | 9:46 | -2,81 | 44,0 % |
| [Hans Zimmer — Time](https://www.youtube.com/watch?v=jgyShFzdB_Q) | 4:36 | -2,93 | 46,2 % |
| [Herbert von Karajan — Symphony No. 5 in C Minor, Op. 67: I. Allegro con brio](https://www.youtube.com/watch?v=4Xmg_UVAL2E) | 7:08 | -1,92 | 18,1 % |
| [John Williams — Hedwig’s Theme](https://www.youtube.com/watch?v=wtHra9tFISY) | 5:09 | -1,26 | 25,6 % |
| [Max Richter — On the Nature of Daylight](https://www.youtube.com/watch?v=tUMc_-Bcunk) | 6:12 | -4,67 | 44,5 % |

Ediciones identificadas:

- **Gustavo Dudamel:** Dudamel/Simón Bolívar, Caracas 2008 live; deliberate premeasurement revision of Fiesta draft
- **Hans Zimmer:** Inception original motion picture album, not live Prague/remix
- **Herbert von Karajan:** Karajan/Berliner, recorded 1977; deliberate premeasurement revision of 1963 draft
- **John Williams:** Harry Potter and the Sorcerer’s Stone original soundtrack
- **Max Richter:** The Blue Notebooks (15 Years); chamber original, not orchestra version

### Latina

| Artista — canción / fuente | Duración | S/M (dB) | Side <250 Hz |
|---|---:|---:|---:|
| [Alejandro Sanz — Corazón Partío](https://www.youtube.com/watch?v=mE4Mik_vfJA) | 5:43 | -6,15 | 10,4 % |
| [Bad Bunny — Tití Me Preguntó](https://www.youtube.com/watch?v=juRFjpB5Ppg) | 4:04 | -13,64 | 63,4 % |
| [KAROL G — PROVENZA](https://www.youtube.com/watch?v=i9gxO_5vA-Q) | 3:28 | -19,15 | 43,8 % |
| [Marc Anthony — Vivir Mi Vida](https://www.youtube.com/watch?v=79OxD7KNlYw) | 4:12 | -6,25 | 7,5 % |
| [Shakira — Hips Don’t Lie](https://www.youtube.com/watch?v=nOj6d-HOw2w) | 3:40 | -19,62 | 24,0 % |

Ediciones identificadas:

- **Alejandro Sanz:** Más; not MTV Unplugged
- **Bad Bunny:** Un Verano Sin Ti
- **KAROL G:** MAÑANA SERÁ BONITO; not Sistek remix
- **Marc Anthony:** 3.0; original salsa, not Versión Pop
- **Shakira:** Oral Fixation Vol. 2 Expanded; original Wyclef version, not anniversary

## 5. Método, controles y límites

**Captura.** Reproducción pública normal, a velocidad 1×, en una aplicación aislada y silenciada: estéreo a 48 kHz, ganancia del reproductor constante y comprobación de volumen estable. AGC, cancelación de eco y supresión de ruido desactivados. Sin Spotify, extensiones de Chrome ni audio guardado en la entrega.

**Cobertura.** Se midió prácticamente toda cada canción; se detuvo aproximadamente 250 ms antes del final para evitar el contenido posterior (20 ms en las dos primeras capturas pop). Puede haber un pequeño margen silencioso de encaminamiento. No es una copia PCM exacta de principio a fin. Hay evidencia del reloj cada segundo en 48 capturas y cada 30 segundos en las otras dos. Los intentos con anuncios o deriva de reloj se excluyeron.

**Cálculo.** Ventanas espectrales Hann de 3 s, salto de 1,5 s; dinámica corta de 400 ms con salto de 200 ms. Bandas FFT disjuntas aproximadamente de tercio de octava, con límites adicionales a 120, 200, 250, 300 Hz, 2 kHz y 10 kHz. No son filtros de medida IEC calibrados. Los bloques de 15 s no identifican estrofas o estribillos.

**Verificación.** 50 archivos de resultados, identificadores únicos y diez grupos de cinco. Se comprobaron hashes, controles de calidad, sumas espectrales, todas las características estéreo/tonales y las agregaciones de nuevo. Hay identidad de fuente en las 50 referencias; 49 conservan además el elemento seleccionado del catálogo y una se verificó directamente en la página oficial.

**Límites.** Selección de conveniencia de canciones conocidas, épocas y ediciones distintas, incluida una grabación orquestal en directo. Cinco canciones no estiman la distribución de todo un género. El códec y el remuestreo pueden alterar especialmente los agudos. Los dBFS absolutos del navegador no permiten comparar el nivel de master entre canciones; los cocientes y espectros normalizados sí cancelan una atenuación común constante. No se midieron LUFS integrados BS.1770, EBU LRA, true peak ni coherencia espectral cuadrática. No hubo evaluación perceptiva controlada.

## 6. Qué cambia esto en el diseño de QuickMaster

- **Perfiles orientativos:** usar estos datos como referencias iniciales por familia y mostrar su dispersión; no convertir la mediana en una obligación ni en un límite de seguridad.
- **Adaptación al tema:** combinar el perfil con el análisis de la propia canción y, cuando exista, una referencia elegida. No elevar automáticamente todo tramo estrecho: puede ser una decisión musical.
- **Graves independientes:** separar el permiso para generar Side nuevo en graves de la preservación del Side original.
- **Validación pendiente:** número de bandas, profundidad y velocidad de modulación, saturación del delta y corte de graves necesitan pruebas DSP y de escucha. No se han establecido «sweet spots» mediante este corpus.

**Estado del producto:** este informe entrega la investigación y sus datos. No significa que Stereo Image o una nueva EQ espectral estén implementados ni validados en la aplicación.

## Evidencia reproducible

[Datos íntegros](study.json) · [Verificación](verification.json) · [Resumen técnico](README.md).

SHA-256 del estudio: `8de32115b7fa145329734477fac06c00187b99d4c497d2dfee2374f8cf114add`.

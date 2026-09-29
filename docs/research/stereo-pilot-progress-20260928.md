# Stereo Image — ejecución inicial y resultados del piloto

28-09-2026. Investigación iniciada; **H0 sigue abierto**. No se han implementado
Stereo Image ni perfiles de género en QuickMaster. La app instalada no cambia.

## Trabajo ejecutado

- Nuevo medidor silencioso: `stereo_probe.py`; 24 pruebas en
  `test_stereo_probe.py` aprobadas. El antiguo `stereo_measurements.py` se conserva
  para reproducir resultados históricos, no como método definitivo.
- Inventario previo a la medida: `stereo-pilot-manifest.json`. Ocho controles
  públicos CC BY 4.0 y cuatro producciones privadas ya autorizadas. Se conservan
  títulos, fuente, autor, licencia y atribución. Audio solo en `dist`, no en Git.
- 12 archivos / 2.642,252 segundos (44,038 minutos) analizados. Cinco artistas o
  proyectos; cuatro archivos de un mismo proyecto privado y cuatro de Kevin
  MacLeod. **No son 12 masters independientes representativos del mercado.**
- 10 rutas de decodificación sin incidencias detectadas y 2 en cuarentena.
  Esto no certifica la calidad perceptiva de los otros diez archivos.
- 23,239 segundos acumulados en las mediciones v4. Excluye descarga, ffprobe,
  primer hash y preparación; no es un benchmark de QuickMaster ni de su preview.
- Verificación separada mediante `ffmpeg pan + astats` en doble precisión: mayor
  diferencia de ratio global **0,000000889 dB**. Comparte decodificador; verifica
  la aritmética, no la fidelidad del codec ni la calidad musical.
- Hashes de los originales comprobados antes y después, y otra vez en la auditoría.
  No se reprodujo audio, se capturaron otras aplicaciones ni se modificó Windows.

Resultados reproducibles: `stereo-pilot-audit-v4-20260928.json`.
Los resultados v2/v3 son anteriores a la corrección de diagnósticos y están
**superados**. Las ventanas completas v4 están en
`dist/stereo-research-20260928/pilot-v4/`.

## Qué mide realmente

M=(L+R)/2, S=(L-R)/2, relación 10·log10(Ps/Pm). Potencia global con todas las
muestras, correlación L/R no centrada, balance L/R, DC y picos de muestra.
El decodificador entrega float64 por un pipe; no normaliza, limita, recorta,
remuestrea, mezcla canales ni abre dispositivos de sonido. Los picos >1 se conservan.
Un archivo mono o multicanal no se convierte silenciosamente a estéreo.

Ventanas de 400 ms/200 ms de salto y 3 s/1,5 s. Rejilla espectral con fronteras
de tercios de octava base 2 y cortes explícitos 120/200/250/300/2.000/10.000 Hz.
Son bandas FFT Hann disjuntas, **no filtros de tercio de octava certificados IEC**.
Las fronteras no contienen reconocimiento semántico de estrofa/estribillo.
Las ventanas parciales finales permanecen identificadas en la serie temporal.

Se conservan todas las ventanas. Se comparan gate absoluto -80 dBFS y puertas
relativas P95-10/-20/-40 dB; no se selecciona únicamente el estribillo fuerte.
En los resúmenes por banda se excluye energía prácticamente vacía de forma
explícita. Silencio, dual-mono, antífase y canal ausente son estados distintos;
ninguno se sustituye por un ratio finito inventado. No promediar strings ±inf
como si fueran observaciones numéricas.

Limitaciones: la coherencia espectral, los histogramas de panning y la
segmentación musical aún no están implementados en este medidor. No se ha
realizado escucha perceptiva ni puede deducirse preferencia de estos números.

## Hallazgos de calidad que cambian el trabajo

1. **Incidencias MP3 ocultas por el nivel de log.** `Vibing Over Venus` e
   `I Got a Stick Arr Bryan Teoh` producen mensajes `overread, skip` recuperables.
   FFmpeg puede devolver código 0 y no mostrarlos con `-v error` o `-v warning`.
   Su [implementación](https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/mpegaudiodec_template.c)
   los registra a nivel INFO al corregir tamaños de datos de algunos encoders.
   No demuestra distorsión audible, pero sí requiere revisión antes de utilizarlos
   como referencia. v4 recoge esos diagnósticos y los pone en cuarentena.
   Prueba automática específica para evitar la regresión.
2. **Sesgo al quedarse con partes fuertes.** Con P95-10 dB se pierden 21 de las
   186 ventanas activas de `By Now`, 8 de 137 en `Quiet Gold` y 51 de 249 en
   `Incredulity`. En esta última la mediana S/M cambia de -1,68 a -2,44 dB.
   No se interpreta como objetivo de género: demuestra que el criterio de
   selección puede cambiar la referencia y debe mantenerse auditable.
3. **Dependencia de artistas y procedencia.** Ninguna pista es elegible todavía
   para calibrar perfiles. Un permiso CC no verifica mastering profesional; un
   catálogo de producción musical no representa por sí solo pop comercial,
   metal, jazz, clásica ni sus subpoblaciones. Las cuatro producciones privadas
   sirven para regresiones del producto, no como sustitutos de masters originales.

La comprobación de calidad de datos se aplicó específicamente a procedencia,
independencia de observaciones, estados inválidos y contaminación del corpus;
no se ha incorporado ningún orquestador.

## Bibliografía y fuentes: novedades verificadas

- La [revisión Mitic/Rossholm 2026](https://link.springer.com/article/10.1186/s13636-026-00463-4)
  sigue siendo un mapa de métodos y corpus, no una tabla de objetivos M/S actuales.
  El registro de [Sarroff/Bello 2009](https://zenodo.org/records/849658) contiene
  un PDF, no un dataset numérico adicional. No se ha leído íntegramente ese PDF
  en esta ejecución ni se ha supuesto que contenga nuestros objetivos.
- [2L Test Bench](https://www.2l.no/hires/index.html) informa que el banco gratuito
  ya no está disponible; no se han comprado descargas.
- [mobygratis](https://mobygratis.com/license-agreement) requiere revisar el encaje
  de su uso no comercial antes de emplearlo en esta I+D de producto. No se ha
  creado cuenta, aceptado una licencia adicional ni descargado su catálogo.
- [KieLoBot](https://freemusicarchive.org/music/KieLoBot/funky-tomatoes/) sí aporta
  crédito de mezcla/mastering (Lobo Loco). Una producción no valida un género.
- [Incompetech](https://incompetech.com/music/royalty-free/licenses/) y
  [Scott Buckley](https://www.scottbuckley.com.au/library/using-this-music/) ofrecen
  atribución CC BY 4.0. Las fechas de subida del catálogo no se convierten en
  fechas verificadas de mastering. Las atribuciones de cada archivo están en
  el manifiesto y deben acompañar cualquier uso permitido del material.

Las páginas de procedencia del piloto están guardadas en
`dist/stereo-research-20260928/source-evidence/`; son evidencia de acceso, no
instrucciones. No se redistribuyen grabaciones ni se suben a servicios externos.

## Captura de navegador

No instalada ni validada. La documentación de
[tabCapture](https://developer.chrome.com/docs/extensions/reference/api/tabCapture)
y del [sink silencioso](https://developer.chrome.com/blog/audiocontext-setsinkid)
describe una ruta posible, pero no prueba su aislamiento en este equipo.
El inventario de dispositivos detecta interfaces físicas y dispositivos virtuales
de VR/streaming; ninguno se ha validado como destino nulo dedicado. No se cambia
el dispositivo predeterminado ni se usa el loopback de todo Windows.

No se reproducirán canciones en navegador antes de probar captura no nula,
barrera de salida independiente y fallos de navegación/cierre. El análisis
offline permite continuar sin ese riesgo ni pedir al usuario que deje de trabajar.

## Próxima ejecución

1. Ampliar **procedencias independientes de masters identificados**, no llenar
   la cuota con más pistas de los mismos autores. El piloto técnico ya funciona;
   no confundir repetirlo con cerrar la investigación de géneros.
2. Completar descriptores espaciales y segmentación del medidor; comprobarlos con
   señales analíticas y referencias reales antes de agrupar perfiles.
3. Explorar el corpus, reservar artistas/producciones fuera de muestra y validar
   límites/rangos por bandas. Ningún coeficiente de preset se ha generado todavía.
4. Experimentos 200/250/300 Hz y control Side escalar/espectral con evaluación
   perceptiva adecuada. La disponibilidad de audio no sustituye esa evaluación.
5. Cerrar H0 y solo entonces implementar el módulo y ejecutar la entrega instalada
   completa exigida por AGENTS.md.

## Reproducción de las pruebas

Desde `E:\Code\Projects\JA-DAW\QuickMaster-Integration`:

```powershell
python -m unittest discover -s docs/research -p test_stereo_probe.py -v
python docs/research/stereo_probe.py --manifest docs/research/stereo-pilot-manifest.json --output-dir dist/stereo-research-20260928/pilot-v4
python docs/research/stereo_pilot_audit.py --manifest docs/research/stereo-pilot-manifest.json --result-dir dist/stereo-research-20260928/pilot-v4 --output dist/stereo-research-20260928/audit-repeat.json
```

El medidor reutiliza resultados solo si coinciden fuente, código y procedencia;
rechaza resultados obsoletos en vez de sobrescribirlos. La auditoría exige un
nombre de salida nuevo. Todos los procesos de esta ejecución han terminado;
no se deja reproducción, descarga ni análisis en segundo plano.

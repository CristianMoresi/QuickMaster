# Leveler macro — contrato y plan de desarrollo

Estado: implementado, verificado y entregado localmente el 27-09-2026.
Sustituye el contrato de protección incondicional del Leveler estructural
anterior. Las cinco puertas de aceptación de este plan se han completado;
véanse [resultados, límites y recibos](macro-leveler-results.md). No es una
release pública ni una garantía de perfección universal.

## Qué debe hacer

Automatización offline de ganancia estéreo enlazada, no un compresor de picos.
Con Amount 100 %, los pasajes musicales sostenidos deben converger a un RMS macro
común, aunque cambien instrumentación, armonía, densidad o función musical. Con
Amount parcial se conserva una proporción de su contraste original. Silencio,
ruido residual y los últimos instantes de una cola no son objetivos de normalización.

No puede tener simultáneamente RMS idéntico en cada instante y conservar los
transitorios: el contrato se mide en ventanas de segundos, no muestra a muestra.
Tampoco RMS igual implica LUFS exactamente iguales si cambia el espectro. Se
registran ambas medidas cuando corresponda; la referencia de este control es RMS.

El alcance es una pista mono/estéreo completa. No sustituye la normalización de
sonoridad entre archivos de un álbum, un limitador ni un ajuste independiente de
la voz dentro de una mezcla estéreo. Si la voz baja pero el RMS total permanece
igual por la instrumentación, corregir solo esa voz requiere sus pistas separadas
u otro proceso; este Leveler aplica una ganancia común a la mezcla.

## Casos profesionales y aceptación

| Uso | Resultado exigido | Prueba independiente |
| --- | --- | --- |
| Estrofa más baja que estribillo, sin repetición musical | Ganancia positiva en la estrofa; convergencia al 100 % | Señales de distinto timbre, RMS de ventanas interiores |
| Último estribillo demasiado fuerte | Reducción de su diferencia con el primero | Tres bloques con niveles conocidos |
| Intro, break o outro musical más suave | Corrección proporcional; al 100 % también se nivela | No se usa la posición ni una etiqueta como veto |
| Crescendo o cambio gradual de mezcla | Se reduce la pendiente macro según Amount | Rampa conocida y cuadrícula temporal fija |
| Conservar emoción a intensidad moderada | Amount 50 % reduce a la mitad las diferencias en dB | Identidad de contraste, separada del ajuste global de seguridad |
| Batería, piano y transitorios aislados | No perseguir cada golpe ni recortar picos | Ganancia dentro del golpe, factor de cresta, espectro |
| Música ya estable | Sin modulación significativa ni cambios gratuitos | Señal periódica estable, bypass exacto a 0 % |
| Silencios, pausas, ruido y colas | No elevar silencio/ruido hasta el nivel musical | Ceros exactos, suelo de actividad, señal muy baja |
| Mono, estéreo, canales anticorrelacionados | Misma ganancia para ambos canales, sin cancelación del detector | Potencia por canal, no suma mono |
| Master con poco margen o picos intersample | Seguridad mediante atenuación global, nunca anulando selectivamente las subidas | True peak del PCM final, incluidas las colas del FIR |
| Ajustar Speed | Cambia la escala temporal, no el porcentaje de corrección | Mesetas idénticas, distinta respuesta en transiciones |
| Seek, bloques, sobremuestreo, playback/export | Misma curva en el mismo tiempo de fuente | Render completo contra bloques, posiciones y distintas tasas |
| Cambiar parámetros/audio rápidamente | No publicar curva antigua como nueva ni retener PCM en el detector | Cancelación, forks inmutables, publicación generacional real |
| Archivos cortos, valores inválidos, extremos | Comportamiento explícito, acotado, sin NaN ni cuelgues | Pruebas de límites y entradas degeneradas |

## Arquitectura

1. **Medición**: potencia media de los canales en bloques de 100 ms. Sin mezcla
   mono y sin decisiones basadas en nombres de archivo, estilo o tempo supuesto.
2. **Estructura útil**: cambios sostenidos de nivel y homogeneidad local delimitan
   regiones para evitar mezclar dos mesetas al estimarlas. No se necesita saber
   si son versos o estribillos; no hay requisito de similitud entre ellas. Una
   curva macro continua cubre también crescendos y pasajes sin límites claros.
3. **Referencia**: cuantil robusto de la música activa. Para nivel de entrada
   L(t), referencia T y Amount a, la corrección ideal es a·(T−L(t)) dB. Las
   diferencias restantes son (1−a) veces las originales. Speed determina el
   horizonte de medida y suavizado, no T ni el Amount.
4. **Curva**: puntos de control de baja frecuencia, interpolación continua,
   límites explícitos de corrección y actividad. Un escalar idéntico en ambos
   canales preserva imagen estéreo y microdinámica dentro de cada contexto.
5. **Seguridad**: medir el PCM cuantizado que produce la curva; si falta margen,
   bajar toda la curva por igual y verificar de nuevo. No limitar transitorios
   ni reducir solo las ganancias positivas, lo cual destruiría la convergencia.
6. **Integración**: nuevo procesador macro con publicación inmutable, fuera del
   motor estructural antiguo. El controlador debe usar exclusivamente el nuevo.
   Las pruebas del motor anterior quedan como regresión histórica, no como
   evidencia de que el Leveler visible cumple este contrato. Sus certificados de
   medición tampoco certifican el comportamiento musical nuevo.

No se empieza entrenando un clasificador semántico ni añadiendo excepciones a
By Now. Repetición, homogeneidad y novedad son herramientas de segmentación,
no pruebas de intención artística. Referencias técnicas primarias:
[AudioLabs, estructura musical](https://www.audiolabs-erlangen.de/resources/MIR/FMP/C4/C4.html),
[novedad y límites](https://www.audiolabs-erlangen.de/resources/MIR/FMP/C4/C4S4_NoveltySegmentation.html).
[ITU BS.1770-5](https://www.itu.int/rec/R-REC-BS.1770-5-202311-I) define medición de
sonoridad/true peak, no una política de nivelación musical. La ventana de 3 s es
una escala macro útil también empleada por la medida short-term de
[EBU](https://tech.ebu.ch/loudness); aquí RMS no se etiqueta como LUFS.

El suelo de actividad es el mayor de −65 dBFS y el percentil 90 de potencia de
100 ms menos 35 dB, con rodilla de 6 dB. Es una protección de nivel residual,
no un clasificador infalible de ruido: música extremadamente tenue también
puede quedar fuera y se documenta esta limitación. Una prueba adversarial con
cola de ruido a −60 dB detectó una subida indebida de 6,17 dB con el primer
suelo; la revisión deja esa cola a ganancia unidad. Los límites de corrección
siguen siendo ±24 dB antes de Amount y de la atenuación global de seguridad.

## Orden y puertas de aceptación

1. Pruebas rojas con mesetas distintas, Amount, silencio, cresta, seguridad y
   render; implementar motor, revisar límites y complejidad.
2. Probar By Now y corpus diverso sin modificar los originales. Medir en una
   cuadrícula fija de 3 s, separando música sostenida, transiciones y silencio
   mediante criterios del evaluador, no las regiones elegidas por el motor.
   En mesetas sintéticas, dispersión <= 0,3 dB al 100 %; contraste a 50 % dentro
   de 0,3 dB del esperado. En música real informar todos los percentiles y
   extremos, no esconder transiciones. Exigir varias ventanas positivas >1 dB
   en By Now y reducción sustancial de dispersión macro en todo el corpus.
3. Integrar UI, medidor, lectura diagnóstica, curvas y controles; verificar
   el PCM realmente publicado en reproducción y no solo el analizador aislado.
4. Contrastar rendimiento y memoria con los límites previos, cancelar trabajos
   obsoletos y repetir la referencia fría para detectar caches incorrectos.
5. Suite completa, paquete con mediciones oficiales, imagen Windows Java 25,
   copia a Program Files, comparación de archivos/JAR y arranque limpio del EXE.
   Repetir aceptación macro y de UI sobre esa imagen. Sin release ni push.

Los límites de ganancia, la actividad y las transiciones deben aparecer como
restricciones explícitas cuando impidan la convergencia; nunca ocultarlos tras
un mensaje genérico Ready. Medir no equivale a escucha humana ni permite
prometer perfección universal de una decisión musical automática.

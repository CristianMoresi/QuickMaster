# Stereo Image — investigación y plan de desarrollo

Actualizado: 29-09-2026. Corrección de Generation desplegada y verificada tras el reporte
perceptivo del usuario. Prevalece `docs/diagnostics/stereo-generation-correction.md`:
procesos apagados inicialmente, armónicos automáticos, nuevo rango útil de delta
y primer clic sin bypass oculto. Suite limpia de 902 tests aprobada y nuevo
portable comprobado en Program Files; aceptación sobre el JAR instalado cerrada.
La descripción de la entrega inicial que sigue es histórica, no el estado actual
de los valores por defecto o de la rama de armónicos.

Entrega inicial: investigación de referencias completada (50/50);
módulo implementado, suite completa aprobada y portable desplegado/verificado.
Aceptación adicional sobre cuatro pistas reales del JAR instalado aprobada,
incluidos veinte renders, seis rutas de cadena y estrés de treinta cambios.
Este documento conserva el diseño y las hipótesis de partida; las decisiones
implementadas, pruebas y límites efectivos se registran en
`docs/diagnostics/stereo-image-implementation.md`, que prevalece para el estado
de entrega. No confundir propuestas de monitorización/bandas con funciones ya
implementadas.

Prioridad explícita del usuario: **calidad de sonido > robustez/calidad del código
> eficiencia**. Sus propuestas son guía de diseño, no una obligación de conservar
una solución peor. Cambiar una elección con evidencia y explicar el motivo; no
rebajar calidad para cumplir un benchmark ni declarar óptimos sin pruebas.

Decisiones vigentes: ocho bandas móviles por canal (dieciséis en total), filtro
FIR de fase lineal sobre el delta con límite grave de 150 Hz cuando `Generate Low
Frequencies` esté desmarcado, y objetivos de perfil expresados como porcentajes
de energía Mid/Side que suman 100 %. Estos sustituyen el corte inicial de 300 Hz
y la presentación principal de objetivos en dB. La entrega instalada se verifica
por separado del código fuente.

Actualización de ejecución: `docs/research/stereo-campaign-state-20260928.md`.
Alcance vigente acordado: diez familias generales, cinco grabaciones por familia
(50 referencias). Captura silenciosa completada y verificada. La ampliación tonal pedida
por el usuario se calcula con los mismos poderes espectrales para una futura EQ
por estilo; no añade ese procesador al desarrollo actual ni retrasa la captura.

Revisión posterior de alcance y método:
`docs/research/stereo-corpus-study-plan.md`. Sustituye la lista cerrada de cinco
géneros, amplía el muestreo y contempla captura local estrictamente silenciosa
solo si la bibliografía/datos existentes no cubren lo necesario.
Ejecución inicial: `docs/research/stereo-pilot-progress-20260928.md`.
El piloto de doce archivos fue un control técnico, no cobertura por género.
La campaña final está en `docs/research/stereo-study-20260928/`, con `study.json`,
`verification.json` y 50 registros comprimidos de métricas/procedencia, sin audio.
H0 de medición está cerrado; objetivos empíricos no equivalen a óptimos perceptivos.

Este documento sustituye las propuestas de Spectral Comp, Target Loudness y otros
módulos nuevos. El único módulo nuevo previsto es **Stereo Image**. No elimina
funciones existentes de QuickMaster. No utiliza ningún orquestador.

## 1. Contrato de producto acordado

- Generar diferencias nuevas mediante EQ diferente en L/R, con movimiento continuo
  y suave tanto de frecuencia como de ganancia. Partir de ocho bandas por canal,
  no ocho repartidas entre ambos. No sustituir esto por subir el Side.
- Extraer el delta: señal ecualizada menos señal original, alineadas en tiempo/nivel.
- Aplicar armónicos opcionales únicamente al delta y extraer después su componente
  Side. Añadirlo con polaridad opuesta a L/R, manteniendo el Mid original.
- Regular el Side conjunto, después de añadir el delta, mediante un Side Leveler
  upward/downward con Amount 0–100 %.
- Añadir un Side Guard independiente, posterior, que solo reduzca excesos.
- Decidir si hace falta protección de transitorios después de las pruebas. No
  incluirla activa por defecto ni permitir que vuelva inoperante el generador.
- UI en inglés, respuesta interactiva, waveform procesado y A/B fiel.
- No incorporar por ahora Haas, chorus, microafinación, reverb ni otra capa temporal.
- `Generate Low Frequencies`: checkbox desmarcado por defecto, exclusivo del
  generador. No convertir el grave original a mono al desmarcarlo; no permitir
  que seleccionar un perfil lo active de forma oculta. Filtro final del delta de
  fase lineal, límite grave 150 Hz y compensación del retardo de todas las ramas.
- Los perfiles de género/subestilo se derivarán de un estudio amplio de familias
  y tipos de producción; la lista Electronic/Pop/Rock/Hip Hop/R&B era insuficiente
  y no es definitiva. Mantener `Auto (This Track)` y referencia local.
- Por instrucción posterior del usuario, **cerrar la investigación y calibración
  necesaria antes de implementar el módulo**, no relegarla a después del DSP.
  Los scripts aislados de medición no son desarrollo de la aplicación.

## 2. Resultado de la investigación

### 2.1 No se ha encontrado una relación M/S universal de mastering

Las fuentes primarias revisadas no establecen un número de dB que sea la relación
perfecta para cualquier máster profesional. Sí respaldan referencias contextuales,
dependientes del contenido y de la frecuencia. No convertir consejos de blogs,
estadísticas de muestras pequeñas o porcentajes propietarios en una norma.

- **iZotope, explicación técnica de Ozone 10 (2022):** mide potencias Mid/Side
  en cuatro bandas, con cruces a 120 Hz, 2 kHz y 10 kHz; deriva objetivos de
  referencias o de éxitos por género. Documenta diferencias entre géneros y una
  revisión que dejó de ensanchar bajos prácticamente mono. Es evidencia del método,
  no una tabla de coeficientes de QuickMaster ni una validación de nuestros valores.
  https://www.izotope.com/community/blog/behind-the-technology-of-izotope-ozone-10
- **iZotope Tonal Balance Control 3, documentación actual:** compara anchura con
  referencias y anuncia perfiles de más de 30 géneros basados en másteres. Confirma
  que el enfoque contextual sigue vigente. No tenemos sus datos ni los copiaremos.
  https://www.izotope.com/products/tonal-balance-control
- **STEREOVAULT, manual actual:** los perfiles dependen de género y tipo de fuente;
  admite referencias para comparar la anchura por frecuencia. Sus recomendaciones
  son puntos de partida, no una especificación universal.
  https://www.masteringthemix.com/pages/stereovault-manual
- **Mitic y Rossholm, revisión sistemática (2026):** distingue energía M/S,
  diferencias de nivel/tiempo y coherencia; señala ambigüedades entre medidas.
  Respalda separar los indicadores en vez de tratar correlación como calidad.
  https://link.springer.com/article/10.1186/s13636-026-00463-4
- Un estudio DAFx de 2013 encuentra un óptimo condicionado a su experimento en una
  medida llamada `1-SLR`, no en dB Side/Mid. No usar ese número ni grabaciones
  históricas de ese estudio como objetivo demostrado de mastering actual.
  https://www.dafx.de/paper-archive/2013/papers/04.dafx2013_submission_47.pdf

**Decisión:** utilizar una referencia medible y trazable; no codificar un supuesto
«Side profesional = −8 dB». El objetivo musical es contextual; los límites
técnicos de seguridad son otra cosa. Una media comercial tampoco demuestra
preferencia perceptiva ni causalidad.

### 2.2 Generación y fase

- Apple documenta bandas alternadas L/R y considera 8 suficientes en su algoritmo;
  aconseja evitar el reparto por debajo de 300 Hz. Solo orienta nuestro prototipo.
  https://support.apple.com/en-asia/guide/logicpro/lgcef240df26/10.7/mac/11.0
- NUGEN documenta diferencias espectrales de nivel y modulación suave. Es referencia
  conceptual; Stereoizer no es código disponible para portar. Su sección ITD es
  temporal y no forma parte de la primera versión propuesta.
  https://nugenaudio.com/files/manuals/Stereoizer3%20Manual.pdf
- Melda documenta generación espectral y saturación de la señal expandida.
  https://develop.meldaproduction.com/download/documentation/MStereoSpread_intro.pdf
- La EQ convencional altera fase; la fase lineal añade latencia y puede producir
  pre-ringing. La ausencia de un delay explícito no elimina estos compromisos.
  https://www.fabfilter.com/help/pro-q/using/processingmode
- DAFx 2024 estudia decorrelación y protección de transitorios. Es una alternativa
  para investigar si nuestras pruebas revelan necesidad, no una capa obligatoria.
  https://dafx.de/paper-archive/2024/papers/DAFx24_paper_92.pdf

## 3. Medición y selección del objetivo

### Evidencia empírica disponible para H1

Se midieron las 50 grabaciones seleccionadas, cinco por cada una de las diez
familias: unas 4 h 9 min de duración de referencias. El verificador reconstruye
todas las métricas desde sus archivos de poderes y comprueba hashes, fuentes,
continuidad, volumen del reproductor, ausencia de anuncios en la captura y
agregación por canción. Los intentos fallidos no cuentan. La captura termina
aproximadamente 250 ms antes del post-roll (20 ms en dos referencias iniciales),
con cobertura >99,5 % y un pequeño relleno silencioso documentado. No es PCM
sample-exact del master original, ni se afirma acceso a su versión sin pérdidas.

Tabla descriptiva de **Side respecto a Mid**, no umbrales de calidad. Para cada
canción se agrupan poderes espectrales de ventanas Hann completas de 3 s; después
se calcula mediana y mínimo/máximo entre las cinco canciones, pesándolas igual.

| Familia | Mediana S/M (dB) | Rango observado (dB) |
|---|---:|---:|
| Electronic | −10,05 | −15,16 a −3,60 |
| Pop | −10,36 | −14,82 a −7,27 |
| Rock | −7,67 | −9,58 a −5,83 |
| Metal | −7,15 | −8,35 a −5,58 |
| Hip Hop | −16,18 | −26,28 a −13,47 |
| R&B / Soul / Funk | −8,92 | −10,62 a −6,84 |
| Acoustic / Folk / Country | −12,60 | −18,01 a −3,82 |
| Jazz / Blues | −8,17 | −8,75 a −6,13 |
| Orchestral / Cinematic | −2,81 | −4,67 a −1,26 |
| Latin | −13,64 | −19,62 a −6,15 |

Consecuencias de diseño:

- No aplicar un único objetivo como −8 dB a todos los estilos. La dispersión
  interna también importa: en Acoustic/Folk/Country el rango supera 14 dB.
  Conservar `Auto (This Track)` y `Reference...`, y presentar los perfiles de
  familia como referencias empíricas v1, no como una obligación estética.
- El Side grave original no es un error: la mediana de su fracción bajo 250 Hz
  va del 11,9 % en Rock al 55,5 % en Acoustic/Folk/Country en esta selección.
  Esto respalda preservar el original, NO convertirlo a mono al desmarcar graves.
  Tampoco demuestra que añadir nuevo Side en ese rango mejore el resultado.
- El usuario fija **150 Hz** para la exclusión grave del delta. Sustituye los
  300 Hz sugeridos inicialmente; no se presenta como resultado óptimo del corpus.
  El ancho de transición, rechazo y pre-ringing se contrastan en H2. Los datos
  históricos a 200/250/300 Hz siguen siendo observaciones, no ajustes de producto.
- El perfil debe incluir dispersión y distribución por frecuencia; su mediana
  global no se convierte directamente en un techo duro para Side Guard.
  Los límites de seguridad, el objetivo artístico y Amount siguen separados.
- Cinco canciones cubren el mínimo acordado, pero no son un muestreo aleatorio
  de cada género. Hay grabaciones de distintas épocas y un directo orquestal.
  No afirmar representatividad universal ni una preferencia perceptiva validada.

### Datos adicionales para la futura EQ por estilo

Se conservan espectros L/R/M/S normalizados, medias ponderadas por energía y
medias temporales normalizadas, percentiles, fiabilidad por banda, evolución
cada 15 s, cambio espectral, rango RMS a 400 ms y crest de muestras. Son derivados
de los mismos poderes; no fue necesario volver a reproducir las canciones.
El volumen absoluto del navegador no se compara entre canciones. La codificación
con pérdidas puede afectar especialmente al agudo; una banda casi vacía no es
una instrucción para subir ganancia. No se midieron LUFS, LRA, true peak,
coherencia cuadrática ni etiquetas semánticas de estrofa/estribillo. No confundir
esas magnitudes con los descriptores disponibles. El nuevo procesador espectral
permanece fuera del desarrollo actual; solo se ha preparado su base de datos.

Ampliación y evidencia de esta investigación:
`docs/research/stereo-image-profiles-and-low-band.md`.

### 3.1 Definición única

Usar `M=(L+R)/2`, `S=(L-R)/2`. La UI presenta **fracciones de energía**:

`sideShare = Power(S) / (Power(M) + Power(S))`

`midShare = 1 - sideShare`.

Mostrar `Mid 88.3% / Side 11.7%`, por ejemplo. No llamarlo porcentaje de volumen
percibido ni usar `RMS(S)/(RMS(M)+RMS(S))`: esa sería otra medida. Tampoco son los
porcentajes de Generation o Leveling, que controlan intensidad de procesamiento.
Con silencio total la medida es indefinida: mostrar ausencia de medida, no 0/100.

La representación interna en dB sigue disponible para cálculo y diagnóstico:

`R_b(t) = 10 log10(Power(S_b,t) / Power(M_b,t))`.

Conversión equivalente: `sideShare = 10^(R/10) / (1 + 10^(R/10))`.
Cambiar de unidad no altera la información ni resuelve por sí solo las diferencias
espectrales. Un 60 % Mid / 40 % Side equivale a aproximadamente −1,76 dB S/M;
el ejemplo del usuario no es el objetivo medido de Electronic.

Un valor negativo significa menos energía Side. No mezclar esta convención con
Mid/Side, porcentajes de anchura de otros plugins o RMS/LUFS incompatibles.

- Analizar banda completa y cuatro regiones iniciales: <120 Hz, 120 Hz–2 kHz,
  2–10 kHz y >10 kHz, adaptadas a Nyquist. Estos cruces son una elección inicial.
- Ventanas candidatas: 400 ms para seguimiento y 3 s para contexto; hop 20 ms.
  Side Guard tendrá además un detector más corto, sujeto a calibración.
- Medir también energía L/R, correlación normalizada, concentración espectral,
  ruido/silencio y fiabilidad del denominador. Un canal ausente invalida ciertas
  medidas; no sustituir resultados indefinidos por valores aparentemente seguros.
- No derivar correlación de R salvo bajo sus hipótesis de igualdad de energía L/R.
- Un epsilon numérico evita divisiones inválidas, pero no autoriza subir ruido.
- Conservar datos de entrada, post-generación, post-Leveler y post-Guard separados.

### 3.2 Calibración empírica y procedencia

H0 se cerró con las 50 entregas públicas registradas; la etapa anterior de piloto
no calibra los perfiles. El borrador de 30 familias no está vigente. Cada perfil
se deriva de cinco grabaciones, todas con el mismo peso. No se midieron únicamente
estribillos ni se anotaron secciones semánticas: no presentar estas cifras como
una media de estribillos. La validación de algoritmos con pistas privadas es
independiente del cálculo de referencias por familia.

Por petición del usuario, calcular **primero la fracción energética de cada
canción, después su media aritmética**. No transformar la media/mediana de dB y
llamarla media de porcentajes; tampoco concatenar los cinco archivos, lo que
cambiaría la ponderación. Conservar extremos y fuentes además de la media.

Derivación reproducible: `node docs/research/stereo_energy_profiles.mjs`.
Pruebas: `node --test docs/research/stereo_energy_profiles.test.mjs`.
Salida: `docs/research/stereo-study-20260928/energy-profiles.json`, con hash del
estudio, definición, cinco fuentes por perfil, media y extremos sin redondear.
Las pruebas contrastan poderes espectrales originales frente al cociente en dB,
conservación Mid+Side=100 %, invariancia de ganancia común y corrección Side-only.

| Profile | Mid medio | Side medio |
|---|---:|---:|
| Electronic | 88,26 % | 11,74 % |
| Pop | 89,85 % | 10,15 % |
| Rock | 85,35 % | 14,65 % |
| Metal | 83,72 % | 16,28 % |
| Hip Hop | 97,97 % | 2,03 % |
| R&B / Soul / Funk | 88,18 % | 11,82 % |
| Acoustic / Folk / Country | 89,51 % | 10,49 % |
| Jazz / Blues | 85,85 % | 14,15 % |
| Orchestral / Cinematic | 64,90 % | 35,10 % |
| Latin | 90,98 % | 9,02 % |

Son referencias empíricas, no porcentajes de calidad certificada. El procesamiento
usa precisión completa; el redondeo solo pertenece a la UI.

### 3.3 Perfiles y significado de las cifras

- Los nombres de género seleccionan una distribución de referencia, no una
  ganancia Side fija ni una cifra supuestamente perfecta para todos sus temas.
- El perfil seleccionado fija como objetivo global su porcentaje medio medido.
  No sustituirlo silenciosamente por el estribillo más fuerte o por un objetivo
  `Auto`. La información por bandas aporta diagnóstico y protección. Cualquier
  adaptación espectral que cambie el objetivo efectivo debe ser explícita.
- Guardar por banda mediana, dispersión, número de artistas/producciones, época,
  procedencia y versión del método. No contar ventanas solapadas como canciones
  independientes. Mantener Hip Hop y R&B separados.
- Distinguir el centro estadístico del perfil, el objetivo elegido para esta pista
  y el techo técnico del Guard. Un percentil de un corpus no es por sí mismo un
  límite universal de seguridad ni una demostración de calidad perceptiva.
- Los datos publicados por iZotope respaldan diferencias por género y frecuencia,
  pero no proporcionan un corpus numérico reproducible para QuickMaster. La figura
  consultada no explicita dB en el eje; no digitalizarla y asumir esas unidades.
- Una sola ganancia de Side no iguala cuatro objetivos distintos. Se puede evaluar
  un objetivo global condicionado al espectro Mid mediante
  `P_S_target = sum(P_M_b * 10^(R_profile_b/10))`, siempre con medidas compatibles
  y control de excesos por banda. Es una hipótesis de diseño a validar, no una
  garantía de ajuste de la curva completa. Si no cumple los casos de uso, revisar
  en H0 el control espectral lento necesario antes de programar la aplicación.

### 3.4 Política automática

- `Auto`: encontrar ventanas activas estables de la canción como posibles anclas;
  contrastarlas con perfiles empíricos validados cuando estén disponibles.
- No elegir siempre la sección más fuerte, más ancha o con más antífase. No hace
  falta afirmar que se reconoció semánticamente «el estribillo».
- Fijar una referencia y un margen de variación, mostrando su origen y confianza.
  No recalcular el objetivo desde la salida del controlador: evitar realimentación.
- Si no hay perfil externo fiable, usar referencia interna conservadora y declarar
  ese origen. Si tampoco hay ancla válida, no inventar precisión: limitar la subida
  insegura y explicar el estado, sin etiquetar un plan vacío como éxito.
- Soportar un perfil de referencia local opcional; no exigir al usuario aportarlo
  para el uso habitual ni convertir esto en otro módulo de recomendaciones.
- Al 100 %, el Side Leveler sí debe acercar las partes válidas al objetivo. No
  proteger automáticamente todas las diferencias hasta hacerlo inoperante.

## 4. Arquitectura DSP

Posición recomendada: `EQ → Dynamics → Stereo Image → Clip → Limit → salida`.
Se respetará el sistema de orden de la app; cualquier otra posición debe analizar
su entrada real y revalidar la salida. No realimentar una copia del master final.

### A. Generación y mezcla

1. Ocho bandas EQ en L y ocho en R, con centros intercalados y corredores que no
   se crucen. Trayectorias de frecuencia y ganancia diferentes, deterministas y
   suaves. Ocho por canal es la base de prototipo, no un óptimo auditivo probado.
2. Restar la entrada alineada de cada salida EQ para obtener `dL` y `dR`.
3. Armónicos opcionales sobre el delta: curvas diferenciadas, control de componente
   lineal, DC y aliasing. No duplicar el fundamental al sumar saturación.
4. Extraer `D=(processed_dL-processed_dR)/2` tras toda transformación del delta.
5. Aplicar la delimitación espectral de generación al delta final, también después
   de los armónicos: la saturación puede producir DC e intermodulación por debajo
   de las frecuencias de entrada. No basta con limitar los centros de las campanas.
6. `Sg=S+a*D`; conservar `M` intacto. Amount es mezcla aditiva, no crossfade que
   atenúa el original. Las reducciones de EQ también contribuyen al delta.

Con `Generate Low Frequencies` desmarcado, el límite grave es **150 Hz** y se
implementa mediante FIR simétrico de fase lineal sobre el delta final, después de
armónicos. Se aplica únicamente al delta nuevo, sin filtrar el dry. Compensar
su retardo constante para que todas las ramas coincidan muestra a muestra.
El render offline puede usar convolución centrada y corregir bordes/colas; no
confundirla con una ejecución causal sin latencia ni duplicar la compensación.

Interpretación de ingeniería a comprobar en H2: proteger hasta 150 Hz y situar
la transición por encima, en vez de llamar «excluidos» a graves que un corte a
−6 dB aún deja pasar. Prototipo inicial: banda de rechazo hasta 150 Hz, banda de
paso desde 200 Hz; objetivos de rechazo ≥90 dB y rizado ≤0,05 dB. Son criterios
de prueba, no resultados. Ajustar longitud/transición con evidencia de respuesta,
pre-ringing, coste y efecto sobre percusión. No prometer un brickwall perfecto.
La documentación de FIR de SciPy distingue frecuencia de media amplitud, ancho
de transición y retardo: https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.firwin.html

Marcado permite ampliar la generación hacia graves con control gradual y sin DC;
no obliga a crear subgrave ni desactiva el Side Guard. El límite bajo y la intensidad
admisible también se calibrarán; no inventar un valor universal por género.

Leveler y Guard son etapas independientes sobre el Side conjunto: si se activan,
pueden modificar el Side grave ya existente según sus propios ajustes. La casilla
no se llamará `Protect Bass`, pues no promete inmunidad frente a esas etapas.
Con ambas etapas inactivas, desmarcarla no debe reducir el grave estéreo original.

No prometer ausencia de solapamiento entre respuestas de campanas: lo garantizado
es que los centros no crucen sus corredores y las trayectorias no sean idénticas.
No usar el reloj del sistema, un RNG sin estado reproducible o el tamaño de bloque
como reloj de modulación. Usar posición en segundos del audio fuente, con mapeo
correcto de trim, seek, oversampling y latencia. Guardar semilla y versión DSP.

El backend de EQ variable se decide en el hito de prototipo: comparar una
realización estable de filtro variable con morphing de kernels FIR alineados.
Evaluar fase, pre-ringing, estabilidad, coste y exactitud al hacer seek. No aprobar
una interpolación arbitraria de biquads porque no produzca clicks en un ejemplo.

### B. Side Leveler

La primera implementación aplica **una ganancia suave al Side conjunto**, no cuatro
EQ dinámicas adicionales. El análisis por bandas ayuda a escoger el objetivo y
limita una subida que dañaría una región espectral. Una ganancia escalar no puede
igualar cuatro relaciones incompatibles: mostrar el compromiso, no prometerlo.

Para un objetivo `q=targetSideShare`, la ganancia escalar ideal del Side es
`gIdeal=sqrt((q/(1-q))*P_M/P_S)`, manteniendo M sin modificar. Ejemplo de cálculo,
no algoritmo muestra a muestra. Si M o S no son fiables, no dividir por un epsilon
para inventar Side o amplificar ruido. Silencio, mono y antífase tienen estados
explícitos. Si no hay Side y el generador está apagado, el objetivo no es alcanzable.

Corrección parcial: `gLinear=exp(amount*log(gIdeal))`, con zona neutra suave,
límites de subida/bajada, puertas de confianza y suavizado offline. Es equivalente
a escalar la corrección en dB; la UI sigue mostrando Mid/Side en porcentajes.
Medir sobre energía en ventanas/contexto, no sobre valores de muestras individuales.
Validar otra vez tras aplicar la envolvente: la fórmula instantánea no demuestra
que una media temporal de una señal con ganancia variable cumpla el objetivo.

- 0 %: sin regulación. 50 %: mitad de la corrección en dB fuera de la zona neutra.
- 100 %: objetivo completo en regiones válidas no limitadas por seguridad.
- Upward más lento; downward más ágil. Conservar microdinámica del Side.
- No generar Side si es cero; no tratar el suelo de ruido como material útil.
- Un Side Leveler fuerte puede reducir parte de la energía añadida por Generation;
  es coherente con mantener un objetivo fijo. Medir y mostrar esta interacción.

### C. Side Guard

Ganancia adicional `h(t)<=1` sobre el Side después del Leveler. Solo downward;
habilitación independiente y techo de relación en ajustes avanzados. Controlar
relación global y excesos espectrales con detectores de energía y anticipación.

- Techo y tiempo de medida definidos; no un supuesto límite muestra a muestra.
- Resolver/revisar el render offline hasta satisfacer el techo medido o informar
  que las condiciones de señal impiden la garantía. No ocultar infracciones con GR.
- Mid casi nulo/antífase original requiere tratamiento explícito; reducir Side no
  puede reconstruir un centro que no existe ni recuperar instrumentos cancelados.
- Límites finitos, transición suave y sin competencia con el Leveler: techo por
  encima del objetivo, con margen e histéresis; no perseguirse mutuamente.

Salida: `Lout=M+h*gLinear*Sg`, `Rout=M-h*gLinear*Sg`.
El módulo conserva M; la cadena no lineal posterior puede cambiar su suma mono.
El Side Guard no es un limitador true peak. Reutilizar la protección de salida
existente, verificar headroom y no añadir clipping ni normalización encubiertos.

## 5. Parámetros a calibrar (no son sweet spots ya probados)

| Elemento | Candidatos iniciales |
| --- | --- |
| Bandas generadoras | Ocho por canal como base; comparar doce por canal si aporta mejora medible/auditiva, sin suponer que más bandas es mejor |
| Frecuencia de generación | Exclusión hasta 150 Hz; FIR de fase lineal y transición documentada sobre el delta final, con retardo compensado |
| Generación de graves | Checkbox Off por defecto; calibrar extensión/intensidad al activarlo; el dry no se filtra |
| Ganancia de bandas | ±0,25 / ±0,5 / ±1 / ±2 dB |
| Movimiento del centro | ±0,05 / ±0,1 / ±0,15 octavas, dentro de corredores |
| Modulación | 0,02–0,1 Hz, comparación estática como control de prueba |
| Armónicos | Off como base; residual y aliasing medidos al activarlos |
| Sobremuestreo de armónicos | Comparar 4x y 8x sin duplicar el global ciegamente |
| Leveler | Seguimiento de segundos; probar subida 2–5 s y bajada 0,2–1 s |
| Guard | Detector más corto que Leveler, lookahead y release suaves |

No fijar límites de ganancia tan bajos que el caso de uso legítimo quede anulado.
Los valores definitivos requieren corpus, medición y evaluación musical, no solo
que la curva visual resulte agradable.

## 6. Diseño de software e integración

Componentes propuestos, nombres orientativos:

- `StereoImageSettings`: estado serializable, rangos y defaults versionados.
- `StereoImageAnalyzer` / `StereoReferenceProfile`: mediciones y procedencia.
- `StereoDeltaGenerator`: EQ móvil, delta y armónicos; sin GUI ni análisis global.
- `SideLevelingPlanner` / `SideGuardPlanner`: planes offline inmutables.
- `StereoImagePlan`: objetivo, envolventes, semilla, versión, avisos y clave de caché.
- `StereoImageProcessor`: ejecución por bloques, posición absoluta y publicación
  de medición real; compatible con `AudioProcessor` y su contrato de latencia.
- `StereoImagePane`: UI separada; evitar otro bloque DSP dentro de MainController.

Reutilizar DSPark SmoothedValue, filtros, Saturation, oversampling y analizadores
solo después de comprobar sus contratos. StereoWidth no es el generador. Si se
modifica o porta DSPark, comprobar el C++ correspondiente con vectores numéricos.
No prometer un port de algoritmos propietarios citados como referencias.

Integrar ProcessingPipeline, snapshots independientes, ChainPreset, undo/redo,
A/B, exportación, batch, waveform procesado y medidores. Los presets antiguos
cargan Stereo Image desactivado y conservan el orden relativo de sus módulos;
no fallar porque `chainOrder.size()` pase de cuatro a cinco.

Para archivos de un canal, no escribir dos canales dentro de un buffer mono.
La primera entrega mantiene el formato y deshabilita la generación con explicación;
admite material mono transportado en dos canales. Una conversión mono→stereo de
formato sería un trabajo explícito de canalización, no un cambio oculto.

### Preescucha y rendimiento

- Generador, analizadores y planificadores fuera del hilo de audio/JavaFX.
- Conservar la preescucha por ventanas existente. Añadir contexto/checkpoints de
  filtros y posición correcta; la semilla por sí sola no reconstruye estado IIR/FIR.
- Caché del delta y estadísticas de ventanas `E[S²]`, `E[D²]`, `E[SD]`: para cambiar
  solo Amount, `E[(S+aD)²]=E[S²]+2aE[SD]+a²E[D²]`. No suponer energías aditivas
  independientes ni anchura monótona para cualquier señal.
- Recalcular planes desde estas estadísticas cuando proceda. Cambios de EQ,
  armónicos, audio o etapa anterior invalidan los datos afectados.
- Clave de caché incluye audio/ediciones, estado upstream, posición en cadena,
  parámetros, versión del perfil, semilla, sample rate, canales y oversampling.
- Workers con cancelación, planes inmutables y rechazo de resultados obsoletos.
- Preview provisional identificada; exportar solo el plan final exacto.
- Presupuesto de producto inicial: p95 de cambio audible ≤250 ms a 48 kHz/1x en
  máquina de referencia; medir 4x aparte (objetivo ≤500 ms). Son objetivos, no
  resultados. No aceptar de nuevo pausas de varios segundos por mover Amount.
- Sin asignaciones por muestra; memoria acotada; cachés con presupuesto explícito.

## 7. UI propuesta (inglés)

Tres secciones del único módulo Stereo Image:

1. `Generation`: On/Off, `Amount`; `Harmonics` opcional. Movimiento activo durante
   generación, con profundidad/velocidad en Advanced si las pruebas lo justifican.
   Casilla `Generate Low Frequencies`, desmarcada por defecto y persistente.
   Mostrar el límite nominal real, sin confundirlo con un corte ideal.
2. `Side Leveler`: On/Off, `Amount` 0–100 %, porcentajes actuales y objetivo visibles.
3. `Side Guard`: On/Off, reducción real visible; techo Auto/ajustable en Advanced.

Selector común `Profile`: `Auto (This Track)`, familias/subperfiles que justifique
el estudio, `Reference...`. Por decisión posterior del usuario, el estudio cubre
como máximo diez familias: Electronic, Pop, Rock, Metal, Hip Hop, R&B/Soul/Funk,
Acoustic/Folk/Country, Jazz/Blues, Orchestral/Cinematic y Latin. No publicar 30
subperfiles. Los perfiles publicados requieren H0 aprobado y deben identificarse
como referencias contextuales, no como una relación M/S profesional universal.
Cambiar Profile no cambia Amount, habilitaciones ni el consentimiento de graves.
No ofrecer presets de género vacíos o etiquetados como validados sin mediciones.

Mostrar relación actual/objetivo en porcentajes de energía (`Mid` / `Side`), origen
de referencia y corrección real. Los dB de ganancia aplicada pueden aparecer como
diagnóstico, sin confundirse con el objetivo porcentual ni con Amount.
Audiciones `Mono` y `Delta` sin alterar exportación ni estado persistente del DSP.
No añadir un control principal de ganancia Side estática. No mostrar `OFF` como
si el módulo estuviera deshabilitado cuando simplemente no está corrigiendo.
Si una protección limita el resultado, indicarlo. No esconder un generador inerte
detrás de un indicador Ready o una animación.

## 8. Hitos y condiciones de salida

### H0 — Investigación de referencias: cerrada para el alcance acordado

- Bibliografía contrastada, unidades y semántica del checkbox definidas: hecho.
- Medidor de investigación con oráculos y contraste independiente: hecho.
- Cuatro pistas privadas y un control público medidos: hecho, no calibran géneros.
- Diez familias y cinco referencias públicas por familia: 50/50 medidas y
  verificadas. Versiones/fuentes registradas; no se presentan como WAVs originales
  ni como una muestra representativa de toda la producción profesional actual.
- Revisión ampliada de estudios y plan de cobertura/captura silenciosa guardados
  en `docs/research/stereo-corpus-study-plan.md`; campaña de 50 completada,
  estado vigente en `docs/research/stereo-campaign-state-20260928.md`.
- Distribuciones estéreo/tonales y objetivos de referencia trazables: guardados.
  No son todavía presets implementados o validados en el DSP de QuickMaster.
- La validación del FIR a 150 Hz y de generación de graves incluidos pasa a H2;
  no se afirma una escucha ni un sweet spot que este estudio no puede demostrar.

La recogida previa solicitada está completa. H1–H5 constituyen el desarrollo
pendiente, sin nuevas familias ni otra campaña obligatoria añadida de forma
unilateral. No copiar curvas propietarias ni prometer calidad perceptiva basándose
solo en estadísticas. La validación DSP/musical del módulo sigue siendo necesaria.

### H1 — Contratos y analizador

Tras H0, portar los contratos de medida y el formato de perfiles a la aplicación.
Pruebas: dual-mono, solo L/R, antífase, L/R desbalanceados, silencios, M o S casi
nulos y relaciones conocidas. Demostrar independencia de ganancia global, salvo
puertas de silencio expresamente definidas. Separar medición de juicio musical.

Salida: mediciones reproducibles, política de objetivo trazable y estado explícito
de perfiles empíricos. No declarar cerrado un perfil sin datos suficientes.

### H2 — Generador y armónicos

Prototipos aislados, comparación de backends, movimiento de centros real, corredores
verificados y delta puro. Probar mono en dos canales, tonos/multitonos, barridos,
impulsos, percusión y audio real. Generación >0 debe producir diferencias medibles
en señales aptas incluso con Side original cero.

Salida: backend elegido con evidencia; armónicos sin DC/aliasing inaceptable;
decisión documentada sobre protección de transitorios después de comparar.

### H3 — Leveler y Guard

Fixtures con tramos −14/−8/−4 dB S/M respecto a un objetivo de −8 dB, solo como
oráculo. Verificar subida, bajada, 0/50/100 %, gates y límites. En mesetas válidas,
proponer tolerancia ±0,25 dB al objetivo y comprobar el render, no solo el plan.
Validar el techo de Guard sobre sus ventanas declaradas (tolerancia inicial
0,2 dB), incluyendo cambios bruscos y máximos entre hops. Afinar tolerancias antes
de aceptarlas, no después de un fallo para hacerlo desaparecer.

Salida: actuación audible/medible cuando corresponde y sin corrección injustificada
en fixture ya conforme; limitaciones explícitas y ausencia de bombeo indebido.

### H4 — Integración interactiva y UI

Presets heredados/nuevos, snapshots, undo, A/B, render/cache, export, batch,
trim/crop, seek/loop y waveform. Estreses de 30 cambios y cancelaciones cruzadas,
orden variable de cadena y coexistencia con EQ Auto Gain, Leveler, clips y limitador.

Salida: latencia y memoria medidas; ningún resultado antiguo publicado; igualdad
entre render completo y lectura de su resultado final por bloques/seek.

### H5 — Aceptación musical y entrega local

- Fuentes reales: By Now, Quiet Gold, Billie Jean y Wicked Game disponibles del
  usuario, sin modificar originales, más corpus autorizado para validar perfiles.
- Evaluación a igual sonoridad en estéreo/mono, auriculares y altavoces cuando haya
  escucha humana disponible. Registrar escucha humana y pruebas numéricas por
  separado; no afirmar una escucha que no se haya realizado.
- Transitorios, foco vocal, equilibrio L/R, timbre, fatiga y estabilidad espacial.
  No aprobar por aumento de volumen o por una correlación «bonita».
- Verificar bypass bit-exact y neutralidad con las tres funciones inactivas.
  Amount=0 anula su propia sección, no otras secciones activas.
- Verificar suma mono del módulo alineada dentro del error float: presupuesto
  inicial de error absoluto ≤2e−6*max(1, peakInput) en señales de prueba finitas.
  Auditar también la suma mono del render final con dinámica posterior.
- Matriz de sample rates/oversampling soportados, extremos de parámetros, archivos
  cortos/largos, NaN/Inf, DC, tails, latencia y determinismo temporal.
- Ejecutar suite completa de aplicación y DSPark, pruebas de aceptación y build.
- Generar imagen Windows con el runtime soportado; desplegar portable en
  `C:\Program Files\QuickMaster`, verificar JAR/imagen, abrir el EXE instalado
  y comprobar arranque limpio. Probar el caso real en la aplicación empaquetada.
- Dejar informe con hashes, métricas, limitaciones y evidencia. No prometer
  «perfección al 100 %» ni publicar un release por aprobar solo tests unitarios.

Commit/push/release no forman parte de esta solicitud de investigación y plan.
Cuando se autoricen, mensajes `[FIX]`/`[FEAT]`/`[DOC]` breves (máximo dos líneas),
identidad configurada de Cristian Moresi como único autor/committer y sin créditos
de asistente. Preservar los cambios existentes en el checkout de integración.

## 9. Estado histórico al cerrar la investigación, antes de implementar

El siguiente párrafo conserva el cierre de la fase de investigación. El estado
actual de implementación y entrega lo sustituye el informe enlazado al principio.

Campaña 50/50 y datos adicionales para EQ futura completados y verificados.
Stereo Image no se ha implementado ni se han fijado sweet spots perceptivos
definitivos. La aplicación instalada no cambia; su JAR sigue con hash
`cbad134db59f08138722b07550774e30869f580dbcad25ede7f91d0347aa78a2`.
Solo se cambiaron documentación/herramientas aisladas de investigación, por lo
que no corresponde reconstruir o desplegar QuickMaster. Siguiente fase: H1–H5.

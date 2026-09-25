# Arquitectura técnica · Leveler conservador y viewport de waveform

- Estado: ACEPTADO (ADR-007 y ADR-009 aceptados; construcción acotada de M-004 autorizada por decisión 011)
- Fecha: 2026-09-01
- Última enmienda: 2026-09-05 · ADR-009 ACEPTADO: extensión funcional O2, rechazo temprano con memoria acotada e integridad de copia del app-image final; ADR-008 queda histórico.
- Plataforma objetivo: Java 17, JavaFX 21.0.11, JUnit 5.10.2, DSPark 0.1.0
- Alcance: UC-001..UC-006; RF-001..RF-012; RNF-001..RNF-010
- Base inspeccionada: contratos reales de `AudioProcessor`, `ProcessingPipeline`, `AnalysisDynamicsProcessor`, `LevelerProcessor`, snapshot/adopción y waveform de `MainController`, más las pruebas enumeradas en M-002.

## Visión y principios de diseño

1. **Unidad ante duda.** Desde el switch de M-005, el Leveler nuevo solo publica ganancia distinta de 0 dB después de superar, en este orden, validez, medición, segmentación, vetos duros, puerta corporal/contextual, similitud, agrupación o pareja estricta, referencia, planificación y seguridad de true peak. Un fallo global publica unidad; un fallo local deja la región a 0 dB. M-003 no ejecuta esa ruta nueva y conserva el Leveler legado; M-004 valida la política sobre el candidato de sombra sin darle autoridad de audio. [UC-001..003, RF-001..006, RNF-001..005]
2. **Identidad no implica intención.** Loudness no participa en el score de identidad. Intro, outro, silencio, fade, break, transición, tendencia y buildup son vetos irrevocables: ni similitud, ni repetición, ni gap, ni número de miembros pueden anularlos. [UC-001/002, RF-001..004, RNF-001/004]
3. **Análisis inmutable; render disperso.** El análisis caro produce datos inmutables. La publicación al hilo de audio es una única referencia `volatile` a un schedule completo. Como puente de compatibilidad, M-003 y M-004 envuelven también el único `gainEnv` legado del Leveler en `DenseGainSchedule`, en dominio lineal y sin crear otro array O(F), y conservan su salida bit a bit. La interfaz común identifica el dominio, pero no fuerza una conversión común de ganancia. Solo M-005 publica el `SparseGainSchedule` nuevo en dominio dB y elimina entonces `float[totalFrames]` del Leveler; Peak/Beat/Punch conservan su schedule denso mediante el adaptador compatible. [UC-003/006, RF-005/006/012, RNF-002..005/007..009]
4. **Una sola geometría de waveform.** `WaveformViewport(D,S,V)` es un modelo puro. Dibujo, playhead, selección, scrub y fades convierten tiempo/píxel exclusivamente mediante `timeAtX` y `xAtTime`. [UC-004/005, RF-007..011, RNF-006/009]
5. **Norma separada de hipótesis.** Ventanas, gates y true peak BS.1770/EBU se versionan como constantes normativas; umbrales MIR, pesos, deadband y tiempos viven en `LevelerCalibrationProfile.V1`, son falsables y nunca se presentan como universales. [RNF-001/003/004/009]

## Componentes y límites

| Componente propuesto | Responsabilidad y límite | Entrada → salida | Trazabilidad |
|---|---|---|---|
| `LoudnessAnalyzer` / `LoudnessCore` | Núcleo K-weighted compartido por producto 1/2 y runner oficial 1/2/5/6; agregados regionales separados, sin targets. | PCM + formato/layout explícito → timeline acotada o readouts temporales | UC-001..003; RF-001/003/004/006; RNF-001/003/005 |
| `StructuralFeatureExtractor` | Identidad armónica/tímbrica/actividad independiente de nivel; no agrupa. | PCM + loudness → `FeatureTimeline` | UC-001/002; RF-001/002; RNF-004/005/009 |
| `BoundaryDetector` | Novelty local multiescala, validación de la extensión fuente y cap de segmentos; no asigna etiquetas humanas ni infiere la cola desde centros. | features + `totalFrames` no retenido → `SegmentLayout` | UC-001/002; RF-001/003; RNF-003..005/009 |
| `SegmentDescriptorBuilder` | Agrega 32 bins, loudness regional, trends, contexto y sketch PCM acotado. | timelines + layout → descriptores | UC-001..003; RF-001..005; RNF-001/004/005 |
| `ProtectionClassifier` | Unión de vetos duros irrevocables. | descriptor + vecinos → razones | UC-002; RF-002/003/005; RNF-001/002/004 |
| `BodyContextGate` | Decide elegibilidad corporal/contextual sin leer H/T/A/C ni gap. | regiones no vetadas → decisión | UC-001/002; RF-002/003; RNF-001/004 |
| `SegmentComparator` | Scores H/T/A/C, DTW, degeneración y orden total. | par elegible → `SimilarityScore` | UC-001/002; RF-001/002; RNF-004/005/009 |
| `ComparableGroupBuilder` | Complete-link, separación externa y ruta estricta de pares. | scores → grupos/pares | UC-001/002; RF-002/004; RNF-001/004 |
| `ReferencePlanner` | Referencia, confianza, pesos, deadband y target bruto. | grupos + loudness → targets | UC-001/003; RF-004; RNF-001/004 |
| `LoudnessConformanceGuard` / `ConformanceArtifactLoader` / `ConformanceCodec` | Loader acotado del mismo JAR, codec puro y factory verificador de 94 claves; ningún audio/corpus en producción. | atestación canónica + binding actual → `StandardValidationReport` | UC-001/003/006; RF-001/006/012; RNF-003/004/008..010 |
| `RetainedGraphAuditor` (solo tests) | Recorre desde roots fijadas y mide el grafo retenido con whitelist externa; no confía en contadores de producción ni entra al app-image. | owner + roots externas + dimensiones → `ShadowRetentionReport` | UC-001/003/006; RF-005/006/012; RNF-003..005/008/009 |
| `GainPlanner` / `RampAllocator` | Leveling/Speed, solver total por componente, rampas C1 y schedule disperso. | targets + controles → schedule | UC-001..003; RF-004..006; RNF-002..005/009 |
| `TruePeakSafety` / `FiniteTruePeakStream` | Prueba dBTP finita con cola EOF, reduce solo boosts; nunca limita transitorios. | PCM/perfil + schedule → schedule seguro | UC-003/006; RF-006/012; RNF-003/004 |
| `GainSchedule` / `LevelerProcessor` | Lifecycle, publicación, seek, oversampling y render estéreo-enlazado. | posición + buffer → misma longitud | UC-001..003/006; RF-001..006/012 |
| `WaveformViewport` / `TimelineEditRebaser` | D/S/V, zoom/pan/reset, transformaciones y mapas de edición puros. | gesto/edit → estado nuevo | UC-004/005; RF-007..011; RNF-006/009 |
| `WaveformPeakIndex` / `WaveformGestureAdapter` | Consulta híbrida exacta de peaks y traducción JavaFX con prioridad/inercia. | snapshot PCM/evento → render/acción | UC-004/005; RF-007..011; RNF-005/006/009 |

`LevelerAnalysisEngine` es el único orquestador de esas etapas y no contiene fórmulas propias. M-004 usa exclusivamente clases `final` —no records, clases locales, anónimas ni lambdas— para que el classfile no necesite bootstraps de `ObjectMethods`/`LambdaMetafactory` y el schema retenido sea literal. Las firmas internas normativas de M-004 son:

```java
ShadowAnalysisSnapshot LevelerAnalysisEngine.analyzeShadow(float[] pcm, AudioFormat source,
                                                           CancellationToken cancellation);
LoudnessTimeline LoudnessAnalyzer.analyze(float[] pcm, AudioFormat f, CancellationToken c);
FeatureTimeline StructuralFeatureExtractor.extract(float[] pcm, AudioFormat f, LoudnessTimeline l,
                                                    CancellationToken c);
SegmentLayout BoundaryDetector.detect(FeatureTimeline f, long totalFrames,
                                      LevelerCalibrationProfile p);
FrozenList<SegmentDescriptor> SegmentDescriptorBuilder.build(float[] pcm, AudioFormat f,
                                                              LoudnessTimeline l, FeatureTimeline x,
                                                              SegmentLayout s);
ProtectionDecision ProtectionClassifier.classify(int segmentIndex, FrozenList<SegmentDescriptor> all,
                                                  LevelerCalibrationProfile p);
BodyEligibility BodyContextGate.evaluate(int segmentIndex, FrozenList<SegmentDescriptor> all,
                                         FrozenList<ProtectionDecision> protections);
SimilarityScore SegmentComparator.compare(SegmentDescriptor a, SegmentDescriptor b,
                                          AudioFormat source, FeatureTimeline features,
                                          LevelerCalibrationProfile p);
// Únicos auxiliares nuevos package-private; no fields ni tipos nuevos:
static FrozenList<StructuralBin> SegmentDescriptorBuilder.structuralBins(
    FeatureTimeline features, FrameRange range, long totalFrames);
static double[] BoundaryDetector.originalNovelty(
    FeatureTimeline features, long totalFrames, LevelerCalibrationProfile p);
GroupingResult ComparableGroupBuilder.build(FrozenList<SegmentDescriptor> eligible,
                                             SimilarityMatrix scores,
                                             LevelerCalibrationProfile p);
ReferencePlan ReferencePlanner.plan(GroupingResult groups, FrozenList<SegmentDescriptor> segments,
                                    LevelerCalibrationProfile p);
StandardValidationReport LoudnessConformanceGuard.verify(ConformanceRequirement requirement,
                                                          ConformanceRun run,
                                                          BuildAlgorithmBinding binding);
```

Las siguientes firmas pertenecen a M-005 y **no** forman parte del universo productivo, root ni whitelist M-004:

```java
SparseGainSchedule GainPlanner.plan(ReferencePlan refs, ControlState controls, AudioFormat source);
SafetyResult TruePeakSafety.constrain(float[] pcm, AudioFormat source, SparseGainSchedule candidate,
                                      PeakSafetyProfile cached, CancellationToken c);
LevelerAnalysisResult LevelerAnalysisEngine.analyzeOrRemap(float[] pcm, AudioFormat source,
                                                           ControlState controls, LevelerAnalysisCache cached,
                                                           CancellationToken c);
```

`CancellationToken` es una clase `final` con el único field `private volatile boolean cancelled`; es una referencia de entrada, nunca se retiene ni se publica y `isCancelled()` se consulta cada <=4.096 frames y entre pares. No existen callback de progreso/cancelación, listener ni registro global: `ShadowDiagnostics.completedPhaseBits` conserva el prefijo monotónico de fases al finalizar. Cada método devuelve un DTO válido o una razón/status; cancelación/error retorna localmente sin reemplazar `shadowAnalysis`. Ningún componente conserva estado mutable entre análisis. [UC-001..003/006; RF-001..006/012; RNF-002..005/009]

### Tipos, unidades e invariantes

Los DTO retenidos M-004 son clases `final` inmutables. Todos los fields enumerados abajo son `private final`, en el orden escrito, sin fields sintéticos ni caches; las excepciones productivas previas son `CancellationToken.cancelled` (`private volatile`) y `LevelerProcessor.shadowAnalysis` (`private volatile`). Los arrays se copian al entrar o se transfieren en propiedad y nunca se exponen mutables. `FrozenList<E>` tiene exactamente `private final Object[] elements`, longitud lógica exacta, cero capacidad libre y accessors read-only. Los nombres incluyen unidad (`...Frames`, `...Sec`, `...Lufs`, `...Db`, `...Dbtp`). Se añade únicamente el núcleo transitorio `LoudnessCore`: sus cuatro fields `private` mutables son `ringCursor:int`, `framesSeen:long`, `momentarySum:double`, `shortTermSum:double`; los restantes fields y sus seis arrays primitivos son los del schema literal ADR-011. No es DTO, root ni estado retenido. Run, binding, codec y loader tampoco son alcanzables desde snapshot/statics.

- `AudioFormat(int sampleRateHz, int channels, long frames)`: Leveler admite 1/2 canales y sample rate positivo. `frames == samples.length/channels`; resto, overflow, canal no soportado o muestra no finita invalidan el análisis completo. El fallback no intenta sanear PCM de entrada.
- `FrameRange(long startInclusive,long endExclusive)`: `0<=start<end<=frames`; conversión tiempo/frame única y con redondeo documentado.
- `MeasuredLoudness(boolean present,double lufs)`: ausencia exige raw bits de `+0.0`; presencia exige LUFS finito. Es la representación opcional del loudness de usuario; la evidencia oficial usa los tags explícitos de ADR-011 y nunca codifica ausencia como un LUFS medido.  `OptionalDouble`/`Optional` quedan prohibidos.
- `LoudnessTimeline(int momentaryWindowFrames,int shortTermWindowFrames,int hopFrames,double[] momentaryPower,BitSet momentaryValid,double[] shortTermLufs,BitSet shortTermValid,MeasuredLoudness integrated)`: Momentary y Short-term tienen arrays y máscaras distintos. Para `H100=max(1,round(.1*sampleRateHz))`, `Wm=max(1,round(.4*sampleRateHz))`, `Wq=max(1,round(3*sampleRateHz))`, `M=F==0?0:ceilDiv(F,H100)` y `Q=F==0?0:ceilDiv(F,H100)`. La entrada `i` cubre `[i*H100,min(F,i*H100+W)]`; así la cola existe exactamente una vez y queda inválida si no completa W. `momentaryPower[M]` usa `+0.0` cuando inválida; `shortTermLufs[Q]` usa `+0.0` cuando inválida. Bits >=M/Q están limpios.
- `FeatureTimeline(long hopFrames,FrozenList<StructuralFrame> frames)`: centros estrictamente crecientes al clock estructural .5 s; no retiene ni permite inferir la extensión exacta de la pista y no contiene espectros/STFT persistentes. Su validez como clock completo se comprueba contra el argumento no retenido `totalFrames` de `BoundaryDetector.detect`.
- `StructuralFrame(long centerFrame,double[] chroma12,double[] spectral8,double onsetFlux,double activity)`: contiene exactamente `12+8+1+1=d=22` observaciones; Short-term vive solo en `LoudnessTimeline`. Valores presentes son finitos; chroma no negativa; activity `[0,1]`; normas cero quedan marcadas, no imputadas.
- `SegmentLayout(LayoutStatus status,FrozenList<FrameRange> regions)`: `READY` implica partición exacta y contigua de `[0,frames)` y tamaño 1..64; un fallo global conserva su razón estable y lista vacía, nunca una partición truncada.
- `SegmentId(int ordinal)`: `0..63`; `S000`..`S063` se deriva sin retener String. Es el desempate estable.
- `PcmSketch(int channels,int bins,double[][] values)`: exactamente 2.048 bins relativos por canal para toda región no vacía; propiedad inmutable, valores finitos y orden `[canal][bin]`; nunca posee PCM completo.
- `BodyContextVector(double previousActivityRatio,double nextActivityRatio,double entryLoudness12,double exitLoudness12,double leftNoveltyMad,double rightNoveltyMad)`: seis componentes finitos en este orden normativo.
- `StructuralBin(double[] chroma12,double[] spectral8,double onsetFlux,double activity)`: exactamente d=22 escalares lógicos y ninguna otra referencia.
- `SegmentDescriptor(SegmentId id,FrameRange range,FrozenList<StructuralBin> bins,long validBinMask,MeasuredLoudness regionalLoudness,double loudnessSlopeLuPerSec,double loudnessDeltaLu,double loudnessConsistency,double activitySlopePerSec,double activitySpread,double foregroundRatio,BodyContextVector context,PcmSketch sketch)`: exactamente L=32 bins; máscara solo usa los 32 bits bajos; nunca posee ni referencia PCM completo.
- `ProtectionFlags(long reasonBits)`: `reasonBits & ~0xFFL == 0`; los bits 0..7 corresponden, en el orden D-301, a `SILENCE_OR_SPARSE,INTRO_EDGE,OUTRO_EDGE,FADE_OR_CRESCENDO,BREAK_OR_BREAKDOWN,TRANSITION,TREND,MACRO_BUILDUP`. `ProtectionDecision(SegmentId id,ProtectionFlags flags)` queda bloqueada si `reasonBits!=0`; no hay `EnumSet`.
- `SimilarityScore(double h,double t,double a,double c,int chromaRotation,int validBins,SimilarityRejectionReason rejectionReason)`: esta es la lista única. `rejectionReason==NONE` implica score finito/estable; inestabilidad se representa por razón y no por boolean. Si existe rechazo, ningún score se usa para agrupar.
- `SimilarityMatrix(int segmentCount,FrozenList<SimilarityScore> scores)`: contiene exactamente `P=S*(S-1)/2` scores en orden `(i,j),i<j`; diagonal 1 se deriva y no se retiene; un par rechazado conserva razón y no se representa como score cero.
- `ComparableGroup(int ordinal,int[] memberOrdinals,double[] memberQuality,double confidence)`, `ComparablePair(int firstOrdinal,int secondOrdinal,double confidence,double margin)` y `GroupingResult(FrozenList<ComparableGroup> groups,FrozenList<ComparablePair> pairs)`: membresías disjuntas; cada grupo tiene >=3, cada pareja 2, `K=sum(group.memberOrdinals.length)+2*pairs.size<=S`; cada `memberQuality` tiene la misma longitud que sus ordinals.
- `ReferenceTarget(SegmentId segmentId,MeasuredLoudness referenceLoudness,double rawDb,double gConf,double confidenceWeightedDb,ReferenceReason reason)`: `confidenceWeightedDb` es exactamente `rawDb*gConf`; no contiene Leveling, Speed, clamp final ni geometría de rampa.
- `ReferencePlan(FrozenList<ReferenceTarget> targets,double[] weights)`: exactamente S targets y S weights; `SegmentId` aliasa el descriptor homólogo; suma de pesos validada y toda región no miembro tiene loudness ausente y `rawDb=gConf=confidenceWeightedDb=+0.0`.
- `ConformanceState`: exactamente `NOT_RUN`, `UNAVAILABLE`, `PARTIAL`, `FAILED`, `PASSED`; el orden no se usa como autorización y solo `PASSED` abre el guard de M-005.
- `OfficialSignalEvidence(String setId,String setVersion,int caseNumber,String signalId,String signalSha256,int sampleRateHz,int channels,ReadingKind readingKind,double expectedLufs,double measuredLufs,double toleranceLu,ChannelLayout channelLayout,ReadoutMode readoutMode,LoudnessValueKind expectedKind,LoudnessValueKind measuredKind,double minimumLufs,long sourceFramesConsumed,long firstReadoutEndFrame,long lastReadoutEndFrame,long readoutCount,long minEndFrame,long maxEndFrame,long completeGatingBlocks,long absoluteGateBlocks,long relativeGateBlocks,int resetCount,boolean eofReached)`: lista exacta de 27 fields private final. Los cuatro nuevos refs son enums compartidos; los nueve long nuevos y minimum/reset/EOF son escalares. FINITE exige doubles finitos; NO_LOUDNESS exige slots raw +0.0 no numéricos y solo pasa en LFE21/27 completos con cero gates seleccionados. Protocolo, expected/tolerance y clave se contrastan con el requirement literal, no con valores declarados por el input.
- `RequiredSetReport(String setId,String setVersion,String manifestSha256,ConformanceState state,FrozenList evidence,ConformanceReason reason)` y `StandardValidationReport(String requirementId,ConformanceState state,String algorithmId,String algorithmSha256,String profileSha256,String attestationSha256,String runnerSha256,FrozenList sets,long createdAtEpochSecond)`: dos sets ordenados; se añade runnerSha256. Constructor público de report rechaza PASSED; `verifyBound(requirement,run,binding)` comprueba bytes/semántica y es el único acceso al constructor privado que puede producirlo. `matches(binding)` exige PASSED y coincidencia de IDs/cuatro hashes/timestamp. Hashes son cuatro Strings owned SHA64 o cuatro `""` shared si no hay binding, nunca hashes inventados.
- `ConformanceRequirement(String requirementId,String ituSetId,String ituSetVersion,String ituPinnedManifestSha256,String ebuSetId,String ebuSetVersion,String ebuPinnedManifestSha256,String algorithmId,FrozenList requiredReadings)`: singleton estático público final `OFFICIAL_LOUDNESS_V1`, ConstantValue público static final `E_REQ=94`, constructor privado, valores/pins exactos y FrozenList de 94 RequiredOfficialReading fijos. No se deriva el requisito del corpus.
- `ConformanceRun(String ituManifestSha256,String ebuManifestSha256,FrozenList evidence,boolean ebuTermsAuthorized,boolean attempted,ConformanceReason failureReason,String algorithmId,String algorithmSha256,String profileSha256,String runnerSha256,long createdAtEpochSecond)`: DTO transitorio final; no recibe state. failureReason distinto de NONE/CORPUS_UNAVAILABLE obliga FAILED; attempted=false exige cero evidencia y razón NONE.
- `BuildAlgorithmBinding(String algorithmId,String algorithmSha256,String profileSha256,String attestationSha256,String runnerSha256,long createdAtEpochSecond)`: DTO transitorio final, constructor package-private usado en producción solo por loader; cuatro hashes completos y timestamp>=0.
- `RequiredOfficialReading(String setId,String setVersion,int caseNumber,String signalId,String signalSha256,int sampleRateHz,int channels,ChannelLayout channelLayout,ReadingKind readingKind,ReadoutMode readoutMode,LoudnessValueKind expectedKind,double expectedLufs,double toleranceLu,int bitsPerSample,int waveFormatTag,int channelMask,int blockAlign,long sourceFrames,long fileBytes,long dataBytes,long riffDeclaredEnd,long firstReadoutEndFrame,long lastReadoutEndFrame,long readoutCount,long completeGatingBlocks)`: entry final de tabla estática únicamente; los 25 fields y todos sus 94 valores quedan fijados por [schema-delta](milestones/M-004/conformance-contract-001/schema-delta.001.json) y [runtime-profile](milestones/M-004/conformance-contract-001/runtime-profile.001.json).
- `ChannelLayout`: exactamente MONO_MAIN,STEREO_LR,SURROUND_5_0,SURROUND_5_1; `ReadoutMode`: EOF_INTEGRATED,STEADY_EOF,CONSTANT_INTERVAL,MAXIMUM_FULL_WINDOWS; `LoudnessValueKind`: FINITE,NO_LOUDNESS. ReadingKind M/S/I y ConformanceState no cambian. ConformanceReason conserva los nueve valores previos y añade READOUT_MISMATCH,LAYOUT_MISMATCH,CONTAINER_MISMATCH,ATTESTATION_MISMATCH, en ese orden. Sin fields de instancia adicionales en enums.
- `ShadowAnalysisResult(ShadowAnalysisStatus status,ReferencePlan referencePlan)` y `ShadowAnalysisCache(AudioFormat format,byte[] pcmFingerprintSha256,String algorithmId,String profileId,LoudnessTimeline loudness,FeatureTimeline features,SegmentLayout layout,FrozenList<SegmentDescriptor> descriptors,FrozenList<ProtectionDecision> protections,SimilarityMatrix similarity,GroupingResult grouping,ReferencePlan referencePlan)`: son tipos exclusivos M-004. `result.referencePlan==cache.referencePlan` por identidad; fingerprint mide exactamente 32 bytes; IDs de algoritmo/perfil son shared atoms fijados.
- `DiagnosticEntry(DiagnosticCode code,int segmentOrdinal,int relatedOrdinal,long occurrenceCount)` y `ShadowDiagnostics(ShadowAnalysisStatus status,long completedPhaseBits,FrozenList<DiagnosticEntry> entries,StandardValidationReport standardValidation,ShadowMemoryCounters memoryCounters)`: `result.status==diagnostics.status`; bits 0..8 son exactamente `VALIDATE,LOUDNESS,FEATURES,SEGMENTS,PROTECT,COMPARE,PLAN,VALIDATION,DONE` y solo se admite un prefijo; snapshot completo exige `0x1FF`. Validación aparece solo aquí y no tiene alias en result/cache.
- `ShadowMemoryCounters(long denseEnvelopeCount,long denseEnvelopeElements,long timelineArrayCount,long timelinePrimitiveElements,long descriptorArrayCount,long descriptorPrimitiveElements,long sketchArrayCount,long sketchPrimitiveElements,long matrixScoreCount,long directBufferBytes,long retainedPcmRefs,long retainedChunkCount,long retainedSpectrumCount,long maxChunkFrames,long loudnessHops,long structuralHops,long segments,long pairs)`: exactamente 18 `long`; se contrasta con el auditor y nunca autoriza por sí solo.
- `ShadowAnalysisSnapshot(ShadowAnalysisResult result,ShadowAnalysisCache cache,ShadowDiagnostics diagnostics)`: única root productiva retenida por `LevelerProcessor`; las tres referencias son no null en `SHADOW_READY`/`DONE`, cumplen los aliases anteriores y no contienen objetos de auditoría.
- `ShadowRetentionReport(whitelistId,whitelistSha256,agentPresent,rootSchemaSha256,List<InspectedRoot>,List<RetainedTypeEvidence>,totalRetainedBytes,accountedAllowedBytes,unaccountedRetainedBytes,externalBoundaryBytes,List<RetentionViolation>)`: evidencia test-only canónica; toda suma usa `long` exacto, `unaccountedRetainedBytes==0` y `totalRetainedBytes==accountedAllowedBytes` son gates obligatorios.
- `GainSchedule`: interfaz sellada que solo expone `domain()` y `sourceFrames()`; `GainDomain` es exactamente `LEGACY_LINEAR` o `SPARSE_DB`. No existe un getter común `gainDbAt`, porque convertir Dense a dB rompería su compatibilidad.
- `DenseGainSchedule(envRateHz,sampleEnv)`: toma propiedad del `float[]` fresco producido por el mapper legado, no lo copia ni lo expone y exige longitud positiva/rate finito positivo. Su único lookup interno devuelve ganancia lineal con la semántica histórica exacta.
- `SparseGainSchedule(sourceRateHz,sourceFrames,GainPiece[],SafetyProof,AnalysisStatus)`: pieces ordenadas, finitas, no solapadas, `HOLD` o `SMOOTHSTEP`, extremos `[-6,+3] dB`; fuera de ellas gain 0 dB. Solver `<=3*S<=192`; postcondición pública `<=258` pieces.
- `PublishedGain(GainSchedule,long analysisGeneration,AnalysisStatus)`: única referencia `volatile` consumida por `process`; rate/length/schedule nunca se publican por separado.
- `LevelerAnalysisCache`/`LevelerAnalysisResult`: tipos **futuros M-005**, distintos y no supertipos de los `ShadowAnalysis*`; podrán contener cache/remap/publicación después del switch, pero no pueden aparecer como dependencia, field ni objeto M-004.
- `WaveformViewport(D,S,V)`: para `D>0`, `Vmin<=V<=D` y `0<=S<=D-V`; pista vacía `(0,0,0)`.
- `FiniteTruePeakStream(channels,kernelFactory)`: mutable y confinado a una medición; un kernel/canal, estados `OPEN/FINISHED`, `finish` idempotente sin segundo flush. Nunca forma parte de un DTO/cache publicado.
- `WaveformSourceSnapshot(generation,pcmRef,sampleRateHz,channels,frames)` / `WaveformRenderState(snapshot,index)`: ownership read-only transferido y publicación atómica; índice nulo significa fallback de construcción, nunca otra generación.

### Constantes normativas y perfil calibrable

| Familia | Valores | Naturaleza |
|---|---|---|
| Loudness | M400 ms y S3 s rectangulares sin gate; I400 ms/hop100 ms, gate absoluto estricto -70 y relativo L_abs-10; readout oficial M/S cada frame real cuando exige intervalo/máximo | BS.1770-5 / EBU; grid retenido de 100 ms y timestamps concretos del perfil son protocolo local |
| Agregado regional | primero `>-70`, después `L_abs_region-20 LU`, mediana/percentiles; mínimo 3 ventanas, 3 s y 50 % cobertura | adaptación QuickMaster, no “Integrated” |
| True peak | estimación BS.1770 4x; tolerancias Tech 3341 +0.2/-0.4 dBTP | normativa/verificable |
| Grid estructural | hop .5 s; STFT Hann ~46.4 ms, `fftSize=nextPowerOfTwo(ceil(sr*.0464))` limitado 2048..8192, hop FFT `fftSize/2`; 32 bins/segmento | hipótesis V1 |
| Segmentación | escalas 2/4/8 s, `median+3*MAD`, máximo ±2 s, separación 2 s, `S<=64` | hipótesis V1 |
| Retry de segmentación | intento robusto; después percentiles 97,5 y 99,0, estrictos y recalculados desde la novelty original; primer `S<=64` | contrato fijo |
| Sketch/contexto | 2.048 bins fractional-area/canal; lags -4..+4; cobertura >=.99; `R_scale<=1e-4` y `D_ctx<=.05` | fórmula fija |
| Comparación | ratio `[.75,1.33]`; 24/32 bins; racha inválida <=4; DTW band 4; grupo H=.85/T=.80/A=.82/C=.85/separación=.08; par H=.92/T=.90/A=.90/C=.92/M=.12 | fórmula fija, thresholds V1 |
| Target | deadband 1 LU; cut -6 dB; boost +3 dB; pendiente 2 dB/s; Speed 4.0..0.75 s | hipótesis V1 |

`LoudnessStandard.BS1770_5` conserva lo normativo y `LevelerCalibrationProfile.V1` todas las hipótesis, su ID y rangos de sensibilidad. Un perfil inconsistente publica unidad.

### Medición y extracción estructural

`LoudnessAnalyzer` trabaja en `double`, suma potencia K-weighted por canal —nunca amplitud fold-down— y conserva potencia además de LUFS. Para bloque `j`, `l_j=-0.691+10log10(z_j)`: selecciona `J_a` sobre -70, calcula `L_abs` en potencia solo con `J_a`, usa `Gamma=L_abs-10`, y vuelve a seleccionar. El vector `99×-80,1×-20,1×-45` produce `L_abs=-22.996588028312978`, gate `-32.99658802831298` y resultado `-20`.

`StructuralFeatureExtractor` suma potencia espectral entre canales. Por frame STFT:

- chroma: bins 55 Hz..`min(5 kHz,.95*Nyquist)`; la coordenada MIDI fraccional reparte potencia entre las dos clases vecinas; al agregar el hop se resta el percentil 10, se clampa a cero y normaliza L2;
- `spectralShape[8]`: bandas logarítmicas 60 Hz..`min(16 kHz,.95*Nyquist)`, `log1p` de energía, centrado por media y normalizado L2;
- `onsetFlux`: suma positiva de diferencias de magnitud normalizada por energía y después mediana/MAD de pista;
- `activity`: proporción de subbloques de 100 ms sobre -70 LUFS;
- loudness absoluto queda separado y nunca entra en H/T/A/C.

Pesos, frecuencias y resolución son calibrables; suma por potencia, normalizaciones y exclusión de loudness son contrato. Vector cero/NaN es degenerado.

### Conformidad oficial y guard de autoridad

Contrato focal ADR-011: fuentes, [94 claves](milestones/M-004/conformance-contract-001/required-keys.001.json), [schema literal](milestones/M-004/conformance-contract-001/schema-delta.001.json) y [30 oráculos finitos](milestones/M-004/conformance-contract-001/oracles.001.json). Ninguna comprobación de esta propuesta midió DSP.

#### 1. Perfil literal y alcance de la afirmación

Se conserva el nombre Java `ConformanceRequirement.OFFICIAL_LOUDNESS_V1`, pero el ID aprobado nuevo sería `QM-OFFICIAL-LOUDNESS-FILE-V1`. `E_REQ=94`, compilado y duplicado de forma independiente en tests. El anexo [required-keys.001.json](milestones/M-004/conformance-contract-001/required-keys.001.json) enumera las 94 claves y su protocolo; no se construye el conjunto requerido enumerando los archivos disponibles. Contiene 39 Integrated ITU (ordinal local de fila, no número oficial de caso), 55 lecturas EBU sobre 51 archivos, 92 oracles finitos y dos categóricos. Son 90 originales distintos medidos de los 109 adquiridos.

EBU exige 1..10/12/13. Los casos 1/2 aportan M/S/I cada uno; 3/4/5/7/8, un I; 6, un I por cada contenedor 5.0 y 5.1 con LFE silencioso; 9/12, una aserción de intervalo constante; 10/13, veinte máximos independientes cada uno. Medir ambos contenedores de 6 es cobertura local explícita, no dos casos normativos. Los casos 11/14 se conservan con `LIVE_ALTERNATIVE_NOT_APPLICABLE_TO_FILE_PROFILE` en inventario/diagnóstico de aplicabilidad; no son fallos DSP ni omisiones silenciosas. Los nueve archivos TP15..23 y ocho auxiliares restantes siguen inventariados; TP no entra en StandardValidationReport.

Pins de bytes originales de manifests, sujetos a change control:

- ITU BS.2217-1: `eb33cd80973eeccbb23efc17a71f37cd307fb040e0b7940f0ef4eb3407714280`.
- EBU Tech3341v4/LTS-5.0: `73a6e342ae20cc4fd7549d90a8ba5c6437c1e738a97a96b37699999ad0794b29`.

Identidad total: set/version, ordinal/caseNumber, signalId literal, ReadingKind, ReadoutMode, sampleRateHz, channels y ChannelLayout. El orden literal es ITU antes de EBU, caso ascendente, signalId ASCII ascendente, ReadingKind M/S/I, ReadoutMode en el orden del anexo y formato/layout. No se normalizan nombres: se preservan incluso los `.wav.wav` originales. Una clave extra, duplicada, reordenada, de otro layout/modo o ausente no puede pasar.

Superar estos mínimos file-based comprueba este perfil sobre una implementación concreta; no certifica todo BS.1770-5, EBU Mode, todas las frecuencias de muestreo, LRA, true peak, medición live ni layouts arbitrarios. BS.2217-1 publicó pruebas para BS.1770-3; se emplean como componentes de regresión del núcleo BS.1770-5, sin cambiar la versión histórica de las fuentes.

#### 2. Valores finitos y ausencia observada

`LoudnessValueKind` es exactamente `FINITE` o `NO_LOUDNESS`. Expected/measured tienen tags distintos aunque compartan el mismo enum. `NO_LOUDNESS` no significa 0 LUFS: sus slots numéricos contienen únicamente raw bits de +0.0 y no se comparan como loudness. La ausencia de una ejecución es ausencia de fila/estado NOT_RUN, UNAVAILABLE o PARTIAL, no un resultado medido NO_LOUDNESS.

Los dos ITU LFE-only (ordinales 21 y 27) requieren NO_LOUDNESS después de consumir el original completo, EOF real y al menos un bloque Integrated completo, con cero bloques sobre el gate absoluto y relativo. No se acepta un valor finito arbitrariamente bajo ni un umbral ficticio. Una lectura NO_LOUDNESS en cualquiera de las otras 92 claves falla. NaN y ambos infinitos se rechazan antes de serializar/retener; un decoder inválido produce FAILED, no un número de sustitución.

Para FINITE se comprueba `measured >= expected - 0.1 && measured <= expected + 0.1` sobre los límites double calculados una vez; la comparación de intervalo usa además `minimum >= expected - 0.1`. No se redondea ni se añade epsilon. Es la implementación inclusiva elegida: evita que la cancelación de `abs(measured-expected)` rechace el extremo representable. Los tests fijan ambos límites y nextDown/nextUp. Expected y tolerance se contrastan por bits con la tabla; el dato recibido nunca decide su propio oracle. Para NO_LOUDNESS, tolerance es +0.0 etiquetado/no aplicable, no una tolerancia LU reducida.

`MeasuredLoudness` de usuario conserva exactamente sus dos fields. La potencia cero de una ventana completa no pasa por `log10(max(power,1e-30))`: se representa como ausencia, y una potencia negativa/no finita es error. Esto no crea un suelo ni cambia Q, trends o gates regionales.

#### 3. Reloj, readouts y núcleo compartido

Cada archivo se mide desde el frame 0 con un núcleo nuevo inicialmente a cero, una inicialización/reset y sin normalización, resampling, compensación temporal, recorte ni padding. Tras consumir un frame, `t=framesSeen` es el extremo exclusivo entero de la ventana. M usa [t-Wm,t), Wm=.4sr; S usa [t-Ws,t), Ws=3sr. Son ventanas rectangulares completas, sin gate ni attack/release adicional. I usa bloques 400 ms con origen 0 y hop 100 ms, 75 % overlap, gate absoluto estricto >-70, promedio de potencia absoluto y gate relativo estricto >L_abs-10. Solo se descarta el bloque de gating incompleto, nunca frames físicamente presentes.

Protocolos cerrados a 48 kHz para este perfil:

- EOF_INTEGRATED: una lectura al terminar F; bloques completos `max(0,1+floor((F-19200)/4800))`.
- STEADY_EOF (EBU1/2 M/S): última ventana completa con t=F. La fuente prescribe estado estable, no ese timestamp: es selección local declarada.
- CONSTANT_INTERVAL: EBU9 S, todos los t enteros de 144001 a F; EBU12 M, de 48001 a F. La elección estrictamente posterior al instante publicado es local. Se guardan count, extremos temporales y mínimo/máximo; no basta un escalar a EOF.
- MAXIMUM_FULL_WINDOWS: EBU10 S, todos los t=144000..F; EBU13 M, t=19200..F. Se conserva el máximo de todas las ventanas completas a resolución de un frame y su primer instante en empate exacto. La rejilla de 100 ms no sustituye esta cuantificación. No se reubican los offsets oficiales de 150/20 ms ni se añaden ceros tras EOF.

Se añade `LoudnessCore` productivo final, mutable solo dentro de una llamada, sin static mutable ni escape. K-weighting, suma de potencias ponderadas, ventanas móviles y gate Integrated quedan en ese mismo núcleo/classfiles para producto y runner. `KWeightingAdapter.fillWindowPowers` conserva firma y alimenta ese núcleo; `LoudnessAnalyzer.analyze` conserva firma y formato 1/2, obtiene sus arrays al mismo grid retenido. Su agregado regional -20 LU no cambia. El runner llama al núcleo real, no a una copia del algoritmo.

El núcleo tiene ring de Ws doubles, diez coeficientes, cuatro arrays de historial por canal y escalares; no produce una timeline por frame. El runner agrega máximos/intervalos a escalares y guarda temporalmente solo las potencias de bloques I completos para la misma función de gating que usa producto. La timeline publicada mantiene M=Q=ceil(F/H100) y sus máscaras/colas; no retiene núcleo, chunks, trazas densas, parser ni runner. El hash del algoritmo abarca todo el cono de medición y verificación fijado en el schema. La refactorización no acredita precisión: solo una futura ejecución oficial podrá hacerlo; si falla se corrige implementación, no oracle.

#### 4. Canales y originales físicamente completos

Layouts explícitos: MONO_MAIN [main] peso1; STEREO_LR [L,R] pesos[1,1]; SURROUND_5_0 [L,R,C,Ls,Rs] pesos[1,1,1,1.41,1.41]; SURROUND_5_1 [L,R,C,LFE,Ls,Rs] pesos[1,1,1,0,1.41,1.41]. Son coeficientes de potencia, no amplitud ni reparto por número de canales. El `dwChannelMask=0x3f` del contenedor 5.1 corresponde a FL/FR/FC/LFE/BL/BR; el mapeo a los cinco canales main/surround del test es explícito, no se afirma máscara SIDE. Los PCM sin máscara usan el orden publicado en el manifest/matriz para su hash exacto. AudioFormat y façade de usuario siguen rechazando >2.

Parser solo tests: acepta exclusivamente RIFF/WAVE PCM signed LE 16/24 y WAVEFORMATEXTENSIBLE PCM con GUID `0100000000001000800000aa00389b71`, validBits igual a containerBits, cbSize válido, blockAlign/byteRate exactos, frames enteros y layout aprobado. Convierte con división por 2^(bits-1), sin ganancia ni dither. Valida cada chunk contra el tamaño físico con aritmética long exacta, un solo fmt/data, padding RIFF y tamaños; omite chunks no-audio completos sin transformarlos. Nunca usa AudioSystem como reparador implícito. Root absoluto externo read-only, rutas de tabla fijas, sin enlaces/reparse/traversal, sin escritura ni downloader.

Se tolera solo que el límite exterior RIFF sea corto para estos tres hashes/tamaños exactos, manteniendo completos los data chunks físicamente acotados:

| Original | SHA-256 | Físico / RIFF declarado / data bytes / frames |
|---|---|---|
| ITU Stereo VinL+R -24 | eaa3eff1f4aec58dbcd0d0ece25efb8576bed6c11c293bce237566cc78970ed0 | 15966176 / 15966168 / 15966108 / 3991527 |
| ITU Mono Voice+Music -24 | fd7b534e2097601b2393b7e0b84392ee640d869e3d54bd5554a999c3e2da881d | 7997766 / 7997758 / 7997698 / 3998849 |
| EBU6 WAVEEX | c7a5b24cfedfd9c781beafd05547e9dd90118ab6a78c4ccf181a02ae85160fc0 | 11520080 / 11520068 / 11520000 / 960000 |

No se reparan originales. La lectura conserva respectivamente los últimos 2/4/1 frames que perdería el límite RIFF defectuoso. Cualquier otra anomalía o truncamiento físico falla por contenedor, no por diferencia LUFS. Se verifican hashes originales antes y después, contador real de frames consumidos, estado inicial, EOF y hash del flujo leído; el oracle estructural exige F exacto. Chunks <=65536 frames; PCM transitorio máximo 65536*6 floats; buffer bytes <=65536*18; ningún audio entra en Git/JAR/log/report/app-image. Los tests de parser usan copias sintéticas locales propias para corromper cabeceras, no modifican estos originales.

#### 5. Atestación sin autohash y factory realizable

`ConformanceRun` pasa a ser input inmutable del codec con attempted/licencia, IDs/hashes de algoritmo/perfil/runner, timestamp, dos manifests y evidencia; no acepta un estado PASSED declarado. El estado se deriva. `ConformanceCodec` es cerrado, determinista y sin I/O; solo fields del schema, claves ASCII ordenadas, arrays en orden literal, UTF-8 sin BOM/whitespace/newline, enteros decimales canónicos y doubles como 16 hex lowercase de raw IEEE bits en strings. Rechaza claves extra/faltantes/duplicadas, orden no canónico, escapes equivalentes y tamaño excesivo; decode+encode debe reproducir exactamente los bytes. El recurso y el perfil tienen cap independiente de 262144 bytes cada uno.

`profileSha256` es SHA-256 de esa serialización de los 94 requirements y constantes/parser/readout/layout/pins, excluyendo SHA de algoritmo, attestation, timestamp y paths locales. El anexo fija el objeto/proyección; su reserialización compacta sin newline produce los bytes runtime y profileSha256, distintos del hash del archivo documental. `algorithmSha256` usa la lista literal de classfiles productivos del schema, ordenada ASCII: dominio ASCII `QM-LOUDNESS-CLASS-CONE-1`, número u32 BE, y por entrada longitud u32 BE del nombre UTF-8, nombre, longitud u64 BE de bytes, bytes. No se hashea el JAR completo dentro de sí mismo. `runnerSha256` aplica el mismo framing con dominio `QM-LOUDNESS-RUNNER-1` a los tres owners test-only del runner/parser/assembler. No se calcula desde source ni nombres/versiones solos.

El payload `META-INF/quickmaster/leveler-conformance.json` NO contiene `attestationSha256` ni su propio hash indirecto. El JAR manifest fija `QuickMaster-Loudness-Attestation-SHA256` = SHA-256 exacto de ese payload y `QuickMaster-Loudness-Runner-SHA256`. El loader valida ambos, los classfiles efectivos del mismo JAR y el perfil compilado/recurso. Solo entonces materializa el attestationSha256 del DTO. El algoritmo/perfil no contienen el digest del payload, el runner no contiene su propio digest y el manifest no participa en los bytes del algoritmo: el grafo de hash es acíclico.

`ConformanceArtifactLoader` es el único borde de lectura productivo nuevo. Se ancla al URL de SU classfile, exige protocolo jar con jar-file URL local file y host vacío, obtiene ese JarFile y lee exclusivamente el manifest/entries literales del mismo contenedor. No busca `/META-INF/MANIFEST.MF` en el classpath general, donde podría pertenecer a otra dependencia. Usa try/finally y cierra streams/JarFile, sin red, paths de usuario, ClassLoader personalizado, cache estática ni escritura. En clases explotadas o cuando faltan conjuntamente payload/perfil/ambos atributos devuelve NOT_RUN sin binding (cuatro hashes `""` compartidos); un bundle parcial (incluida eliminación de un recurso con atributos presentes) o alterado/malformado es FAILED. Este borde no concede I/O al DSP ni al resto de owners; sus tuples de llamadas están cerradas en el schema.

`BuildAlgorithmBinding` tiene constructor package-private, no constructor público ni factory arbitrario: el loader productivo crea el binding actual. `StandardValidationReport` conserva el constructor público no-PASSED y añade factory público `verifyBound(requirement,run,binding)`, donde vive la verificación completa y un constructor privado puede producir PASSED. El guard del package padre conserva `verify` y delega únicamente a ese factory. Así no se necesita un token exportado ni acceso imposible entre packages; el acceso privado no pretende ser un sandbox contra reflection/JNI. `verifyBound` recanoniza el run y exige su digest igual al binding observado, además de las 94 identidades/oracles/protocolos y pins. Un objeto público de evidencia o un estado escrito por un test no adquiere autoridad.

`report.matches(currentBuildBinding)` exige state PASSED, IDs, los cuatro digests y timestamp iguales, todos completos. El build/dependencias fijadas son confiables; no se promete distinguir una ejecución real de una falsificación perfecta hecha por un desarrollador que modifique conjuntamente empaquetador, código y hashes. Sí se detectan las mutaciones finitas por valor/clave/hash/layout/readout/versión/paquete del anexo. Los tests sintéticos validan comparadores/schema/protocolo/negativos y nunca generan la atestación PASSED incluida en el producto. Un caso positivo oficial solo proviene del runner que decodificó los originales, consumió cada frame y ejecutó el núcleo efectivo del build.

Estados: no intento y cero filas = NOT_RUN; intento sin ninguna clave ejecutable por ausencia de corpus/autorización = UNAVAILABLE; alguna lectura válida sin fallos pero faltan claves = PARTIAL; cualquier identidad/bytes/valor/protocolo/binding inválido = FAILED dominante; solo 94/94 con ambos sets completos = PASSED. Ausencia esperada de corpus no se simula como excepción DSP. Error de lectura/parser después de empezar, sin poder completar un archivo, es FAILED; no convierte una lectura requerida ausente en éxito categórico. La autorización EBU existente es válida; solo el runner de tests accede a su documento externo y registra el booleano, sin empaquetar conversaciones/licencias/audio.

#### 6. Pipeline de empaquetado propuesto

Tras preflight y autorización, un único Builder de conformidad es owner de producto loudness/DTO/codec/loader y tests oficiales; root coordina cualquier field/guard compartido con los Builders Musical/Guard. La reserva Maven/target pertenece al owner asignado por root; al cierre documental es Builder integración INTENT317/RUNNER318, y no se toca sin liberación explícita. No se acepta una prueba firmada por un hash anterior.

Se propone fijar Maven AntRun 3.2.0 y JAR plugin 3.5.1, sin dependencia runtime nueva. Después de la suite Surefire, `prepare-package` ejecuta el `ConformancePackageAssembler` test-only con `maven.test.classpath`, failonerror. Siempre genera un payload nuevo: por defecto NOT_RUN sin roots/credenciales; con `-Dqm.officialLoudness=true` lee roots externos explícitos y la autorización ya existente, ejecuta los 90 archivos/94 lecturas y exige PASSED, o falla el gate oficial con evidencia no-PASS. No hace skip JUnit ni reutiliza una atestación previa. Un clean package normal sigue verde sin corpus y no afirma oficialidad.

El assembler calcula digests después de la última compilación, ejecuta el núcleo real, valida la evidencia, escribe los dos recursos canónicos y un manifest generado en target/conformance; JAR plugin usa `archive.manifestFile`. El mismo assembler se invoca en phase package, después del JAR default, para reabrir y verificar el paquete real: class-cone/perfil/payload/atributos, unicidad/ausencias y ningún audio, agente o runner. Error de orden, clases recompiladas, recursos stale, manifest ausente, copia incompleta o hashes discordantes falla package. El registro futuro conserva comandos, bytes/hash, JDK, SOURCE_DATE_EPOCH (0 por defecto; timestamp de ejecución explícito para run oficial), salidas y causas. Repetición con mismos inputs/timestamp debe producir exactamente perfil/payload/digests iguales; el ZIP completo puede tener otras fechas y se verifica aparte por AC12.

La falta de PASSED no bloquea una entrega de sombra honesta. M-005 mantiene sin cambio `report.state()==PASSED && report.matches(currentBuildBinding)`, más sus demás gates; no-PASS deja unidad/candidato no publicable y Dense con autoridad. TP15..23 conserva +.2/-.4 dBTP, su propia prueba y milestone. Todo cambio futuro de código/config/paquete exige suite completa, jpackage soportado, despliegue íntegro, hash y arranque/log de AGENTS; esta misión no los ejecuta porque no implementa cambios de aplicación.


El predicado de autoridad de M-005 es material y único: `report.state()==PASSED && report.matches(currentBuildBinding)`. Para `NOT_RUN`, `UNAVAILABLE`, `PARTIAL` o `FAILED`, el candidato nuevo termina en unidad con `STANDARD_VALIDATION_FAILED` y **no** se asigna ni adopta; el `DenseGainSchedule` legado conserva autoridad. M-004 puede calcular sombra y exponer el estado honesto, pero nunca puede abrir ese guard. [UC-001/003/006; RF-001/006/012; RNF-003/004/007..009]

### Segmentación, regiones y vetos irrevocables

La extensión fuente `F` tiene una sola autoridad retenida: `AudioFormat.frames`. O2 pasa directamente `source.frames()` como argumento primitivo no retenido a `BoundaryDetector.detect(features,totalFrames,LevelerCalibrationProfile.V1)` desde `LevelerAnalysisEngine.analyzeShadow`. Se fija `final class BoundaryDetector` package-private, sin fields, y un único `detect` de instancia package-private con la firma nueva; desaparecen firma antigua e `inferTotalFrames`. La visibilidad reduce uso accidental, no es un sandbox. El engine y sus dependencias fijadas son código confiable. [UC-001/002/003; RF-001/003/005; RNF-003..005/009]

Antes de novelty o reserva/trabajo proporcional al extent rechazado se validan `features/profile!=null`, `F>0`, `H=features.hopFrames()>0`, `int count=features.size()` y `long N_expected=1L+((F-1L)/H)`. La comparación es `N_expected==(long)count`; desigualdad retorna `INSUFFICIENT_FEATURES`/empty. Después se recorren solo los count elementos existentes: `start=Math.multiplyExact((long)i,H)`, `length=min(H,F-start)>0`, `center=Math.addExact(start,(length-1L)/2L)` y centro real igual al esperado. No se copia un array de centros. Null, overflow o mismatch produce fallo vacío sin excepción ni partición parcial. Solo se permite la reserva constante del resultado de fallo. Tras validar todos los centros, count=1 retorna `[0,F)` antes de calcular radios; los demás casos usan count para arrays/iteración y frontera interna `b*H` exacta, con el último end literalmente F. La implementación canónica conserva cálculo/comparación long; un range-check y conversión comprobada semánticamente seguros no son un fallo por sí mismos. La garantía se verifica por resultado, memoria y fase observada, sin proof de todas las expresiones equivalentes. [UC-001/002/003; RF-001/003/005/006; RNF-003..005/009]
La extensión exacta no es derivable del schema previo. Sea `L=F-(N-1)H`, `1<=L<=H`; el último centro solo codifica `floor((L-1)/2)`. Para todo `k` con `2k+2<=H`, las colas `L_1=2k+1` y `L_2=2k+2` producen el mismo centro, el mismo `N` y pueden producir exactamente los mismos 22 rasgos —por ejemplo con PCM cero— aunque `F_2=F_1+1`. En particular, con `H` par, `L=H-1` y `L=H` colisionan; la heurística histórica que promovía `H-1` a `H` devolvía `H` para ambos y cerraba mal la primera pista. Por tanto queda prohibido `inferTotalFrames`, cualquier regla de paridad/centro, padding implícito o codificar F en otro rasgo. [UC-001/002; RF-001/003; RNF-003/004/009]

Escalas nominales V1 s=2/4/8: `R_s=max(1,StrictMath.round(s/p.structuralHopSec()))`, p.structuralHopSec()=.5 ⇒ 4/8/16 hops. No se infiere rate ni cambia detect. Fórmula física anterior equivalente solo sr par o impar>=31: en impares cociente `2s-2s/(sr+1)`, round=2s si sr+1>=4s. AudioFormat admite impares1..29: sr1→2/4/8 y sr3→3/6/12 físicos. Dominio residual explícito ADR010 §4, sin restringir rates/cambiar F/H/centros ni afirmar equivalencia universal/calibración. Fixtures H extremo prueban extent, no segundos. En `b`, pre es `[max(0,b-R_s),b)` y post `[b,min(N,b+R_s))`; son intervalos semiabiertos y usan todos sus hops reales, incluso cerca de bordes. `median` y todo percentil usan nearest-rank inferior: ordenar ascendente y tomar índice `ceil(p*m)-1` para `0<p<=1`; por tanto la mediana par es la inferior. Chroma/spectral se agregan por mediana componente a componente, activity/flux por mediana escalar. Sean `cp/cq`, `sp/sq`, `ap/aq`, `fp/fq` esos agregados:

```text
dChroma = 0                         si ||cp||,||cq|| <= 1e-12
          1                         si exactamente una norma <= 1e-12
          1-clamp(dot(cp,cq)/(||cp||||cq||),0,1) en otro caso
dSpectral = 0                       si ambas normas <= 1e-12
            1                       si exactamente una norma <= 1e-12
            (1-clamp(dot(sp,sq)/(||sp||||sq||),-1,1))/2 en otro caso
dActivity = abs(ap-aq)
dFlux = abs(fp-fq)/(abs(fp)+abs(fq)+1e-12)
raw_s(b) = .5*dChroma + .3*dSpectral + .1*dActivity + .1*dFlux
```

Todo se acumula en `double`, en orden de componente ascendente y sin FMA. Un valor no finito invalida el análisis. Para normalizar cada escala sobre sus `N-1` valores: `m_s=P50(raw_s)`, `mad_s=P50(abs(raw_s-m_s))`, `den_s=mad_s` si es `>0`, en otro caso `max(P95(raw_s)-m_s,1e-12)`, y `z_s(b)=max(0,(raw_s(b)-m_s)/den_s)`. La novelty final es la media aritmética izquierda-a-derecha `Q(b)=(z_2+z_4+z_8)/3`; nunca intervienen loudness, gap, H/T/A/C ni target. [UC-001/002; RF-001..003; RNF-001/004/005/009]

ADR-010 §4: una sola originalNovelty(features,F,p) produce Q original double[N-1], null ante invalidez/no finito; N=1 válido da double[0] sin rawNovelty. Valida F/H/cardinalidad long/todos los centros antes de fase proporcional. detect conserva guards/salida N=1 ADR009 y reutiliza Q en retries. rawNovelty conserva nombre/firma/JDI. Builder evalúa una vez Q para todos los contextos: Q(bH)=array[b-1], Q(0/F)=+0.0; comprueba EOF parcial antes de alineación; interior no múltiplo de H falla. Nunca novelty adyacente/seleccionada ni segundo detect. Dos evaluaciones temporales/READY N>1, ninguna Q retenida; oráculos raw-bit ADR010 §6.

La selección tiene un orden total. En el intento inicial, `m=P50(Q)`, `mad=P50(abs(Q-m))` y el umbral es `m+3*mad` si `mad>0`; si `mad==0`, es `P95(Q)`. Siempre se exige `Q(b)>threshold && Q(b)>0`: la igualdad se rechaza. Es máximo local si ningún `j` con `abs(frame_j-frame_b)<=StrictMath.round(2*sampleRateHz)` tiene Q mayor; en empate de bits gana el menor frame/índice. Después se ordenan máximos por `(Q descendente,frame ascendente)` y se aceptan greedily solo si distan al menos `StrictMath.round(2*sampleRateHz)` de todos los ya aceptados; al final se reordenan por frame. [UC-001/002; RF-001/003; RNF-004/005/009]

Si `S=boundariesInternas+1>64`, se descarta por completo esa selección y se repite desde la **misma Q original**, primero con umbral estricto `P97.5(Q)` y después con `P99(Q)`, ejecutando de nuevo máximo/separación. Se acepta el primer intento con `S<=64`. Si P99 aún da `S>64`, el resultado es `TOO_MANY_SEGMENTS`/unidad. Queda prohibido truncar, escoger 63 por ranking, encadenar solo supervivientes o probar percentiles en otro orden. Fronteras finales son `0`, las internas y `F`; IDs `S000..` se asignan por inicio. [UC-001/002; RF-001/003; RNF-004/005/009]

Goldens `FeatureTimeline`, todos con chroma `[1,0..]`, spectral `[1,0..]`, flux `0`, clock .5 s y cola completa; solo cambia activity, de forma que retirar/reordenar una regla cambia boundaries:

- `FT_MAD0_TIE`: `N=1025`, activity `.2` para `i<512` y `.8` después. `mad=0`; el máximo Q es exactamente igual en `b=511,512,513,514`, gana `b=511`; layout `[0,511H)`/`[511H,F)`, IDs `S000/S001`. El valor exactamente igual a P95 no es candidato.
- `FT_EXACT_64`: `N=131073`, `K=63` transiciones `t_j=1024*j`, `j=1..63`; activity alterna `.2/.8`. Produce exactamente 63 internas y 64 regiones en el intento inicial. La frontera `j` es `b_j=t_j-1` para j impar (subida) y `b_j=t_j-2` para j par (bajada); IDs `S000..S063`.
- `FT_PERSISTENT_65`: igual con `K=64`. P95, P97.5 y P99 valen 0; los tres intentos conservan las 64 internas de la misma fórmula, luego `S=65`, status `TOO_MANY_SEGMENTS` y ninguna partición truncada.
- `FT_RETRY_ORDER`: `N=30000`, transiciones `t_j=1000+300*j`, `j=0..79`. En `j=0..29` alterna `.2/.8`, en `30..49` `.2/.6` y en `50..79` `.2/.4` (cada bloque tiene cardinal par y vuelve a `.2`). El intento inicial conserva 80 internas (`S=81`); P97.5 conserva exactamente las primeras 50 y retorna `S=51`, con `b_j=t_j-1` para j par y `t_j-2` para j impar, IDs `S000..S050`. P99 conservaría solo las primeras 30 (`S=31`), por lo que saltar P97.5 o elegir el resultado más pequeño falsifica el golden.
- `FT_EXACT_TAIL_ALIAS`: con `H=24000`, dos frames estructurales idénticos y centros `[11999,35999]` son válidos tanto para `F=2H-1=47999` como para `F=2H=48000`. La misma instancia de `FeatureTimeline` pasada con cada extensión produce `READY`, una partición contigua y un último `endExclusive` exactamente `47999`/`48000`; ninguna inferencia desde el centro puede pasar ambas ramas.
- `FT_INVALID_EXTENT`: `F<=0`, `N_expected!=features.size()` y un centro que difiere en un frame del centro canónico producen `INSUFFICIENT_FEATURES` y cero regiones antes de novelty. Incluye necesariamente `F=4294967297L,H=1L,features.size()=1`: el valor correcto es `N_expected=4294967297L`, por lo que se rechaza antes de novelty o reserva dependiente de N; el mutante `(int)N_expected==1` lo aceptaría y debe morir. Como positivos de frontera, `F=1,H=1,center=[0]` y `F=Long.MAX_VALUE,H=Long.MAX_VALUE,center=[4611686018427387903]` producen `READY`, una única región `[0,F)` y end exacto sin estrechar N. Los cuatro goldens de novelty/retry anteriores pasan explícitamente `F=N*H`, no lo derivan de sus centros.
- `ENGINE_EXACT_TAIL_COLLISION`: `LevelerAnalysisEngine` completo se ejecuta con PCM cero mono y `AudioFormat(48000,1,F)` para ambos `F=47999` y `F=48000`. Ambos snapshots son `DONE`, conservan `H=24000`, dos StructuralFrame con centros `[11999,35999]`, layout `READY` desde 0 y último `endExclusive` respectivamente 47999/48000, idéntico a `snapshot.cache().format().frames()`. Esta pareja exacta, no una “cola no múltiplo” arbitraria, detecta que el caller haya movido la inferencia/promoción a 48000 antes de llamar al detector.

#### Impacto literal de la enmienda de extensión exacta

| Superficie | Consecuencia cerrada |
|---|---|
| Firmas | Se sustituye, sin conservar overload, `detect(FeatureTimeline,LevelerCalibrationProfile)` por `detect(FeatureTimeline,long totalFrames,LevelerCalibrationProfile)`. `StructuralFeatureExtractor.extract`, `FeatureTimeline`, `StructuralFrame`, `SegmentLayout`, `AudioFormat` y `analyzeShadow` no cambian de firma. El único call site directo first-party aprobado pasa `source.frames()` sin fórmula; clase final y método pasan a package-private. |
| Fields/modifiers | No se añade, elimina ni reordena ningún field productivo. `FeatureTimeline` conserva exactamente `private final long hopFrames` y `private final FrozenList<StructuralFrame> frames`; `AudioFormat` conserva sus tres fields y sigue siendo la fuente retenida única de F; `BoundaryDetector` no gana estado; la reducción de visibilidad de clase/método no cambia fields ni layouts retenidos. |
| Whitelist y grafo | ADR-009 no añade nodos por el argumento long de extensión. La faceta posterior ADR-011 añade solo los fields/atoms enumerados; el schema vigente es el delta compuesto y hasheado de la sección de whitelist, sin reabrir O2. |
| Identidades/arrays/edges | El parámetro de extensión aporta delta cero. Los polinomios actuales son los de la sección Whitelist, incluyendo +1+4E edges por ADR-011 y U=H_b+J+2E; no se duplica aquí otra fórmula. |
| Payloads | No cambia ningún término: loudness, features, bins, sketches, scores, grupos, weights, fingerprint y Strings conservan sus cotas; no aparece `k*F`, `F/q` ni otro array. Los contadores usan el mismo `source.frames()` que ya recibían y no contabilizan el argumento. |
| `SNAPSHOT_MIN/MAX` | O2 por sí mismo no cambia layouts. La faceta conformidad posterior sí cambia report/evidencia: MIN_BOUND169/91/191, MIN_UNBOUND161/87/187 y MAX60M_BOUND31889/19006/35411 para E94; shallow sizes reales se vuelven a medir. |
| Hashes | ADR-009 cambia sus dos owners y tests sin modificar el antiguo perfil de conformidad. Bajo el perfil posterior ADR-011, LevelerAnalysisEngine está además en el cono loudness explícito de 26 classfiles: un cambio de ese cono invalida su atestación. Se recalculan hashes del build, guard y schema compuesto solo después de tests y change control. |
| Classfile guard | El guard de wiring directo O2 permanece separado e intacto. El universo/allowlist semántico se amplía únicamente por los owners/tuples exactos de ADR-011; ningún wildcard ni owner M-003 extra. |
| Payload de entrega | AC12 inventaría el árbol final completo generado por jpackage y exige igualdad bidireccional source app-image↔destino de paths/tipos/tamaños/SHA-256; JAR target↔app-image↔instalado, EXE, runtime y arranque se verifican aparte de la segmentación. |
| Tests FT | Detector: F explícito, misma colisión 47999/48000, inválidos y extremos long. Engine real: ambos PCM cero mono/48 kHz, H=24000, centros [11999,35999] y ends exactos. Una JVM hija de 64 MiB prueba rechazo sin excepción, asignación <=16 KiB por llamada y cero novelty; mutantes de cast/reserva previa/orden fallan y conversión comprobada segura pasa. |

La enmienda vigente está aceptada en [ADR-009](decisions/adr/ADR-009-functional-track-extent-and-delivery-integrity.md). ADR-008 se conserva histórico. La [decisión 011](milestones/M-004/orchestrator-decision.011.json) acepta el [preflight 011](milestones/M-004/preflight-critic-review.011.json) y autoriza la construcción acotada; no se reabren fórmulas musicales ni autoridad de ADR-007.

### Oráculos funcionales y de wiring de la extensión

`BoundaryDetectorTest` conserva los cuatro goldens de novelty/retry y usa F explícito. Añade el mismo timeline H=24000/centros [11999,35999] con F=47999 y 48000, ambos READY con end exacto; null, F<=0, count distinto y centro desplazado en 1 fallan. F=4294967297/H=1/size=1 rechaza el alias de 32 bits. Los extremos directos `F=1,H=1,center=0` y `F=Long.MAX_VALUE,H=Long.MAX_VALUE,center=4611686018427387903` dan una sola región `[0,F)`.

`LevelerAnalysisEngineIntegrationTest` ejecuta separadamente el engine real y completo con dos PCM cero mono de 47999 y 48000 frames, `AudioFormat(48000,1,F)` y token normal. Exige snapshot DONE, features H=24000/count=2/centros [11999,35999], layout READY desde 0 y end igual a cada `cache.format().frames()`. Ningún stub del extractor/detector ni timeline inyectado sustituye esta prueba. Los extremos long anteriores son tests del detector: `AudioFormat` limita PCM a un array Java y no se fabrica un PCM de Long.MAX_VALUE. [UC-001/002/003; RF-001/003/006; RNF-003..005/009]

El guard dirigido reutiliza el decoder test-only existente sobre todos los `.class` bajo `target/classes` de la compilación limpia, incluidos internos/synthetic que existan. Ese directorio es el output first-party del módulo Maven actual; no se deriva de dependencias ni del payload de jpackage. Lee instrucciones invoke y resuelve únicamente sus operands CP. Exige una referencia de método usada y una sola instrucción `invokevirtual` al descriptor nuevo, dentro de `LevelerAnalysisEngine.analyzeShadow(float[],AudioFormat,CancellationToken)`. Una CP entry sin uso no cuenta. En la tabla del detector exige clase final no pública, detect package-private no static/synthetic/bridge/native, firma única y ausencia de firma vieja/inferTotalFrames. [UC-001/002/003; RF-001/003/005; RNF-003..005/009]

Para el argumento directo basta comprobar en el site decodificado el sufijo `aload_2; invokevirtual AudioFormat.frames()J; getstatic LevelerCalibrationProfile.V1; invokevirtual BoundaryDetector.detect`, con owners/descriptors exactos. `source` es el segundo parámetro del método de instancia. Este check reconoce la forma literal convenida, no calcula CFG, taint, handler-origin ni equivalencia interprocedural. El engine completo prueba el resultado relevante. Un segundo caller first-party, overload/firma vieja, inferencia o `+1/-1` fallan el código `BOUNDARY_DIRECT_WIRING`; el positivo Java 17 sin debug pasa. Reflection legítima del resto de QuickMaster/dependencias no recibe una prohibición nueva. [UC-001/002/003; RF-001/003/005; RNF-003..005/009]

### Oracle de rechazo temprano y memoria

Es una prueba pequeña separada del benchmark de retención 1/10/60 minutos. El harness test-only lanza secuencialmente JVMs hijas del JDK soportado con `-Xms16m -Xmx64m -XX:+UseSerialGC` y timeout total 60 s por hija. El padre recoge stdout/stderr sin bloquear y solo termina su PID hijo al vencer el timeout. No se reserva PCM para F inválido. La hija usa las clases compiladas reales; variantes mutadas van en classpaths temporales explícitos y separados. JDK home, flags y hashes se guardan con resultados. [UC-001/003; RF-001/005/006; RNF-003..005/009]

Cada timeline/profile se crea antes de medir. Se habilita `com.sun.management.ThreadMXBean` de asignaciones si está soportado, se hacen 128 llamadas de warm-up y 32 medidas por caso. El delta de `getThreadAllocatedBytes` del thread que llama a detect, tomado inmediatamente antes/después de cada llamada, debe estar en `[0,16384]` bytes. Resultados, serialización y construcción de fixtures quedan fuera del delta. No se usa memoria libre del heap. Una lectura negativa, API ausente/no habilitable, OOM, excepción, timeout, exit no cero o resultado distinto falla; no se convierte en skip/PASS. El límite de 16 KiB deja margen sobre el retorno vacío constante y discrimina reservas de MiB antes del rechazo. [UC-001/003; RF-001/005/006; RNF-003..005/009]

| Caso inválido | H / size / centros | Propósito |
|---|---|---|
| F=2 | 1 / 1 / [0] | mismatch pequeño |
| F=4294967297 | 1 / 1 / [0] | cast a int da 1 |
| F=4296015872 | 1 / 1 / [0] | cast da 1048576; una reserva previa supera 16 KiB |
| F=5368709119 | 1 / 1 / [0] | cast da 1073741823; heap de 64 MiB protege al host |
| F=Long.MAX_VALUE | 1 / 1 / [0] | cast negativo/overflow sin excepción aceptable |
| F=48000 | 24000 / 2 / [11999,36000] | cardinalidad válida, centro incorrecto antes de novelty |

Todas las llamadas deben devolver `INSUFFICIENT_FEATURES`/empty. Se observa cero entradas a `BoundaryDetector.rawNovelty` desde fuera del producto: JDI `LaunchingConnector` arranca la hija suspendida, el padre habilita `MethodEntryRequest` filtrado por el nombre exacto del detector con `SUSPEND_NONE`, reanuda y drena eventos hasta desconexión. Cuenta únicamente las entradas del método rawNovelty. La hija mantiene el conteo medido de invocaciones/resultados; el padre exige que observó entradas a detect y que el observador estuvo activo hasta el cierre. Un observador sin clase/método, desconexión prematura, discrepancia de llamadas o cola no drenada falla. El positivo separado F=48000/H=24000/centros [11999,35999] debe alcanzar rawNovelty y READY. Se ejecutan también los dos extremos válidos en una hija sin exigir novelty cuando size=1. JDI no transforma clases, añade fields/hooks, participa en producción ni aporta cambios al schema de retención vigente. [UC-001/002/003; RF-001/003/005/006; RNF-003..005/009]

Capacidades comprobadas en el host: Temurin 25.0.4 contiene `jdk.management`/`jdk.jdi`; `javap` confirmó `isThreadAllocatedMemorySupported`, `setThreadAllocatedMemoryEnabled`, `getThreadAllocatedBytes`, `MethodEntryRequest.addClassFilter`, `EventRequest.SUSPEND_NONE` y `LaunchingConnector.launch`. Esto verifica disponibilidad, no afirma que el futuro harness ya se haya ejecutado. CI Temurin 21 conserva su verificación propia antes de release.

Mutantes finitos obligatorios, derivados del detector real en una copia de tests: (a) sustituir la comparación long por `(int)expected==count`, o dejar que esa igualdad truncada la eluda; (b) una reserva `new byte[(int)ceil(F/H)]` previa al guard canónico, inline y mediante helper privado; (c) entrar en novelty antes de validar centros. El array mutado se mantiene observable durante la llamada para impedir eliminación por JIT; no entra al producto. El primer mutante falla status para 2^32+1; el segundo falla asignación/heap para las otras F aunque luego rechace; el tercero falla el contador de fase. Al retirar solo el presupuesto, el caso de reserva de 1048576 bytes sigue dando el status correcto y muestra por qué el presupuesto era necesario; al retirar solo el contador, el caso de centro sigue rechazando después de novelty y muestra la sensibilidad del orden. Un fixture positivo con range-check y `Math.toIntExact(expected)` después de las validaciones pasa. No se afirma detectar toda reserva minúscula imaginable ni probar todos los helpers: esta matriz distingue las regresiones concretas del REPLAN. [UC-001/002/003; RF-001/003/005/006; RNF-003..005/009]


`SegmentDescriptorBuilder.structuralBins(features,range,F)` es el único rebinning central/perturbado: 32 bins por área sample-and-hold, incluida cola. Para [u,v), D=v-u, bin k=[kD,(k+1)D), hop i=[(iH-u)*32,(min(F,(i+1)H)-u)*32); componente=sum(overlap*value)/D, orden i ascendente/sin FMA y long exacto. Valida metadatos/rango y centros tocados; null ante invalidez. Barrido solo de hops intersectados, sin interpolar/desplazar índices.

ADR-010 §5 define normativamente trends: I=posiciones originales de ventanas Short-term completas contenidas; q=max(1,floor(|I|/4)), cuartiles geométricos antes de máscara. Dentro solo shortTermValid/finito, sin gates regionales. Delta=medianas inferiores de valores último−primero. Centros dobles t2_i=2*i*H100+Wq−1; dt=diferencia de medianas inferiores de centros válidos/(2.0*sr), independiente del orden de valores; slope=delta/dt. Consistency usa TODOS los i..i+5 contiguos válidos: L[i+5]−L[i], ignorando abs(d)<=.05; fracción del signo de delta, o0 si delta=0/no diferencias materiales. Lapso físico5*H100/sr, nominal .5 s; no compresión/agregado/submuestreo. Cuartil ausente/dt<=0/no finitud/ningún lag5 válido: triples+0.0 y regional final absent, no falsa estabilidad. Actividad usa cuartiles/medianas de valores y centerFrame reales en región; menos de dos centros distintos ⇒ slope+0.0/regional absent. Foreground/spread, medición regional y thresholds D-301/D-302 intactos. Fixtures T-* de ADR010 §6. Para el sketch PCM, toda región no vacía de `F_r` frames y `C` canales produce exactamente `B=2048` bins relativos por canal mediante un integral sample-and-hold, aun si `F_r<B`. En coordenadas enteras escaladas, bin k cubre `[k*F_r,(k+1)*F_r)` y sample local n cubre `[n*B,(n+1)*B)`. Con `lo=k*F_r`, `hi=(k+1)*F_r`, `n0=floorDiv(lo,B)`, `n1=ceilDiv(hi,B)`, se define:

`sketch[c][k] = (Σ_{n=n0}^{n1-1} overlap([lo,hi),[nB,(n+1)B))*pcm[(start+n)C+c]) / F_r`.

Productos de índices usan `Math.multiplyExact(long,long)`; overlap es `long`, suma `double` en n ascendente, luego división. Los intervalos particionan exactamente toda muestra/cola; para una región de un frame los 2.048 bins repiten ese sample. No hay interpolación por índice, padding, decimación que omita cola ni copia PCM retenida. [UC-001/002; RF-001..003; RNF-003..005/009]

`ProtectionClassifier` se ejecuta antes de comparar. La unión de razones es irrevocable:

| Razón | Gate V1 | Efecto |
|---|---|---|
| `SILENCE_OR_SPARSE` | sin ventanas válidas o >=50 % bajo -70 LUFS | 0 dB |
| `INTRO_EDGE` / `OUTRO_EDGE` | primera / última región | 0 dB aunque se repita |
| `FADE_OR_CRESCENDO` | `abs(deltaL)>=3 LU`, `abs(s)>=.25 LU/s`, >=75 % mismo signo | 0 dB |
| `BREAK_OR_BREAKDOWN` | interior >=3 LU bajo ambos vecinos y activity >=25 % menor | 0 dB |
| `TRANSITION` | duración <3 s, o novelty alta en ambos bordes y dispersión >mediana+3 MAD | 0 dB |
| `TREND` | `abs(sL)>=.10 LU/s`, cambio >=2 LU o `abs(sActivity)>=.01/s` | 0 dB |
| `MACRO_BUILDUP` | >=3 intervalos, >=75 % mismo signo, acumulado >=3 LU o .20 activity | 0 dB |

Después `BodyContextGate`, sin H/T/A/C/margen/gap, exige predecesor/sucesor >=3 s; foreground >=.80; `p90-p10 activity<=.15`; `abs(sActivity)<.01/s`; `abs(sL)<.10 LU/s`; cambio `<2 LU`; vecinos foreground >=.50 y contexto finito. La clase de signo es `-1` para delta `<-1 LU`, `0` para `[-1,+1]` inclusive y `+1` para `>+1`; entrada usa `L_r-L_prev` y salida `L_next-L_r`, y ambas clases deben coincidir entre regiones.

El vector contextual único es `B_r=(a_prev/a_r,a_next/a_r,clip((L_r-L_prev)/12,-1,1),clip((L_next-L_r)/12,-1,1),Q(left),Q(right))`; exige `a_r>1e-12` y todos sus operandos finitos. `Q(left/right)` es la novelty base anterior a cualquier retry. Tolerancias, en ese mismo orden, son `T=(.25,.25,.25,.25,1,1)` y `D_ctx=max_i(abs(B_a[i]-B_b[i])/T[i])`, acumulado por i ascendente. Contexto homologable exige clases de signo iguales y `D_ctx<=1`; esto es exactamente la antigua tolerancia de .25 para los cuatro primeros y 1 MAD para bordes. No existe otra distancia contextual en el producto.

Para alinear sketches, lag `ell in {-4..4}` empareja `x[c][k]` con `y[c][k+ell]` para `I_ell={k|0<=k<2048 && 0<=k+ell<2048}`. `O=2048-|ell|`, cobertura `O/2048` y se exige `>=.99` (los nueve lags normativos dan al menos `2044/2048`). El orden de acumulación es k ascendente y dentro canal ascendente; cada canal/bin pesa igual. Se suman en `double`, sin FMA, `sx=Σx²/(C*O)`, `sy=Σy²/(C*O)`, `sxy=Σxy/(C*O)`. Normas deben ser finitas y `StrictMath.sqrt(sx),StrictMath.sqrt(sy)>1e-12`; `alpha=sxy/sy` debe ser finito y `>0`. En una segunda pasada exacta, `sr=Σ(x-alpha*y)²/(C*O)` y `R_scale=StrictMath.sqrt(sr)/max(StrictMath.sqrt(sx),abs(alpha)*StrictMath.sqrt(sy),1e-12)`. Negativo/no finito no se clampa: invalida el lag. Se evalúa `ell` en orden ascendente `-4..4` y se conserva un incumbent: un candidato lo reemplaza si `R<R_best-1e-12`; si `abs(R-R_best)<=1e-12`, lo reemplaza solo cuando `(abs(ell),ell)` es lexicográficamente menor; en otro caso no lo reemplaza. Sin lag válido: `DEGENERATE_SKETCH`/unidad.

La ambigüedad es exactamente `R_scale<=1e-4 && D_ctx<=.05`; la igualdad entra. Si se cumple, `INTENT_UNIDENTIFIABLE`/unidad antes de H/T/A/C/gap. Si no se cumple pero `D_ctx<=1`, el par solo queda habilitado para las puertas posteriores; no se presume intención. Goldens independientes, tolerancia absoluta `1e-15` para alpha/R/D y lag exacto:

| ID | PCM interleaved cerrado (`F=2048,C=2`, resto cero) / contexto | Resultado |
|---|---|---|
| `SKETCH_N08_GAIN_SCALE` | `xL[100]=.5,xL[701]=-.25,xR[311]=.375,xR[1703]=-.125`; `y=.5*x`; ambos `B=(1,1,0,0,.25,.5)` | lag 0, alpha 2, `R_scale=0`, `D_ctx=0`, `INTENT_UNIDENTIFIABLE` |
| `SKETCH_P01_VARIATION` | `xL[100]=.5`; `yL[100]=.5,yR[500]=1/64`; `B_x=(1,1,0,0,0,0)`, `B_y=(1.03125,1,0,0,0,0)` | lag 0, alpha `1024/1025=0.9990243902439024`, `R_scale=1/sqrt(1025)=0.031234752377721213`, `D_ctx=.125`; homologable, no ambigüedad |

El predicado puro se prueba además con `Math.nextDown`, igualdad y `Math.nextUp`: `(nextDown(1e-4),nextDown(.05))` y `(1e-4,.05)` son ambiguos; `(nextUp(1e-4),.05)` y `(1e-4,nextUp(.05))` no. Valores decimales de los vecinos son `9.999999999999999e-5/1.0000000000000002e-4` y `.049999999999999996/.05000000000000001`. El test N08 fija H=T=A=C=1 y gap 6 LU; retirar este `AND` o moverlo después de similitud debe autorizar el par y, por ello, fallar el oracle.

## Comparación, agrupación y referencia

### Score de identidad, sin loudness ni circularidad

`SegmentComparator` solo recibe regiones que pasaron protecciones y `BodyContextGate`. Usa los `L=32` bins centrales del descriptor y descarta pares con ratio de duración fuera de `[.75,1.33]`. Engine pasa sus referencias existentes source/features no retenidas; comparator valida null/F/rangos/H=round(.5*sr)/cardinalidad antes del kernel (H mínimo1), sin inferir rate. Se presupone procedencia de esos descriptores del mismo timeline ya validado; no hay escaneo completo N por par. Contexto inválido falla NON_FINITE; demás fallos centrales conservan su razón. La máscara conjunta `Vij` incluye un bin únicamente si chroma y timbre de ambos lados son finitos y de norma `>1e-12`, y ambas actividades son finitas en `[0,1]`. Se rechaza si hay menos de 24 bins o más de 4 inválidos consecutivos; nunca se imputa ni se usa una máscara distinta por feature. `rho=|Vij|/32` penaliza H, T y A por igual. [UC-001/002; RF-001/002; RNF-004/005/009]

Para `delta=0..11`, `Hraw(delta)=mean(clamp(cos(chroma_i,rotate(chroma_j,delta)),0,1))`; gana el mayor y, dentro de `epsilon=1e-12`, el menor `delta`. Una rotación no nula debe ser la mejor en al menos 80 % de bins; `H=rho*clamp(Hraw-.05*I[delta!=0],0,1)`. Por bin, `t=.8*((1+clamp(cos(timbre_i,timbre_j),-1,1))/2)+.2*(1-|activity_i-activity_j|)` y `T=rho*mean(t)`. DTW usa la misma máscara en índices originales, banda 4, pasos diagonal/vertical/horizontal y coste `.5*(1-cosChroma)+.3*(1-cosTimbreMapped)+.2*|deltaActivity|`; en empate prima diagonal, vertical, horizontal. `A=rho*clamp(1-meanPathCost,0,1)` y `C=min(H,T+.05,A+.03)`. ADR-010 §3 fija ocho ejes en orden a.start−,+;a.end−,+;b.start−,+;b.end−,+, cada uno desde límites centrales. delta=max(1,StrictMath.round(.5*source.sampleRateHz())) frames; clipping seguro [0,F] antes de formar rango, evaluando también no-op de EOF. Solo cambia un borde; no vecinos/resegmentación. structuralBins reextrae el lado modificado; cada variante recalcula ratio [.75,1.33], máscara/cobertura/racha/rho, rotación80%/ties, H/T/DTW/A/C. Vacío/invertido/no evaluable/fallo ⇒ UNSTABLE_BOUNDARY. Exige abs(Aj-Acentral)<=.05 double inclusivo sin epsilon/redondeo, nunca Aprev ni gates de grupo adicionales. Kernel privado compartido con temporales; NONE central solo tras ocho éxitos. Rechazo: H/T/A/C+0.0, rotación0, validBins central si calculado. No repite loudness/sketches/gates. Política axial explícita, no garantía de perturbaciones simultáneas ni calibración; oráculos §6. Ninguna fórmula consulta loudness, gap, target o resultado de gain. [UC-001/002; RF-001/002; RNF-001/004/005]

### Complete-link, separación externa y ruta estricta de pares

`ComparableGroupBuilder` construye la matriz simétrica completa de pares calculables, ordenada por `SegmentId`; la matriz no se modifica en función del cluster. La arista de grupo exige `H>=.85,T>=.80,A>=.82,C>=.85` y estabilidad. Empieza con singletons y fusiona A/B solo si toda pareja cruzada tiene arista. Entre fusiones posibles elige `(min C interno descendente, tamaño unido descendente, lista de IDs ascendente)`; igualdad `<=1e-12` se resuelve por IDs. Solo clusters terminales de al menos tres miembros continúan. La cadena `.93/.92/.70` no forma trío. [UC-001/002; RF-002/004; RNF-001/004]

Para cada grupo G:

- `cohesion=min(Cij, i,j en G)`;
- `external=max({Cie | i en G, e fuera de G elegible y calculable} union {0})`;
- `separation=cohesion-external`, que debe ser `>=.08`;
- `qi=clamp((min_j Cij-.85)/.10,0,1)`;
- `gConfG=smooth(clamp((cohesion-.85)/.10,0,1))*smooth(clamp((separation-.08)/.12,0,1))`.

No existe `min_margin`, confianza declarada ni margen libre. Un score externo empatado registra el ID lexicográficamente menor. La ruta de pareja se ejecuta aparte, nunca convierte un cluster de dos en “grupo”: exige ambos body/context, no `INTENT_UNIDENTIFIABLE`, `H>=.92,T>=.90,A>=.90,C>=.92`, y mejor opción mutua estricta. `second_i(j)=max({Cik|k!=i,j} union {0})`; `Mij=min(Cij-second_i,Cij-second_j)>=.12`; empate `<=1e-12` da `AMBIGUOUS`. `gConfPair=smooth(clamp((C-.92)/.06,0,1))*smooth(clamp((M-.12)/.12,0,1))`. El gap se mira solo después. [UC-001..003; RF-001/002/004/005; RNF-001/004/005]

### Referencia robusta y target bruto

`ReferencePlanner` trabaja dentro de cada grupo aceptado; queda prohibida una mediana global. En grupos `|G|>=3`, parte de `ui=.5+.5*qi` y obtiene pesos por water-filling determinista con cap `.40`: distribuye el resto proporcionalmente, fija en `.40` todos los que exceden en orden de ID, los retira y repite. Verifica `sum=1 +/-1e-12` y cada peso `[0,.40]`. Ordena `(regionalLufs,segmentId)` y toma la primera mediana ponderada inferior cuya acumulación sea `>=.5`. Parejas usan pesos `.5/.5` y punto medio, sin fingir un cap imposible. [UC-001/003; RF-004; RNF-001/004]

Con `d=Lref-Lregion` y deadband `tau=1 LU`, `rawDb=sign(d)*max(0,|d|-tau)`. Un gap no crea elegibilidad. `ReferencePlanner` fija además `gConf` exactamente desde D-401 y `confidenceWeightedDb=rawDb*gConf`, con la multiplicación Java `double` en ese orden. Un valor no finito, región sin loudness suficiente, confianza inválida o cualquier duda produce los tres campos a `+0.0` y razón estable. [UC-001/003; RF-003/004; RNF-001/004/009]

Esta es una frontera de ownership irrevocable: M-004 produce `referenceLufs`, `rawDb`, `gConf` y `confidenceWeightedDb`; no recibe ni lee `ControlState`, Leveling o Speed, no aplica clamps `[-6,+3]` y no crea rampas/schedule. Cambiar ambos knobs no altera ningún bit de `ReferencePlan`, grupos o cache. Solo `GainPlanner` de M-005 calcula `requestedDb=clamp(Leveling*confidenceWeightedDb,-6,+3)`, aplica Speed/capacidad/rampas y puede reducir el resultado. [UC-001/003; RF-004/005; RNF-002/004/005/009]

Golden A=-20/B=-26/C=-20: `referenceLufs=-20`, `rawDb_B=+5`, `gConf_B=0.43860126820672457` y `confidenceWeightedDb_B=2.1930063410336227`; A/C son `+0.0`. Los tres valores se afirman por separado. `2.1930063410336227` no se llama target final ni supone Leveling=1; el futuro GainPlanner, y solo él, lo convierte en solicitud controlada. [UC-001/003; RF-004/005; RNF-001/004/009]

## Plan de ganancia disperso y render

### Controles, límites y continuidad

Se preserva exactamente la API pública real: constantes `MIN/MAX_LEVELING=0/1`, `DEFAULT_LEVELING=.5`, `MIN/MAX_SPEED=0/1`, `DEFAULT_SPEED=.5`; métodos `get/setLeveling`, `get/setSpeed`; y campos persistidos `ChainPreset.levelerOn/leveling/levelerSpeed` usados por JSON, A/B y undo. Los setters siguen clampando a `[0,1]`; un argumento no finito se rechaza conservando el valor anterior. `enabled=false` o Leveling=0 publica bypass exacto. Para cada región autorizada se define primero `r_i=rawDb_i*gConf_i` y el target solicitado `clamp(Leveling*r_i,-6,+3)`; el solver siguiente puede reducir un componente completo, pero nunca cambiar signos ni prioridades. [UC-003/006; RF-004..006/012; RNF-002/003/009]

#### Solver total de rampas competidoras

`RampAllocator` es una función pura. Normaliza `-0.0` a `+0.0`, rechaza números no finitos o geometría no particionada y ordena regiones por `(startFrame,endFrame,SegmentId)`; IDs se comparan lexicográficamente por code point y resuelven cualquier empate. Cada componente maximal de regiones modificables queda entre regiones protegidas reales o fronteras virtuales de pista a 0 dB. Sus regiones son `[a_i,b_i)` en frames fuente enteros, su disponibilidad exacta es `d_i=b_i-a_i`, y ningún frame protegido pertenece al componente. Geometría global inválida produce unidad; falta de capacidad afecta solo al componente. [UC-002/003; RF-003..006; RNF-001..004/009]

Para un componente de `m` regiones y `lambda in [0,1]`, se usa `q_i(lambda)=clamp(lambda*r_i,-6,+3)` y `Tnom=4*StrictMath.pow(.75/4,Speed)` segundos. La topología activa se fija antes de reducir: transición izquierda si `r_1!=0`, interna `j` si `r_j!=r_(j+1)` y derecha si `r_m!=0`. Una transición inactiva reserva cero. Las reservas activas, deliberadamente conservadoras para que su factibilidad sea monótona, son:

```text
R_0(lambda) = max(Tnom, .75*abs(q_1))
R_j(lambda) = max(Tnom, .75*(abs(q_j)+abs(q_(j+1))))   // 1 <= j < m
R_m(lambda) = max(Tnom, .75*abs(q_m))
need_iFrames = sampleRateHz * ((i==1 ? R_0 : R_(i-1)/2)
                             + (i==m ? R_m : R_i/2))
fits(lambda) <=> need_iFrames <= d_i para todo i
```

El orden de operaciones anterior y las comparaciones IEEE-754 `<=` sin epsilon son normativos. La suma de magnitudes del tramo interno mayora `abs(q_(i+1)-q_i)`, de modo que reserva al menos el tiempo de pendiente necesario incluso con signos opuestos. `fits(0+)` conserva `Tnom` para cada transición activa; si falla, no existe solución no nula bajo esta política y el componente publica unidad con `RAMP_CAPACITY_ZERO`. Si cabe, el objetivo único es maximizar el Leveling efectivo común: `lambdaCap=1` cuando `fits(1)`; en otro caso se ejecutan exactamente 60 pasos con `lo=0, hi=1, mid=lo+(hi-lo)*.5`, asignando `lo=mid` si `fits(mid)` y `hi=mid` si no, y se toma `lambdaCap=lo`. La salida usa `lambdaOut=min(Leveling,lambdaCap)`. No hay prioridad regional oculta; los componentes se resuelven en el orden total anterior. [UC-002/003; RF-003..006; RNF-001..004/009]

Con `lambdaOut>0`, la rampa izquierda ocupa `[a_1,a_1+R_0*sr)`, cada interna se centra exactamente en la frontera `b_j=a_(j+1)` y ocupa `[b_j-R_j*sr/2,b_j+R_j*sr/2)`, y la derecha `[b_m-R_m*sr,b_m)`. `fits` demuestra que no se solapan. Los huecos son `HOLD(q_i)`; cada transición es `SMOOTHSTEP(g0,g1)` con `g(u)=g0+(g1-g0)*(3u^2-2u^3)`. Transiciones cuyos extremos coinciden se convierten en HOLD, luego se fusionan holds adyacentes dentro de `1e-12 dB` y se omiten holds 0. Así la curva es C1, `max |dg/dt|=1.5*abs(g1-g0)/R<=2 dB/s`, los protegidos siguen exactamente a 0 y no existe anticipación en ellos. Un componente de `m` regiones emite a lo sumo `2m+1` piezas; para `S<=64`, el total `2S+#componentes<=3S<=192`, dentro del límite público `<=258`. Un postcheck de orden, finitud, continuidad o pendiente que falle reemplaza ese componente por unidad. [UC-002/003; RF-003..006; RNF-001..004]

La monotonía queda cerrada: cada `abs(q_i(lambda))` y cada reserva es no decreciente, luego el conjunto factible positivo es un prefijo y `abs(q_i(lambdaOut))` nunca disminuye al subir Leveling. Al subir Speed, `Tnom` no aumenta, por lo que `lambdaCap` y la intervención alcanzable no disminuyen; para targets fijos, ninguna reserva se alarga. La reconstrucción TP escala solo positivos de un plan ya factible: todas las magnitudes/reservas se mantienen o bajan y `allocateFixed` no necesita una segunda reducción. Orden total, `StrictMath`, 60 pasos y ausencia de iteración sobre hashes hacen que tres ejecuciones en el mismo runtime produzcan los mismos bits de targets y extremos. [UC-003/006; RF-004..006/012; RNF-002..004/009]

El golden exhaustivo fija `sampleRateHz=48000` y usa `P(0)/M1(+3)/M2(-6)/P(0)`. Para cada `Speed` y `rho in {.5,1,2}`, calcula, con evaluación Java izquierda-a-derecha, `d_iFrames=StrictMath.round((rho*Tnom)*sampleRateHz)` y construye `FrameRange(0,d_iFrames)` / `FrameRange(d_iFrames,Math.multiplyExact(2L,d_iFrames))`. Toda fila y todo test usan esos `long`; `D=rho*Tnom` continuo no es un fixture válido. Solo después de resolver capacidad se derivan los endpoints `double` de piezas en frames mediante `R*sampleRateHz`. [UC-002/003; RF-003..006; RNF-001..004/009]

El oracle bit-exacto llama literalmente al mismo cálculo general, en este orden: `q_i=clamp(lambda*r_i,-6,+3)`; luego `R0=max(T,.75*abs(q1))`, `R1=max(T,.75*(abs(q1)+abs(q2)))`, `R2=max(T,.75*abs(q2))`; luego `need1=sampleRateHz*(R0+R1/2.0)` y `need2=sampleRateHz*(R1/2.0+R2)`; finalmente compara cada `need<=d_iFrames` y ejecuta las 60 bisecciones ya fijadas. Está prohibido preplegar esos pasos como `2.25*lambda`, `6.75*lambda` o `4.5*lambda`: aunque sean algebraicamente equivalentes, no conservan necesariamente los bits IEEE-754 del producto. Se cruzan los 27 casos `Speed in {0,.5,1} x rho in {.5,1,2} x Leveling in {0,.5,1}`. [UC-002/003; RF-003..006; RNF-001..004/009]

| Speed / `Tnom` | `rho=.5` debajo: `d_iFrames` | `rho=1` igual: `d_iFrames` | `rho=2` encima: `d_iFrames`; `lambdaCap`; targets para Leveling `0 / .5 / 1` |
|---|---:|---:|---|
| `0 / 4` | `96000`; cap 0, unidad | `192000`; cap 0, unidad | `384000`; cap 1; `(0,0) / (1.5,-3) / (3,-6)` |
| `.5 / 1.7320508075688772` | `41569`; cap 0, unidad | `83138`; cap 0, unidad | `166277`; cap `0x1.c2718a1537834p-2`; `(0,0) / (0x1.51d5278fe9a27p0,-0x1.51d5278fe9a27p1) / igual` |
| `1 / .75` | `18000`; cap 0, unidad | `36000`; cap 0, unidad | `72000`; cap `0x1.8618618618619p-3`; `(0,0) / (0x1.2492492492493p-1,-0x1.2492492492493p0) / igual` |

Todos los extremos siguientes son frames `double`. Para Speed 0/rho 2, Leveling .5 produce exactamente `SMOOTH[0,192000,0,1.5]`, `HOLD[192000,288000,1.5]`, `SMOOTH[288000,480000,1.5,-3]`, `HOLD[480000,576000,-3]`, `SMOOTH[576000,768000,-3,0]`; Leveling 1 produce `SMOOTH[0,192000,0,3]`, `HOLD[192000,222000,3]`, `SMOOTH[222000,546000,3,-6]`, `HOLD[546000,552000,-6]`, `SMOOTH[552000,768000,-6,0]`. Leveling 0 no emite piezas. El caso adicional `P/M1(0)/M2(+3)/P`, Speed 0/rho 2/Leveling 1 emite `SMOOTH[288000,480000,0,3]`, `HOLD[480000,576000,3]`, `SMOOTH[576000,768000,3,0]`.

Para Speed .5/rho 2, Leveling .5 y 1 quedan ambos limitados por el cap nuevo y producen exactamente: `SMOOTH[0x0.0p0,0x1.44c27052cac27p16,0,0x1.51d5278fe9a27p0]`, `HOLD[0x1.44c27052cac27p16,0x1.73276db6db6dbp16,0x1.51d5278fe9a27p0]`, `SMOOTH[0x1.73276db6db6dbp16,0x1.cff1492492492p17,0x1.51d5278fe9a27p0,-0x1.51d5278fe9a27p1]`, `SMOOTH[0x1.cff1492492492p17,0x1.44c28p18,-0x1.51d5278fe9a27p1,0]`; el HOLD M2 tiene longitud cero y se omite. Para Speed 1/rho 2, Leveling .5 y 1 producen análogamente `SMOOTH[0,0x1.194p15,0,0x1.2492492492493p-1]`, `HOLD[0x1.194p15,0x1.416db6db6db6ep15,0x1.2492492492493p-1]`, `SMOOTH[0x1.416db6db6db6ep15,0x1.91c9249249249p16,0x1.2492492492493p-1,-0x1.2492492492493p0]`, `SMOOTH[0x1.91c9249249249p16,0x1.194p17,-0x1.2492492492493p0,0]`.

El test compara bits de cap, targets y extremos en tres runs y comprueba unidad para toda fila sin capacidad, no solape, C1, pendiente, protegido 0, monotonía de Speed sobre una geometría fija separada del grid relativo a `Tnom`, y `allocateFixed` tras reducir boosts. Incluye obligatoriamente `assertNotEquals` por bits entre el cap productivo Speed .5/rho 2 y el literal antiguo `0x1.c2717456e04bdp-2`; ese literal algebraicamente preplegado no es un resultado permitido del fixture. [UC-002/003; RF-003..006; RNF-001..004/009]

### Representación y muestreo

`GainSchedule` es una `sealed interface` interna permitiendo solo `DenseGainSchedule` y `SparseGainSchedule`; el discriminante `GainDomain` es parte validada de cada instancia y no hay conversión implícita entre dominios. `DenseGainSchedule` transfiere ownership del `float[]` creado por `mapFeaturesToGain`: no lo copia, no ofrece accessor y nadie lo muta después de construir el schedule. `SparseGainSchedule` guarda piezas `HOLD[start,end,g]` y `SMOOTHSTEP[start,end,g0,g1]` en frames fuente fraccionales `double`, ordenadas y sin solape; lo no cubierto dentro del dominio es 0 dB. Se fusionan holds adyacentes iguales dentro de `1e-12 dB` y se omiten rampas 0→0. Con S<=64 el solver emite `<=3*S<=192`; todo Sparse conserva además la guarda pública `<=258`. [UC-003/006; RF-005/006/012; RNF-002..005]

El renderer posee desde construcción dos cursores mutables confinados al hilo de audio, `DenseGainCursor(generation,nextPreparedFrame)` y `SparseGainCursor(generation,nextPreparedFrame,pieceIndex)`; no los publica ni los asigna dentro de `process`. `prepare`/seek marcan ambos para reposición. Al comienzo de cada bloque, `process` lee una sola vez `PublishedGain` y `preparedFrameCursor`, valida buffer/canales/rate y ejecuta un único `switch(schedule.domain())`: `renderDenseLegacy(...)` o `renderSparseDb(...)`. No se vuelve a consultar el dominio dentro de los bucles y no hay asignaciones por bloque ni por muestra. Si cambió generación o el frame esperado, Dense solo reinicia sus contadores y Sparse hace una búsqueda binaria inicial; después Sparse avanza `pieceIndex` O(1) amortizado. Ambos renderers actualizan `nextPreparedFrame` al final del bloque. [UC-003/006; RF-005/006/012; RNF-002/003/005/009]

La ruta Dense de M-003/M-004 reproduce literalmente el dominio y el orden de evaluación Java 17 del código base. Calcula una vez por bloque `double rateRatio=dense.envRateHz()/preparedRateHz`; para cada frame calcula `double pos=(startPreparedFrame+f)*rateRatio`, donde la suma ocurre como `long` antes de la multiplicación. Su lookup es exactamente, sin clamp adicional, `double`, `StrictMath`, dB, `Math.fma` ni vectorización algebraica:

```java
static float sampleLinearLegacy(float[] env, double pos) {
    if (pos <= 0.0) return env[0];
    int i = (int) pos;
    if (i >= env.length - 1) return env[env.length - 1];
    float frac = (float) (pos - i);
    return env[i] + (env[i + 1] - env[i]) * frac;
}
```

El `float g` resultante se aplica a cada canal con la sentencia histórica `buffer[base+c] *= g`; resta, producto, suma y multiplicación de audio permanecen operaciones `float` con sus redondeos intermedios. Por tanto Dense clampa `pos=-0.0`, cualquier negativo y cero a `env[0]`, interpola hasta el último frame y clampa desde `i>=env.length-1` a `env[last]`. Este clamp, incluido después del final, es deliberadamente distinto del Sparse. El cálculo dB de metering ocurre después y jamás retroalimenta el audio. [UC-003/006; RF-005/006/012; RNF-002/003/007/009]

La ruta Sparse habilitada desde M-005 mantiene el contrato final: `double sourcePos=((double)(startPreparedFrame+f)*sourceRateHz)/preparedRateHz`, sin redondeo previo. Si `sourcePos<0`, `sourcePos>=sourceFrames` o no es finito, usa unidad exacta y deja la muestra sin operación aritmética. Dentro del dominio, el cursor obtiene `double gDb`; 0 dB también deja la muestra intacta, y para `gDb!=0` ejecuta exactamente `double linear=StrictMath.exp(gDb*StrictMath.log(10.0)/20.0)` y `buffer[base+c]=(float)(buffer[base+c]*linear)`. Seek aleatorio usa búsqueda O(log S); render secuencial, O(1) amortizado. Nunca se convierte una ganancia Dense lineal a dB ni se invoca este renderer para un `LEGACY_LINEAR`. [UC-003/006; RF-005/006/012; RNF-002..005/009]

El medidor también tiene dos contratos cerrados y se calcula una vez por frame, no por canal. Dense conserva exactamente `double dB=20.0*Math.log10(Math.max(g,1e-6f)); if (Math.abs(dB)>Math.abs(extreme)) extreme=dB;`, con `extreme=0.0`, comparación estricta `>` y primer valor en empate; al final publica ese `double`. Sparse compara del mismo modo `Math.abs(gDb)>Math.abs(extreme)` y publica el `gDb` firmado del schedule, con 0 para unidad fuera de dominio. Bypass, formato inválido o bloque no procesado publica 0. Dense no reutiliza el meter dB como gain de audio; Sparse no recomputa su meter desde el `float linear`. [UC-003/006; RF-005/006/012; RNF-002/003/007/009]

Golden Dense normativo: `env={0x1.529a5p-1f,0x1.0p-1f,0x1.cp-1f,0x1.2p0f}`, buffer mono de un frame `1.0f`, `envRateHz=R`, y `preparedRateHz=R,2R,4R`. Cada celda de posición preparada se prueba mediante seek antes del frame; `—` significa que ese factor no representa el cuarto de frame exacto. El resultado debe coincidir por `Float.floatToRawIntBits`, no por tolerancia:

| `sourcePos` | frame preparado 1x / 2x / 4x | gain/output esperado | raw bits |
|---:|---:|---:|---:|
| `-2` | `-2 / -4 / -8` | `0x1.529a5p-1f` | `0x3f294d28` |
| `-1` | `-1 / -2 / -4` | `0x1.529a5p-1f` | `0x3f294d28` |
| `0` | `0 / 0 / 0` | `0x1.529a5p-1f` | `0x3f294d28` |
| `.25` | `— / — / 1` | `0x1.3df3bcp-1f` | `0x3f1ef9de` |
| `.5` | `— / 1 / 2` | `0x1.294d28p-1f` | `0x3f14a694` |
| `.75` | `— / — / 3` | `0x1.14a694p-1f` | `0x3f0a534a` |
| `1.25` | `— / — / 5` | `0x1.3p-1f` | `0x3f180000` |
| `1.5` | `— / 3 / 6` | `0x1.6p-1f` | `0x3f300000` |
| `1.75` | `— / — / 7` | `0x1.9p-1f` | `0x3f480000` |
| `2.5` | `— / 5 / 10` | `0x1.0p0f` | `0x3f800000` |
| `3` (último) | `3 / 6 / 12` | `0x1.2p0f` | `0x3f900000` |
| `4` (después) | `4 / 8 / 16` | `0x1.2p0f` | `0x3f900000` |

Contraejemplo obligatorio de la frontera de dominios: con `x=0x1.7p-1f` (`0x3f380000`) y Dense constante `g=0x1.529a5p-1f` (`0x3f294d28`), el único output permitido es el producto float legado `0x1.e6bdd4p-2f` (`0x3ef35eea`). El round-trip lineal→dB→`StrictMath.exp` produce `0x1.e6bdd2p-2f` (`0x3ef35ee9`) y debe afirmarse explícitamente distinto. El meter Dense correspondiente es `-0x1.cbb92e1bffe12p1` (`0xc00cbb92e1bffe12`), calculado por la fórmula legacy anterior. [UC-003/006; RF-005/006/012; RNF-002..005/007/009]

### Migración compatible de `AnalysisDynamicsProcessor`

La migración mantiene a `LevelerProcessor` como subtipo para no romper `MainController.DynCard`, `ProcessingPipeline` ni las pruebas/subclases existentes; `usesAnalysis()` permanece `true` y la latencia declarada de la etapa permanece cero. En la base se introduce un template `mapFeaturesToSchedule(...)`: su implementación por defecto llama al actual `mapFeaturesToGain(...)`, captura después la referencia fresca de `gainEnv` y transfiere esa misma referencia —sin copia ni conversión— a `DenseGainSchedule`. En M-003 ese default rige sin excepción para Peak/Beat/Punch **y Leveler**, cuya salida debe permanecer bit a bit. En M-004 el motor y el plan nuevos del Leveler se calculan y validan exclusivamente en sombra: el candidato no puede asignarse a `published` ni entrar por `adoptEnvelope`, y el audio sigue usando el `DenseGainSchedule` legado. Solo en M-005, después de completar y validar rampas, true peak y fixtures, Leveler sobrescribe el template con `SparseGainSchedule` y realiza el único switch atómico al plan nuevo; Peak/Beat/Punch permanecen en el default denso. El campo legado queda `protected` y deprecado durante el rollout; no se elimina en M-003 ni como parte de este cambio. [UC-003/006; RF-005/006/012; RNF-007/009]

El único estado de audio publicado pasa a `private volatile PublishedGain published`, record inmutable con schedule, formato fuente, fingerprint, generación, estado y diagnóstico. `analyze` de la ruta que tenga autoridad de audio construye y valida todo localmente y hace una única asignación al final. En M-003/M-004 esa autoridad sigue en el mapper legado denso; el cálculo nuevo de M-004 conserva su resultado separado y nunca escribe la referencia. Desde el switch de M-005, la ruta nueva adquiere la autoridad y error/cancelación publica unidad completa, nunca campos parcialmente nuevos. `process` lee esa referencia una sola vez al inicio de cada bloque y mantiene el snapshot hasta terminarlo. `adoptEnvelope(snapshot)` conserva el nombre API pero copia exactamente una referencia validada de la ruta activa; la guarda de generación de `MainController.syncLiveAnalysis` sigue descartando análisis obsoletos. [UC-003/006; RF-005/006/012; RNF-002/003/007/009]

La integración M-004 es cerrada y síncrona. `LevelerProcessor.analyze` ejecuta primero `super.analyze(samples,channels)` para completar/publicar el Dense legado y, en el **mismo thread llamante**, invoca directamente `LevelerAnalysisEngine.analyzeShadow`; solo después de que el método retorne con un snapshot completo hace una asignación `this.shadowAnalysis=next`. `null` por input inválido, cancelación o error conserva el snapshot anterior y siempre conserva Dense. El engine no crea ni entrega trabajo, no registra callbacks y no toca `published`/`adoptEnvelope`. Ningún classfile productivo M-004 puede referenciar `Thread`, `ThreadLocal`, `InheritableThreadLocal`, `Executor`, `ExecutorService`, `ForkJoinPool`, `CompletableFuture`, `Future`, `FutureTask`, `Timer`, `TimerTask`, `java.util.concurrent`, `java.util.stream`, parallel stream, shutdown hook, `Cleaner`, callback/listener global ni API equivalente. El worker one-shot que usa el benchmark pertenece exclusivamente al proceso hijo de tests: llama a esta API síncrona, retorna, termina y se hace `join` antes de `SHADOW_READY`. [UC-001/003/006; RF-005/006/012; RNF-002..005/008..010]

Lifecycle normativo, respetando las firmas reales de `AudioProcessor`:

1. construcción: schedule unidad y cursor cero;
2. `prepare(sampleRatePrepared,totalSamplesInterleaved)`: valida rate/total, fija reloj de render y reinicia cursor/medidor; no conoce canales y no borra análisis;
3. `analyze(samples,channels)`: usa el sample rate del último `prepare`; la ruta activa valida, construye su schedule y publica al final sin cambiar posición preparada. M-004 ejecuta después el análisis/plan nuevo síncronamente, sin remap/cache previa, y como máximo sustituye `shadowAnalysis` por un snapshot completo; no lo publica ni adopta. Desde M-005 esa ruta nueva pasa a ser la activa;
4. el segundo `prepare(...)` que `analyzeAndRender` ya hace al terminar solo rebobina el cursor y conserva la publicación;
5. `setPlaybackPosition(framePrepared)`: acepta negativos de compensación, actualiza cursor y fuerza búsqueda de pieza en el siguiente bloque;
6. `process(buffer,channels)`: valida los canales contra `PublishedGain`; bypass si disabled/formato inválido; en otro caso captura una publicación local, selecciona exactamente una vez por bloque el renderer Dense lineal o Sparse dB, aplica la misma ganancia por frame/canales sin asignaciones y avanza exactamente `buffer.length/channels` frames;
7. `clearAnalysis()`: método package-private de carga/cierre publica unidad; no se inventa un `reset()` en la interfaz.

En la ruta oversampled existente, `prepare(sr*factor,(long)input.length*factor)` y `setPlaybackPosition(-oversamplerUpsampleLatencyHiFrames)` siguen siendo válidos. Dense usa exactamente `envRate/preparedRate` y sus clamps/interpolación legacy: los instantes representables a 1x/2x/4x coinciden por raw bits y los cuartos de frame siguen la tabla normativa anterior, incluidos los negativos clampados a `env[0]`. Sparse convierte a frame fuente con su fórmula propia y devuelve unidad para posiciones negativas o posteriores al final. Ningún schedule se reanaliza a rate alto. [UC-003/006; RF-005/006; RNF-002/003/007/009]

### Cache e invalidación de remapeo

M-004 retiene únicamente `ShadowAnalysisCache`, con los doce fields y aliases fijados en “Tipos, unidades e invariantes”; no contiene perfil objeto, `PeakSafetyProfile`, controles, schedule ni publicación. Su identidad exacta es `(sampleRateHz,channels,frames,SHA-256 de Float.floatToRawIntBits en orden,algorithmId,profileId)`, calculada sobre el PCM que realmente entra al Leveler después de procesadores anteriores. No existe remapeo ni adopción M-004. [UC-001/003/006; RF-001/004..006; RNF-005/009]

Desde M-005 se introduce, como tipo nuevo y fuera del universo M-004, `LevelerAnalysisCache`: será inmutable y podrá contener los datos independientes de controles más `PeakSafetyProfile`; no contiene Leveling, Speed ni gain final. Coincidencia total permitirá remapear Leveling/Speed en O(S) más la prueba de picos, sin FFT/segmentación; mismatch, cambio de orden/upstream, edit, sample rate/canales, versión o PCM fuerza análisis completo. No hay herencia, cast, field compartido ni alias entre ambos tipos de cache. [UC-001/003/006; RF-001/004..006; RNF-005/009]

Desde M-005, para integrarse con el snapshot real, `LevelerProcessor.forkForAnalysis(leveling,speed)` comparte solo la referencia al `LevelerAnalysisCache` futuro; `MainController.buildSnapshot` usa ese factory en lugar de un Leveler vacío. Aun así vuelve a validar la key contra su entrada acumulativa: compartir no equivale a confiar. La adopción exitosa transfiere `PublishedGain` y cache; una generación obsoleta no transfiere ninguno. M-004 no ejecuta este factory/remap. El hash O(F) es deliberado para corrección y puede abortarse cada 4096 frames; no se usa identidad de array como prueba. [UC-003/006; RF-005/006/012; RNF-004/005/007/009]

## Seguridad de true peak

`TruePeakSafety` solo puede medir mediante `FiniteTruePeakStream`, façade común para DSPark y el fallback. La API local verificada de DSPark 0.1.0 expone `TruePeak.process(double)`, `reset()` y `GROUP_DELAY_FRAMES=6`; su `measureMax(float[],int)` no hace flush y queda prohibido en `TruePeakSafety`, `PeakSafetyProfile` y cualquier `SafetyProof`, incluso para el input (solo un test negativo puede invocarlo). La adopción del kernel sigue condicionada a los casos 15–23 de EBU Tech 3341 v4 a 44.1/48 kHz con tolerancia +.2/-.4 dBTP; `max(abs(sample))` tampoco es sustituto. [UC-003/006; RF-006/012; RNF-002..004/009]

Cada medición crea un `FiniteTruePeakStream(channels,kernelFactory)` nuevo que posee exactamente un kernel nuevo/reseteado por canal. Su estado inicial `OPEN` representa la extensión izquierda por ceros mediante el historial ya puesto a cero; no procesa frames ficticios de priming. `accept(interleaved,offsetFrames,countFrames)` exige canales constantes, rango válido y frames completos, y recorre en orden frame→canal; conserva cada historial y el máximo entre chunks, incluido un chunk vacío, sin reset ni flush intermedio. `finish()` cambia una sola vez a `FINISHED`, llama exactamente seis veces a `process(0.0)` en cada canal, incorpora todos esos retornos al máximo y cachea el resultado; una llamada posterior devuelve el valor cacheado sin otro cero y `accept` posterior es error. Los seis frames son cola del filtro: no avanzan posición de fuente ni consultan gain. Excepción, no finito o violación del protocolo invalida la prueba y fuerza unidad. Ningún kernel se reutiliza entre input, candidatos, canales o generaciones. [UC-003/006; RF-005/006/012; RNF-002..004]

El fallback cumple ese mismo estado observable y replica el kernel local auditable: historial circular 16, cuatro fases de 12 taps, máximo entre `abs(sample)` y las cuatro salidas, escritura del sample seguida de lectura hacia atrás. Su matriz normativa es:

```text
[ .001708984375,  .010986328125, -.0196533203125,  .033203125,  -.0594482421875,  .1373291015625, .97216796875,   -.102294921875,  .047607421875, -.026611328125,  .014892578125, -.00830078125 ]
[-.0291748046875, .029296875,    -.0517578125,     .089111328125, -.16650390625,    .465087890625,  .77978515625, -.2003173828125, .1015625,      -.0582275390625, .0330810546875, -.0189208984375]
[-.0189208984375, .0330810546875,-.0582275390625,  .1015625,      -.2003173828125,  .77978515625,   .465087890625,-.16650390625,   .089111328125, -.0517578125,    .029296875,     -.0291748046875]
[-.00830078125,   .014892578125, -.026611328125,   .047607421875, -.102294921875,   .97216796875,   .1373291015625,-.0594482421875, .033203125,    -.0196533203125, .010986328125,  .001708984375]
```

El input y **todo** candidato, incluida unidad y boost cero, se terminan mediante `finish`; así la cola participa tanto en `inputTp` como en cada `SafetyProof`. El stream candidato evalúa el schedule en frames reales, preserva el sample protegido sin operación aritmética y en un frame modificable genera exactamente el valor que renderizará Java: `float y=(float)(x*StrictMath.exp(gDb*StrictMath.log(10.0)/20.0))`, después lo ensancha a `double` para `process`. El máximo enlazado conserva amplitud lineal finita `inputTp>=0`; `ceilingLinear=min(1,inputTp*StrictMath.exp(.1*StrictMath.log(10.0)/20.0))`, equivalente a `min(0 dBTP,inputTpDb+.1 dB)`. Solo presentación convierte cero a `-Infinity`. `PROVEN` exige siempre una pasada finita completa, chunks `<=65,536`, `finish` satisfactorio, preservación protegida y `candidateTp<=ceilingLinear`; ninguna cota aproximada puede emitirlo. [UC-002/003/006; RF-003/005/006/012; RNF-001..004]

`PeakSafetyProfile` guarda por tiles de 100 ms máximo sample, estado/cotas FIR y metadatos del kernel, incluyendo una tile EOF que incorpora los seis ceros y su máximo de cola; ocupa O(F/(sr*.1)), no PCM. Solo ordena candidatos o permite abandonar una cota ya incapaz de mejorar: nunca sustituye la pasada finita que autoriza `PROVEN`. Si el kernel concreto no demuestra equivalencia se deshabilita la optimización, no la medición. [UC-003; RF-006; RNF-003..005/009]

Golden obligatorio, con tolerancia absoluta `1e-12` en amplitud: para el clip mono final `x=[-0.7042242288589478f,-0.41123709082603455f]`, la llamada directa defectuosa sin flush da `0.7042242288589478`, mientras `FiniteTruePeakStream.finish()` da `inputTp=0.7410990583266539` (`-2.6024747713344243 dBTP`) y `ceilingLinear=0.7496805809746013`. El candidato constante +1 dB, con cuantización `float` de render, da `0.8315268495898636` y no es seguro; +.1 dB da `0.7496805612245225` y sí queda bajo el ceiling. DSPark y fallback deben repetir esos números para un chunk y para cada corte `k=0,1,2` de `accept(x[0:k]); accept(x[k:2]); finish()`. En estéreo se prueban `L=x/R=0` y `L=0/R=x`, separando en cada frontera de frame: el máximo debe ser idéntico, terminar un canal no contamina al otro y una segunda llamada a `finish` no lo cambia. Una propiedad adicional particiona clips sembrados en toda frontera legal y exige igualdad de bits con un único chunk. [UC-003/006; RF-006/012; RNF-002..004/009]

Si un candidato falla, escala conjuntamente solo los targets positivos por `lambda in [0,1]`, reconstruye mediante `RampAllocator.allocateFixed` —factible porque ninguna magnitud aumenta— y hace bisección determinista hasta 20 iteraciones o intervalo <=.01 dB; `lambda=0` se prueba primero como base segura. Cada candidato considerado seguro se vuelve a medir de extremo a extremo con un stream nuevo y su único flush; no se presupone monotonicidad perfecta y se conserva el mayor lambda efectivamente `PROVEN`. Si el plan sin boosts falla, prueba los cuts; si estos tampoco son seguros, publica unidad. Si el propio input supera 0 dBTP en una región que debe permanecer bit-exact, el límite es matemáticamente imposible: estado `INFEASIBLE_INPUT_BASELINE`, cero boosts y unidad/cuts solo si verificables, sin afirmar falsamente cumplimiento. NaN/Infinity implica unidad global. No se introduce limiter, clipper ni saturación. [UC-002/003/006; RF-003/005/006/012; RNF-001..004]

## Flujos principales

### Flujo offline Leveler

```text
PCM acumulativo post-upstream
  -> validar/formatear/fingerprint
  -> LoudnessAnalyzer + StructuralFeatureExtractor
  -> BoundaryDetector (S<=64) + SegmentDescriptorBuilder
  -> ProtectionClassifier (unión irrevocable)
  -> BodyContextGate -> gain-scaled ambiguity gate
  -> SegmentComparator -> matriz fija
  -> complete-link + separación externa | pareja mutua estricta
  -> ReferencePlanner -> raw targets/deadband
  -> GainPlanner(Leveling,Speed) -> SparseGainSchedule
  -> TruePeakSafety -> PublishedGain atómico o unidad
  -> prepare/seek/process por bloques, una ganancia enlazada por frame
```

Cada flecha valida finitud/invariantes. Una razón dura no tiene arista saliente hacia comparación; toda rama de fallo de la ruta nueva converge en unidad local/global y diagnóstico estable. Así no existe camino high-match que salte una protección. En M-004 el flujo llega a análisis y `ReferencePlan` como cálculo de sombra y sus fallos solo producen diagnóstico; no sustituyen el audio legado. M-005 completa `GainPlanner`/`TruePeakSafety` y es el primer milestone autorizado para asignar el resultado a `PublishedGain`. `ProcessingPipeline.analyzeAndRender` conserva el orden acumulativo actual: cada procesador analiza y renderiza la salida de los anteriores; Leveler no analiza el master original si no es lo que recibe. [UC-001..003/006; RF-001..006/012; RNF-001..005/009]

### Flujo live/snapshot

Desde M-005, un cambio Leveling/Speed captura `generation`, crea snapshot con cache compartida, ejecuta fuera del hilo JavaFX, valida el PCM real, remapea y prueba peaks. En `Platform.runLater`, solo si generación/audio siguen vigentes llama `liveLeveler.adoptEnvelope(snapshot)`; el hilo de audio ve el plan previo o el nuevo completo en el siguiente bloque. Esa ejecución futura no pertenece al universo M-004. En M-004 no se agenda análisis live: el único cálculo nuevo ocurre síncronamente durante `LevelerProcessor.analyze`, es exclusivamente sombra y `adoptEnvelope` está prohibido, por lo que el audio conserva el schedule denso legado. Carga/edit/reordenamiento invalida key y cancela cooperativamente desde M-005. `enabled=false` puede hacer bypass inmediato sin borrar el último plan, de modo que reactivar no publica media actualización. [UC-003/006; RF-005/006/012; RNF-002/005/007/009]

## Viewport de waveform

### Modelo puro y transformación única

`WaveformViewport` es un record/valor sin JavaFX con segundos `D,S,V`. Para `D>0`, `Vmin=min(D,max(.25,D/1024))`, `Vmin<=V<=D` y `0<=S<=D-V`; vacío es `(0,0,0)`. Con ancho útil `W>0`: `timeAtX(x,W)=S+clamp(x,0,W)/W*V` y `xAtTime(t,W)=(t-S)/V*W` sin clamp. Un `D<=0`, `W<=0` o número no finito retorna `OptionalDouble.empty`/`GestureResult.ignored` sin mutación. El renderer recorta, pero mantiene offscreen distinguible del borde. [UC-004/005; RF-007..011; RNF-006/009]

`MainController` deja de dividir por duración global. Waveform, ticks, playhead, hover, click/drag scrub, Shift-drag selección, hit-test y dibujo de ambos fades llaman al mismo adapter. El modelo guarda `fadeInEndSec` y `fadeOutStartSec`; el procesador actual de fade-out expresa duración, por lo que el límite UI convierte exclusivamente `fadeOutDurationSec=D-fadeOutStartSec` al leer/escribir. [UC-004/005; RF-008..010; RNF-006/009]

### Zoom, prioridad, navegación y reset

Para Shortcut+Scroll válido y no inercial: `p=clamp(x,0,W)/W`, `anchor=S+pV`, `z=1.25^sign(deltaY)`, `V'=clamp(V/z,Vmin,D)`, `S'=clamp(anchor-pV',0,D-V')`. Delta positivo acerca. Incluso si el clamp deja el mismo estado, el gesto válido se considera manejado y se consume para no activar fades. Se usa `ScrollEvent.isShortcutDown()`, nunca detección de OS. [UC-004; RF-007/008/010/011; RNF-006/009]

Orden único del handler: (1) `isInertia()` retorna antes de toda mutación y no consume; (2) Shortcut válido hace zoom/consume y jamás fade; (3) sin Shortcut sobre handle/área admitida conserva el gesto de curva actual y consume solo si cambia; (4) fuera no cambia ni consume. Hit-testing también usa `xAtTime`. [UC-004/005; RF-007/010/011; RNF-006/009]

La navegación mínima que no colisiona con scrub/selección/fades es `Alt/Option + primary-drag` sobre fondo: al press captura `(S0,x0)` y al drag usa `S'=clamp(S0-(x-x0)*V/W,0,D-V)`; sin movimiento válido no consume. No se añade control persistente. `Shortcut+0` ejecuta `fullView()`; cargar/reemplazar pista hace `S=0,V=D`, playhead 0, selección vacía y fades default. Resize conserva D/S/V y cambia solo W. Estas dos interacciones se someten a las mismas pruebas JavaFX y accesibilidad que zoom. [UC-004/005; RF-008/009; RNF-006/009]

### Rebase después de edits

`TimelineEditRebaser` calcula antes de mutar. Para delete `[a,b)`, `delta=b-a`: `map(t)=t` si `t<a`, `a` si `a<=t<b`, `t-delta` si `t>=b`, `D'=D-delta`. Para crop/trim que retiene `[a,b)`: `map(t)=clamp(t,a,b)-a`, `D'=b-a`. Tras éxito del PCM, rebasa playhead, fades y selección; extremos de selección iguales dentro de `1e-9 s` la limpian, y crop siempre consume la selección. [UC-005; RF-008..010; RNF-006/009]

Después mapea el centro `c=S+V/2`, calcula `c'=map(c)`, `Vmin'`, `V'=clamp(V,Vmin',D')`, `S'=clamp(c'-V'/2,0,D'-V')`, y al final clampa tiempos manteniendo `0<=fadeInEnd<=fadeOutStart<=D'`. Publica un único `TimelineViewState` inmutable solo si todas las invariantes pasan. Con `D'=0` produce estado vacío. Si el edit PCM falla no cambia ningún estado UI. Delete anterior mueve S, posterior lo conserva e interior mantiene V alrededor de la costura; nueva pista nunca usa rebase. [UC-005; RF-008..010; RNF-006/009]

Vectores normativos (`P` playhead, `Sel`, `FI/FO` son segundos):

| Caso | Entrada/operación | Salida exacta `(D,S,V,P,Sel,FI,FO)` |
|---|---|---|
| delete before | base `(120,40,20,50,[42,58],5,110)`, delete `[10,20)` | `(110,30,20,40,[32,48],5,100)` |
| delete inside | base, delete `[45,55)` | `(110,35,20,45,[42,48],5,100)` |
| delete after | base, delete `[80,90)` | `(110,40,20,50,[42,58],5,100)` |
| delete selection | base, delete `[40,60)` | `(100,30,20,40,empty,5,90)` |
| crop partial | Sel `[45,75]`, crop `[45,75)` | `(30,0,20,5,empty,0,30)` |
| trim start/end | base, retain `[30,120)` / `[0,50)` | `(90,10,20,20,[12,28],0,80)` / `(50,30,20,50,[42,50],5,50)` |
| short track | `(1,.2,.5,.4,[.3,.7],.1,.9)`, retain `[0,.1)` | `(.1,0,.1,.1,empty,.1,.1)` |

Con `(D,S,V,W)=(120,40,20,1000)`, `xAtTime(30)=-500` y `xAtTime(70)=1500`; no se clampan a borde. [UC-004/005; RF-008..010; RNF-006/009]

### Índice de peaks visibles

`WaveformPeakIndex` inmutable guarda min/max por canal con bloque base `Q=256` frames y pirámide 2:1; un nodo `(level,k)` cubre exactamente los bloques base `[k*2^level,min((k+1)*2^level,B))`, `B=ceil(F/Q)`, y el último conserva la cola. Un nodo solo puede responder si **todo** su intervalo de frames está contenido en el rango solicitado. La construcción lee una vez el PCM, cuesta O(F*channels), comprueba cancelación cada bloque base y publica arrays finales; no existe nivel por frame. Hay `<2B` nodos y payload primitivo `<16*B*channels` bytes. [UC-004/005; RF-008/009; RNF-005/006/009]

La unidad de ownership es `WaveformSourceSnapshot(generation,pcmRef,sampleRateHz,channels,frames)`: `pcmRef.length=frames*channels`, la referencia no se copia para indexar y queda read-only mientras el snapshot esté publicado. Carga, reset, undo, crop, delete o reemplazo de procesamiento crean/reutilizan un array ya terminado, incrementan generación y publican de una vez `WaveformRenderState(snapshot,null)`; cualquier ruta que hoy mutase ese array debe hacer copy-on-write antes, nunca durante una consulta. El builder captura el snapshot. Solo publica `WaveformRenderState(snapshot,index)` si identidad de `pcmRef`, formato y generación aún coinciden. El renderer lee una sola referencia `volatile` al iniciar el redraw; final fields + esa publicación dan visibilidad JMM y evitan mezclar PCM nuevo con índice viejo. [UC-004/005; RF-008/009; RNF-002/005/006/009]

Para ancho entero `W>0`, se calcula una sola tabla compartida de bordes, en ese orden de operaciones: `edge[x]=clamp((long)StrictMath.ceil((S+(x*V)/W)*sampleRateHz),0,F)` para `x=0..W`. La columna `x` representa el intervalo de frames semiabierto exacto `[a,b)=[edge[x],edge[x+1])`; un frame en una frontera pertenece solo a la columna derecha. Vacío devuelve min=max=0. Este borde único evita gaps o duplicados por reevaluación flotante. [UC-004/005; RF-008/009; RNF-003/006/009]

La consulta híbrida exacta de `[a,b)` es normativa:

1. si `b-a<Q`, escanea únicamente esos frames contra `snapshot.pcmRef`, todos los canales, sin buffer/copia;
2. en otro caso fija `p=min(b,ceilDiv(a,Q)*Q)` y `s=max(p,floorDiv(b,Q)*Q)`, escanea los fragmentos `[a,p)` y `[s,b)` directamente, y nunca más de `2*(Q-1)` frames de borde;
3. para los bloques completos `[p/Q,s/Q)`, toma en orden de frame la descomposición canónica: en cada inicio el nodo de mayor nivel alineado que no rebase el final. Son como máximo `2*ceil(log2(max(2,B)))` nodos, todos totalmente contenidos; combina sus min/max por canal;
4. combina nodos y bordes con min/max IEEE-754 tras haber rechazado PCM no finito. Ningún bloque parcial se redondea hacia fuera y un peak jamás se extiende a una columna vecina.

Por tanto un redraw cuesta `O(W*channels*(log(max(2,B))+2Q))`, independiente de F salvo ese logaritmo; más precisamente visita por píxel `<=2*ceil(log2(max(2,B)))` nodos y `<=510` frames directos. Para F=0 no se construye índice y todos los bordes/rangos son cero. [UC-004/005; RF-008/009; RNF-003/005/006/009]

Mientras el índice de la generación vigente se construye, el renderer usa exactamente el mismo `edge`: si el rango visible contiene `<=2*Q*W` frames, hace escaneo directo exacto O(visibleFrames*channels); si lo supera, dibuja una línea central neutra con estado `INDEXING` —no un resumen impreciso— y mantiene playhead, selección, scrub y fades mediante el viewport. Cancelación/fallo conserva esa ruta y permite reintento; nunca usa un índice de otra generación. Así el hilo JavaFX tiene una cota aun antes de publicar el índice. [UC-004/005; RF-008/009; RNF-002/005/006/009]

Goldens de localización, cada uno con un único impulso `+1f` y su réplica `-1f`, exigen que solo la columna indicada tenga max/min no cero; las demás son exactamente cero:

| `D=120 s`, `Vmin=.25 s`, `W=1000` | viewport | frame 0 | frame 255 | frame 256 | frame `F-1` |
|---|---|---:|---:|---:|---:|
| 48 kHz (`12` frames/píxel) | `S=0` salvo último; último `S=119.75` | 0 | 21 | 21 | 999 |
| 96 kHz (`24` frames/píxel) | `S=0` salvo último; último `S=119.75` | 0 | 10 | 10 | 999 |

No se cambia `Vmin`. Para pista corta `D=.1 s`, `Vmin=D`: F es 4,800/9,600 a 48/96 kHz, el último frame cae solo en columna 999, construir lee exactamente F*channels samples, el redraw full-view escanea intervalos exactos y el payload del índice es `<608/<1,216` bytes en estéreo. Para 60 min/48 kHz estéreo, F=172,800,000, B=675,000: payload `<21,600,000` bytes (20.6 MiB), la construcción hace 345,600,000 lecturas, y W=1000 limita cada redraw a 40,000 nodos, 510,000 frames de borde y 1,020,000 lecturas PCM de borde. UI08 añade cola no múltiplo de 256 y exige el mismo resultado antes (fallback directo) y después de publicar índice. [UC-004/005; RF-008/009; RNF-005/006/009]

## Tecnología y dependencias

- Java 17: clases `final`, `Math`/`StrictMath`, arrays primitivos, `BitSet`, `MessageDigest SHA-256` y publicación JMM por `volatile`; no se requiere preview ni dependencia nueva. M-004 prohíbe records/lambdas y toda API async en su universo productivo; el executor UI existente solo vuelve a intervenir fuera de ese universo en M-005. [RNF-005/008..010]
- JavaFX 21.0.11: el adapter de eventos vive en UI; modelos DSP/viewport no importan `javafx.*`, permitiendo JUnit headless. [UC-004/005; RF-007..011; RNF-006/009]
- DSPark 0.1.0: FFT/K-weighting/TruePeak se encapsulan detrás de adapters propios. Solo se usan primitivas cuya salida pase fixtures normativos; si TruePeak no pasa, se implementa el FIR BS.1770 auditable dentro de QuickMaster, no se modifica el vendor. [UC-001/003; RF-001/006; RNF-003/009]
- Maven y `jpackage` existentes: release Java 17, runtime compatible >=17; CI JDK 21 y runtime local usado se registran en evidencia. Sin red, telemetría, modelos remotos ni descargas en ejecución. [UC-006; RF-012; RNF-008/010]

## Errores, cancelación y observabilidad

| Condición | Alcance/fallback | Diagnóstico estable | Trazabilidad |
|---|---|---|---|
| PCM/formato no finito, longitud no divisible, overflow | análisis global → unidad, salida intacta | `INVALID_INPUT` | UC-003; RF-005/006; RNF-003/004 |
| `totalFrames<=0`, cardinalidad estructural distinta de `ceilDiv(totalFrames,hopFrames)` o centro no canónico | layout vacío, sombra anterior/Dense intactos | `INSUFFICIENT_FEATURES` | UC-001/002/003; RF-001/003; RNF-003/004/009 |
| Conformidad `NOT_RUN`/`UNAVAILABLE`/`PARTIAL`/`FAILED`, binding/hash distinto | candidato nuevo unidad, no switch/adopción; Dense conserva autoridad | `STANDARD_VALIDATION_FAILED` + estado/set | UC-001/003/006; RF-001/006/012; RNF-003/004/008/009 |
| Descriptor/bin/contexto local degenerado | solo región/par → 0 dB | razón concreta, no excepción | UC-001/002; RF-002/003/005; RNF-001/004 |
| S>64, tamaño estimado >256 MiB o dimensión imposible | análisis global → unidad | `TOO_MANY_SEGMENTS`/`MEMORY_BUDGET` | UC-001/003; RF-001/005/006; RNF-004/005 |
| Root/schema desconocida, cap excedido, alias a PCM/Dense o bytes retenidos no contabilizados | gate M-004 falla; se descarta sombra y Dense conserva autoridad | `MEMORY_OWNERSHIP_UNACCOUNTED` + paths | UC-001/003/006; RF-005/006/012; RNF-003..005/008/009 |
| Cancelación/nueva generación | abandonar temporales, conservar publicación anterior | `CANCELLED`, no se adopta | UC-003/006; RF-005/012; RNF-002/005 |
| Cache key/version no coincide | recomputar, nunca reutilizar parcialmente | `CACHE_MISS` informativo | UC-003; RF-005/006; RNF-005/009 |
| TP no seguro o no finito | reducir boosts; después cuts/unidad según política | `BOOST_REDUCED`/`PEAK_UNSAFE` | UC-003/006; RF-006/012; RNF-003/004 |
| Rampas activas sin capacidad nominal | solo ese componente → unidad, otros continúan | `RAMP_CAPACITY_ZERO` | UC-002/003; RF-003..006; RNF-001..004 |
| Baseline TP protegido ya >0 dBTP | resultado conservador verificable, declarar imposibilidad | `INFEASIBLE_INPUT_BASELINE` | UC-002/003; RF-003/006; RNF-001/003 |
| Viewport/evento inválido | estado anterior, evento no consumido | `GestureResult.ignored` | UC-004/005; RF-007..011; RNF-006 |
| Edit PCM falla | no publicar rebase | error existente del edit | UC-005; RF-008..010; RNF-006/009 |
| Índice de peaks falla/cancelado | escaneo directo exacto si `visibleFrames<=512W`; si no, centro neutro `INDEXING`; UI/overlays siguen usables | generación/motivo | UC-004/005; RF-008/009; RNF-005/006 |

M-004 acumula el prefijo monotónico exacto `VALIDATE,LOUDNESS,FEATURES,SEGMENTS,PROTECT,COMPARE,PLAN,VALIDATION,DONE` y comprueba cancelación cada <=4096 frames y entre pares; `PEAKS` se añade solo en M-005. Error/cancelación descarta el resultado local y conserva publicación/snapshot anteriores; `DONE` de sombra solo habilita evidencia/diagnóstico y nunca asigna `published`. El caller puede serializar después generación, formato, perfil, counts y razones agregadas, pero ningún classfile M-004 retiene logger/listener/callback; nunca se incluyen PCM, hashes completos ni contenido musical. [UC-003/006; RF-005/012; RNF-003..005/010]

## Rendimiento y presupuestos

Sean F frames, N hops de .5 s, K tamaño FFT, S<=64, L=32 y d=22. Extracción cuesta `O(F log K)` y `O(K+N*d)`; Q compartida de radios constantes cuesta dos evaluaciones O(N log N), temporales O(N), nunca N²; comparación <=2016 pares/18144 kernels/16128 rebinning secuenciales, hops jitter<=4(S−1)N+8P, tiempo O(SNd+S²Ld*w), w4, temporal O(Ld) con DTW dos filas y matriz retenida O(S²). Trends O((Q+N)log(Q+N)), temporales O(Q+N), no por par. ADR010 §7 conserva caps/schema/roots/aliases/MIN/MAX y detalla hashes de cuatro owners/SCANNED/tests, no perfil/conformidad; sin Q/variantes/PCM/sketches duplicados retenidos, RSS/NMT/p95 por medir; planificación `O(N+S)` temporal y O(S) piezas publicadas. Render es O(F), O(1) por cursor y bloques <=65,536. Cada pasada TP cuesta exactamente F+6 llamadas por canal; solo el caso inseguro llega a hasta 20 candidatos adicionales, reutilizando todo análisis. Índice waveform: construcción `O(F*channels)`, memoria O(F/256), redraw exacto `O(W*channels*(log ceil(F/256)+512))`; a 60 min/48 kHz/W1000, <=40,000 nodos y <=1,020,000 lecturas PCM de borde. [UC-001/003/004; RF-001/005/006/008; RNF-004..006/009]

Para 60 min/48 kHz hay 172,800,000 frames. El diseño anterior `float[F]` consume 691,200,000 bytes = 659.1796875 MiB solo en envelope y queda prohibido para el Leveler nuevo desde el switch de M-005; la única excepción transitoria de M-004 es **exactamente un** envelope legado, envuelto por `DenseGainSchedule` sin copia. La sombra no puede crear un segundo `float[F]`, retener PCM, acumular chunks/STFT/espectros ni reservar direct buffers lineales con F. [UC-001/003/006; RF-005/006/012; RNF-004/005/008/009]

#### Protocolo RSS ejecutable M-004

El gate se ejecuta de verdad, no mediante modelo, para `durationMinutes={1,10,60}`, 48.000 Hz, estéreo, tres repeticiones por duración y seed fija. Cada pareja usa dos JVM hijas del mismo build/classpath: `LEGACY_ONLY` ejecuta el mapper Dense y no construye sombra; `LEGACY_PLUS_SHADOW` ejecuta el mismo Dense y después todo M-004. La única propiedad que difiere es el mode flag del harness. Orden por repetición: legacy→shadow, shadow→legacy, legacy→shadow. Se registra comando, PID, `java -version`, JDK home, Windows build, CPU/RAM, git/build identity y temperatura/actividad externa si el host la expone. [UC-001/003/006; RF-001/005/006/012; RNF-004/005/008..010]

Flags normativos del harness local: `-Xms256m -Xmx4g -XX:+UseG1GC -XX:NativeMemoryTracking=summary -javaagent:<retentionAgentJarAbsoluto> --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED`; el JAR test-only y su SHA-256 son idénticos en ambos modos, no entra al app-image y cualquier cambio se registra y obliga a repetir ambos modos. Antes de medir, cada JVM ejecuta dos análisis completos de 30 s con el mismo generador. Después libera el warm-up, solicita una sola estabilización GC antes de cargar el caso y no vuelve a solicitar GC. Preasigna el PCM final, lo llena/toca completamente con un generador xorshift32 seed `0x4d303034` y muestras dyadic finitas enlazadas pero no idénticas entre canales, y emite `PCM_READY`; el PCM ya está cargado antes de baseline en ambos modos. Se mantienen vivos PCM/resultado al menos 3 s en cada marcador `PCM_READY`, `DENSE_READY`, `SHADOW_READY` y `DONE` para hacer observables las retenciones. Timeout por JVM `max(120 s,.75*duracionAudioSegundos)`; timeout, exit no cero o marcador perdido falla el gate. [UC-001/003/006; RF-001/005/006; RNF-003..005/008]

Un proceso padre PowerShell muestrea `Get-Process -Id PID | Select WorkingSet64,PrivateMemorySize64` cada 100 ms desde antes de `PCM_READY` hasta después de `DONE`; un hueco >250 ms invalida el run. También captura `jcmd PID VM.native_memory summary` en los cuatro marcadores. `baselinePcm` es la mediana de WorkingSet64 de los últimos 2 s de `PCM_READY`; `peakRun` es el máximo observado desde el primer frame de Dense hasta fin del hold DONE; `runDelta=peakRun-baselinePcm`. Para cada pareja, `shadowIncrement=max(0,runDelta(LEGACY_PLUS_SHADOW)-runDelta(LEGACY_ONLY))`. No se consulta `Runtime.freeMemory`, `totalMemory` ni un heap estimado como sustituto de RSS. [UC-001/003/006; RF-005/006/012; RNF-004/005/008]

M-004 aprueba solo si, en **cada** repetición: `shadowIncrement<256 MiB` en 1/10/60 min, `shadowIncrement(10)-shadowIncrement(1)<64 MiB` y `shadowIncrement(60)-shadowIncrement(10)<128 MiB`. El umbral arquitectónico `<256 MiB` se aplica aquí al **delta incremental de sombra sobre el legacy comparable**, no al total que contiene los 659.1796875 MiB Dense. Desde el switch M-005, tras retirar Dense, vuelve a aplicarse al delta total del Leveler desde `baselinePcm`. El tiempo se registra por fase y p95 debe ser `<.5*duración`; no se oculta un fallo RSS por cumplir tiempo. [UC-001/003/006; RF-005/006/012; RNF-004/005/008]

#### Oracle independiente de reachability M-004

`ShadowMemoryCounters` continúa como diagnóstico, pero la autoridad conjunta pertenece a `AsyncEscapeBytecodeGuard` y `RetainedGraphAuditor`, ambos implementados solo en tests y sin API de registro productiva. El primero demuestra que el código permitido no puede publicar trabajo/referencias fuera de la llamada; el segundo mide todas las identidades alcanzables desde roots cerradas. Quitar cualquiera de los dos vuelve verde al menos un mutante obligatorio y hace fallar el test de control. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010]

##### Guard de classfiles y universo productivo cerrado

`AsyncEscapeBytecodeGuard` parsea directamente classfile Java 17 (`magic`, versión, constant pool, fields, tabla completa `method_info`/`Code`, `BootstrapMethods` e instrucciones); no usa búsqueda textual ni auto-reporte. Antes de lanzar ninguna hija exige y hashea el conjunto `SCANNED_M004_CLASSFILES`: `LevelerProcessor`, `AnalysisDynamicsProcessor`, `LevelerAnalysisEngine`, los diez componentes M-004, perfil/norma/token/FrozenList/conformance/body, más LoudnessCore/ConformanceCodec/ConformanceArtifactLoader/RequiredOfficialReading/ChannelLayout/ReadoutMode/LoudnessValueKind de ADR-011 y todos y solo los tipos M-004 enumerados campo por campo en “Tipos, unidades e invariantes”. No se permiten clases locales/anónimas, nested helpers ni otro binario M-004; auxiliares private static salvo los dos static package-private ADR010 originalNovelty(FeatureTimeline,long,LevelerCalibrationProfile)→double[] y structuralBins(FeatureTimeline,FrameRange,long)→FrozenList, en sus owners existentes. Calls/descriptors se enumeran expresamente, sin auto-whitelist. ADR010 por sí solo no añade fields, roots ni cambia el conjunto SCANNED; las ampliaciones adicionales de ADR011 son únicamente las enumeradas en este bloque y su schema-delta. La frontera QuickMaster preexistente permitida, referenciable pero no reabierta a la allowlist general de calls M-004, es exactamente `AudioProcessor`, `AnalysisStatus`, `GainSchedule`, `GainDomain`, `DenseGainSchedule`, `DenseGainCursor` y `PublishedGain`; sus hashes M-003 también se registran. Todos esos boundaries reciben además el scan mecánico acotado de tabla de métodos para ausencia de `finalize()V` declarado y `ACC_NATIVE`; `AnalysisStatus`, por ser enum boundary, se parsea también con el patrón canónico de `valueOf` descrito abajo. Una referencia QuickMaster fuera de ambos conjuntos, aun no ejecutada, falla `BYTECODE_DEPENDENCY_OUTSIDE_UNIVERSE`. Los owners DSPark M-004 permitidos son com/dspark/core/FFTReal y com/dspark/core/DspMath del JAR0.1.0 SHA de368f326f668267d0cba20f34135efb466dafc53f84a4aee32d8af4347e4141, y solo <init>(I)V, getFrequencyDomainSize()I, getNumBins()I, forward([F[F)V y computeMagnitudes([F[F)V desde StructuralFeatureExtractor; DspMath.clamp(DDD)D solo en LevelerProcessor.setLeveling(D)V/setSpeed(D)V y decibelsToGain(D)D solo en su mapFeaturesToGain()V legacy. LoudnessCore/KWeightingAdapter no añaden owner DSPark; TruePeak permanece fuera en M-005. Fields, enums, shared atoms y firmas exactos de conformance/run/binding están cerrados en schema-delta, no inferidos del código. [UC-001/003/006; RF-001/005/006/012; RNF-003..005/008..010]

El alcance anterior protege ownership, escape y retención reales de M-004 según ADR-007. No se amplía al producto completo. La prueba directa de wiring y la JVM pequeña de extensión están definidas en la sección de segmentación; la integridad portable pertenece al gate de entrega. Ninguna de esas pruebas sustituye el guard semántico, sus statics/roots/probes ni el benchmark de memoria siguiente.

La allowlist JDK literal compara `(opcode,owner,name,descriptor)`, no package ni “lo observado” en el build. Las familias previas se conservan abajo. ADR-011 añade exclusivamente los tuples por owner/contexto de schema-delta: lectura del mismo JAR solo en ConformanceArtifactLoader, construcción String ASCII en codec/normalización owned y Double.longBitsToDouble para raw bits. Esta excepción no permite I/O/URL/Class en DSP, ni reflexión, red, paths variables o nuevas APIs genéricas. Todas las otras calls siguen prohibidas. Familias previas:

| Owner exacto | Calls/fields exactos permitidos |
|---|---|
| `Object`, `Enum`, arrays, excepciones aprobadas | `Object.<init>()V`; `Enum.<init>(String,int)V`; la única `Enum.valueOf` contextual descrita abajo; `clone()Object` solo en `$VALUES`; constructores `()V`/`(String)V` de `IllegalArgumentException`, `IllegalStateException`, `ArithmeticException`, `RuntimeException`; adicional ADR012 solo `invokespecial java/lang/IndexOutOfBoundsException.<init>(Ljava/lang/String;)V` |
| `Math`, `StrictMath` | overloads primitivos de `abs,min,max,round,ceil,floor,floorDiv,addExact,multiplyExact,sqrt,pow,exp,log,log10,sin,cos`; fields constantes `E/PI`; adicionales ADR012 solo `invokestatic java/lang/Math.toIntExact(J)I`, `java/lang/StrictMath.signum(D)D`, `java/lang/StrictMath.copySign(DD)D` y `java/lang/StrictMath.log1p(D)D` |
| `Double`, `Float`, `Integer`, `Long` | `isFinite`, `compare`, conversiones raw-bits y parse/conversión primitiva usada por validación; ningún cache/boxing retenido |
| `String`, `System` | `String.length/charAt/equals/hashCode/getBytes(US_ASCII)`; adicionales ADR012 solo `invokevirtual java/lang/String.isEmpty()Z` y `java/lang/String.compareTo(Ljava/lang/String;)I`; `System.arraycopy`; ninguna propiedad/env/IO |
| `Arrays`, `Objects`, `BitSet` | `copyOf/fill/sort/equals/hashCode` solo sobre arrays primitivos/Object[]; `Objects.requireNonNull`; `BitSet.<init>(int)`, `set(int)`, `get(int)`, `cardinality`, `length` |
| `MessageDigest`, `NoSuchAlgorithmException`, `StandardCharsets` | `MessageDigest.getInstance("SHA-256")`, `update(byte|byte[])`, `digest()` y `US_ASCII`; adicional ADR012 solo `invokevirtual java/security/MessageDigest.update([BII)V` sobre SHA-256/chunk8192 local; ninguna otra primitive/provider API |

Todo descriptor concreto de esas filas se enumera en el fixture; un nombre/overload no listado falla. La excepción contextual de enums, distinta del borde I/O explícito ADR-011, sigue siendo exactamente `invokestatic java/lang/Enum.valueOf:(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;`, y solo dentro de `public static valueOf(Ljava/lang/String;)LE;` del owner enum actual `E` (`ACC_ENUM`). Su `Code` debe tener cero handlers y exactamente esta secuencia, sin branch, side effect ni instrucción adicional: `ldc` o `ldc_w` de `CONSTANT_Class E`, `aload_0`, esa `invokestatic`, `checkcast E`, `areturn`. El literal, retorno del descriptor y checkcast deben nombrar al mismo `E`; el método no es synthetic/bridge y sus únicos access flags son `ACC_PUBLIC|ACC_STATIC`. La excepción no autoriza otra llamada/campo de `Enum` o `Class`, otro descriptor, owner o contexto. `values()`, `$values()`, `$VALUES`, `<init>` y `<clinit>` siguen bajo sus reglas literales existentes. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010]

El guard compila con `javac --release 17` y parsea un fixture positivo por cada owner/forma enum M-004, además de parsear el `AnalysisStatus` preexistente después de verificar su hash M-003; todos deben reproducir la secuencia anterior. Mutantes separados cambian owner de la call, descriptor, class literal, checkcast, nombre/descriptor/access del método, orden de instrucciones o añaden una segunda llamada; todos fallan `ENUM_VALUEOF_NONCANONICAL`. Quitar solo la excepción canónica hace fallar los positivos y dispara su control de sensibilidad. Los boundaries M-003 restantes conservan hash congelado más el scan de seguridad de method table; no se someten retroactivamente a la allowlist completa de calls M-004. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010]

Antes de inspeccionar `Code`, el parser recorre todas las entradas `method_info` de cada classfile M-004, `LevelerProcessor`, `AnalysisDynamicsProcessor` y los boundaries congelados citados. Cualquier método de instancia (`ACC_STATIC` ausente) llamado `finalize` con descriptor `()V` falla incondicionalmente `FINALIZER_DECLARATION`, con independencia de access flags, `ACC_SYNTHETIC`, atributo `Exceptions`, presencia/ausencia o contenido de `Code`; no depende de encontrar un Methodref a `Object.finalize`. Todo método con `ACC_NATIVE` (`0x0100`), estático o de instancia y tenga o no `Code`, falla `NATIVE_METHOD_DECLARATION`. `ACC_ABSTRACT` y métodos de interfaz solo se admiten en los boundaries M-003 previamente hasheados/aprobados y, por ADR012, exclusivamente en `com/quickmaster/processing/dynamics/AnalysisDynamicsProcessor` con class access `0x0421` y sus declaraciones protected abstract `computeFeatures([FIII)V` y `mapFeaturesToGain()V`, ambas `0x0404` y sin Code. No existe excepción por package/subclass/owner modificable ni para otro abstract; native/finalize siguen prohibidos incluso en este owner. Así el código M-003 congelado sigue aceptado si no declara native/finalizer, sin abrir JNI ni lifecycle implícito. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010]

Se mantienen prohibidos `java.lang.ref`, Cleaner y hooks de Runtime. No se añade `--finalization=disabled` a los flags normativos: la garantía es una propiedad del classfile productivo y debe conservarse en cualquier lanzamiento instalado, no depender de una opción de una JVM concreta. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010]

Se rechaza cualquier referencia a `Thread`, `ThreadLocal`, `InheritableThreadLocal`, `java.util.concurrent.*`, `java.util.stream.*`, `Timer`, `TimerTask`, `Runtime.addShutdownHook`, `Cleaner`, `java.lang.ref`, reflection, `sun.misc.Unsafe`, `jdk.internal.misc.Unsafe`, `ClassLoader`, servicios, sockets o filesystem, salvo exclusivamente los tipos y tuples de lectura de entries del propio JAR en ConformanceArtifactLoader de ADR-011; ningún resto del universo recibe esa excepción. De `java.lang.invoke` solo se admite el enlace estructural exacto de `StringConcatFactory.makeConcatWithConstants`: retorno `String`, argumentos escalares/String y cero interfaz funcional capturada; `LambdaMetafactory`, `altMetafactory`, `ObjectMethods` y todo otro owner/bootstrap fallan. Por eso los DTO M-004 son clases `final`, no records. Constantes `CONSTANT_MethodHandle/MethodType` solo pueden pertenecer a esa concatenación y nunca ser alcanzadas por `Code`. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010]

Cada `putstatic` se decodifica. Solo se admite dentro de `<clinit>` del mismo owner y contra una entrada literal de schema: constantes enum y `$VALUES`; `LevelerCalibrationProfile.V1`, `LoudnessStandard.BS1770_5`, `ConformanceRequirement.OFFICIAL_LOUDNESS_V1`; y el shared atom legacy `DenseGainSchedule.UNIT`. Un field `static final` primitivo/String con `ConstantValue` no genera write. Cualquier otro `putstatic`, field estático no final, setter estático o write desde otro método falla `PRODUCTIVE_NON_CONSTANT_PUTSTATIC`. El guard compara además nombre/descriptor/modifiers de **todos** los fields de instancia y estáticos, aunque estén a null. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010] El initializer de OFFICIAL_LOUDNESS_V1 contiene únicamente la tabla de 94 entries/pins del schema; E_REQ=94 es ConstantValue. Nuevos enums solo usan sus constantes/$VALUES canónicos. Core/codec/loader/run/binding no tienen statics añadidos. La tabla se audita recursivamente con todos sus fields/valores/longitudes exactos como shared boundary fija.

El cierre estático finito ADR012 y su anexo `milestones/M-004/static-baseline-contract-001/static-baseline.001.json` (SHA-256 `6f18e4d64926edbce089590ed9dabdf63a854b11fbb797bd1fdd1031705cf16b`) son literales: 85 fields contrastados, 83 de autoridad012 y 2 de LoudnessStandard solo referencia011; enums DiagnosticCode/ReferenceReason/ShadowAnalysisStatus/SimilarityRejectionReason con nombres/orden/flags/$VALUES y métodos canónicos exactos. BodyEligibility es temporal, no root retenida. Legacy solo conserva fields M003 más shadowAnalysis ya permitido; retirar AnalysisDynamicsProcessor$1 y NoSuchFieldError mediante la comparación exacta del anexo, nunca admitir el helper. Perfil V1/IDs/ConstantValue quedan fijados sin recalibrar. Exception solo join StackMapTable/LVT del multi-catch analyzeShadow; Deprecated solo metadatos vacíos de gainEnv; ningún permiso ejecutivo/general de esas clases. DSPark y los dos fields de LoudnessStandard tienen autoridad única ADR011 (schema-delta.001.json SHA-256 `59e4ad48616254d9d61a83793488790dfb85385416893b26578b84a1226396df`), deduplicada por identidad exacta; no wildcard ni segundo schema. ADR012 no añade retención ni cambia MIN/MAX; fuente/fixture/inventario observado no autorizan nuevos permisos.

##### Agente, statics y defensas runtime

El harness construye antes de cualquier hija un JAR mínimo desde `target/test-classes` mediante `JarOutputStream`, con `Premain-Class`, en una ruta absoluta dedicada fuera de `target/app`; no transforma el build positivo. Su SHA-256, el de los classfiles y el de schema compuesto de retención ADR-011 deben ser idénticos en `LEGACY_ONLY` y `LEGACY_PLUS_SHADOW`, y un escaneo ZIP exige ausencia del agente en `quickmaster.jar`, `target/app` y el app-image. Flags normativos, en ambos modos, incluyen exactamente ambos opens: `--add-opens=java.base/java.lang=ALL-UNNAMED` y `--add-opens=java.base/java.util=ALL-UNNAMED`. Antes del warm-up, un probe exige `Instrumentation`, `getObjectSize`, `String.value` como `byte[]` y `String.coder`, `Thread.threadLocals/inheritableThreadLocals` y `BitSet.words` como `long[]`; quitar `java.lang` falla String/ThreadLocal y quitar `java.util` falla BitSet, antes del benchmark largo. [UC-001/003/006; RF-005/006/012; RNF-003..005/008/009]

El owner tiene exactamente un field nuevo, `private volatile ShadowAnalysisSnapshot LevelerProcessor.shadowAnalysis`. El auditor recibe el owner real y extrae el field por reflexión. Su universo de estáticos no se descubre por package: es la misma lista literal del guard más `Class.getSuperclass()`/interfaces y todos los holders QuickMaster permitidos por la clausura de field descriptors. Así incluye explícitamente `LevelerProcessor`, `AnalysisDynamicsProcessor`, `AudioProcessor`, los holders Dense/Published y cada clase M-004, aunque estén en `processing.dynamics`. Cada field static —sintético, null o no null— debe casar una entrada. Solo se admiten constantes enum/`$VALUES`, `static final` primitivos/String y los cuatro shared atoms anteriores con identidad y hash recursivo exactos; arrays/colecciones dentro de esos atoms solo existen si están descritos por ese hash/cap. Cualquier otro array, holder, `ThreadLocal`, Future, lambda o null desconocido es `MEMORY_OWNERSHIP_UNACCOUNTED`. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010]

El PCM `float[F*C]` es root externa. `AnalysisDynamicsProcessor.published -> DenseGainSchedule.sampleEnv` es otra frontera externa y contiene por identidad el único `float[F]` transferido; `gainEnv` está a null. `sectionLoud`, `sectionPeak` y `DenseGainCursor` son fronteras legacy fijadas y comparadas con `LEGACY_ONLY`. Cualquier camino desde sombra a PCM, Dense o legacy es violación. Identidad se decide con `==` y ordinales de `IdentityHashMap`, nunca `identityHashCode`. [UC-001/003/006; RF-005/006/012; RNF-002..005/008/009]

El worker one-shot existe solo en el harness hijo y llama a la API productiva síncrona; antes de `SHADOW_READY` retorna, termina, se hace `join` y el harness libera su única referencia. En `PCM_READY` se congela un baseline de: identidades/estado de todos los threads; pares key/value de `threadLocals` e `inheritableThreadLocals` de cada thread baseline; contenido por identidad de las colas test-only registradas; y `HarnessEscapeSentinel.value==null`. En `SHADOW_READY` y `DONE` se repite la captura: thread añadido/vivo, entrada ThreadLocal nueva/cambiada, elemento nuevo/cambiado en cola o sentinel no null abre una root externa prohibida. El auditor recorre el valor nuevo y lo suma a `unaccountedRetainedBytes` con path `external.threadLocal[...]`, `external.queue[...]` o `external.sentinel`; no lo descuenta de `externalBoundaryBytes`. Esta defensa runtime no sustituye el guard: prueba escapes test-only conocidos y cambios de host. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010]

##### Whitelist DTO, adapters y caps exactos

El BFS productivo empieza en `shadowAnalysis.result`, `.cache`, `.diagnostics`, en ese orden; fields se ordenan por `(declaring binary name,field name)` y arrays/FrozenList por índice. Cada identidad se mide una vez con `Instrumentation.getObjectSize`; aliases posteriores son edges. `FrozenList` es el único contenedor de aplicación y solo posee su `Object[]`. No se admite `List`, `Optional`, `OptionalDouble`, `EnumSet`, `Map`, buffer directo ni otra colección JDK retenida. Los únicos adapters JDK owned son:

- `BitSet`: exactamente `long[] words`, `int wordsInUse`, `boolean sizeIsSticky`; se construye con capacidad lógica R, exige `words.length==ceilDiv(R,64)`, `sizeIsSticky==true`, `wordsInUse` igual al último word no cero +1 y bits >=R limpios.
- `String`: el probe exige compact String LATIN-1 (`byte[] value`, `coder==0`). IDs de algoritmo/perfil/requirement/set/version y `""` son shared literals de identidad fijada. Solo son owned dinámicos H_b hashes del report (H_b=4 si binding completo, H_b=0 y cuatro slots `""` shared si ausente), hasta dos manifest hashes y, por evidencia, `signalId`/`signalSha256`; cada String owned tiene exactamente un `byte[] value`. Hashes son 64 ASCII hex; `signalId` 1..128 ASCII. Los cuatro hashes presentes y los dos Strings por evidencia poseen arrays byte distintos, aun si repiten valor; no aliasan la tabla de requirements. IDs compartidos tienen caps 64, versiones 32, pero no se contabilizan como owned.

`OfficialSignalEvidence.setId/setVersion` aliasa por identidad al set padre. `ShadowAnalysisResult.referencePlan==ShadowAnalysisCache.referencePlan`; `ReferenceTarget.segmentId` y `ProtectionDecision.id` aliasan el `SegmentDescriptor.id`, y `SegmentDescriptor.range` aliasa `SegmentLayout.regions[i]`. Toda otra duplicación o alias no declarado falla. El schema literal enumera los fields y modifiers de la sección de tipos, incluidos null prohibidos; un field extra/omitido/renombrado, descriptor/modifier distinto o objeto que case cero/múltiples reglas falla antes de contar bytes. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010]

Dimensiones exactas: `H100=max(1,round(.1*sr))`, `Wm=max(1,round(.4*sr))`, `Wq=max(1,round(3*sr))`, `H500=max(1,round(.5*sr))`; `M=F==0?0:ceilDiv(F,H100)`, `Q=F==0?0:ceilDiv(F,H100)`, `N=F==0?0:ceilDiv(F,H500)`, `0<=S<=64`, `P=S*(S-1)/2`, `C in {1,2}`, `B=2048`, `L=32`, `d=22`, `D=diagnostics.size<=8*S+64`, `G=groups.size`, `R=pairs.size`, `K=sum(groupMembers)+2R<=S`, `E=sum(set.evidence.size())<=E_REQ`. `E_REQ=94` es la cardinalidad literal del perfil file-based ADR-011 duplicado en tests y ligado a su hash; no se infiere del corpus ni se amplía en runtime. `H_b in {0,4}` cuenta hashes owned del binding ausente/completo. `J` es el número de manifests presentes, `0<=J<=2`; strings owned `U=H_b+J+2E`. [UC-001/003/006; RF-001/005/006/012; RNF-003..005/008..010]

| Familia | Identidades/arrays exactos |
|---|---|
| root | 1 `ShadowAnalysisSnapshot`, result, cache, diagnostics, counters, report, format, fingerprint `byte[32]`; 1 `ReferencePlan` aliasado; 2 `RequiredSetReport` |
| loudness | 1 timeline, `double[M]`, BitSet+`long[ceilDiv(M,64)]`, `double[Q]`, BitSet+`long[ceilDiv(Q,64)]`, 1 `MeasuredLoudness` integrated |
| features | 1 timeline, 1 FrozenList/backing de N; N frames, N `double[12]`, N `double[8]`; exactamente `N*d` escalares lógicos |
| regiones/descriptores | S ranges/IDs/descriptors/regional loudness/context/sketch; raíz descriptors, raíz layout y S listas de bins con backings exactos; S*L bins con `double[12]`+`double[8]`; S `double[C][]` y S*C `double[B]` |
| protecciones/scores | S decisions y S flags; raíz protections; 1 matrix y P scores exactos, cada score 4 doubles+2 ints+1 enum y ningún boolean/array |
| grupos/pares | 1 result, G groups, R pairs, dos listas/backings; por grupo un `int[k]` y `double[k]`, mismas longitudes; K<=S; ningún offset/map/set |
| referencia | S targets, S measured references, lista/backing y `double[S]`; IDs aliasados y razón enum compartida |
| diagnóstico/conformidad | D entries y lista/backing; exactamente dos set reports, lista/backing y dos evidence lists/backings; E evidencias; U Strings owned y U `byte[]`; toda la familia es independiente de F |

El número máximo de identidades owned es exactamente `48 + 3*N + 13*S + 3*S*L + S*C + P + 3*G + R + D + E + 2*U`; el de arrays es `18 + 2*N + 2*S + 2*S*L + S*C + 2*G + U`; el cap de edges, contando shared boundaries pero no recorriéndolas, es `70 + 3*N + 17*S + 3*S*L + S*C + 2*P + 3*G + R + 2*D + 10*E + U`. Cada sumando se construye con `Math.addExact/multiplyExact`; el test refleja todos los fields y vuelve a derivar las tres fórmulas, no copia un total opaco. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010] Derivación del delta ADR-011: un field report String añade un edge; cuatro refs enum por evidencia añaden 4E edges sin nodos; presencia de runnerSha256 añade un String y su byte[64] por U. Nueve long, minimum double, reset int y EOF boolean añaden 85 bytes lógicos por evidencia, pero ningún nodo/array/edge; shallow alineado se mide.

Los payloads lógicos que escalan con audio son, separados, `8*(M+ceilDiv(M,64)+Q+ceilDiv(Q,64))`, `8*N*d`, `8*S*L*d` y `8*S*C*B`. Otros caps primitivos son `40*P` bytes lógicos de score, `12*sum(groupMembers)` de arrays de grupo, `8*S` de weights, 32 de fingerprint y como máximo `64*H_b+64*J+192*E` bytes de String owned. Headers, refs y escalares dentro de objetos entran por shallow size real, no por estimación. No existe término `k*F`, `F/q`, PCM, chunk, STFT ni spectrum. [UC-001/003/006; RF-001/005/006/012; RNF-003..005/008..010]

Dos goldens conceptuales cierran la whitelist:

- `SNAPSHOT_MIN` ligado (`MIN_BOUND`): sr48000,C1,F1,M=Q=N=S1,P=G=R=D=E=J0,H_b4,U4; masks y aliases anteriores íntegros, dos sets UNAVAILABLE, manifests `""` y cero evidencia; exactamente169 identidades/91 arrays/191 edges. Es fixture de grafo, no atestación oficial. Variante obligatoria `MIN_UNBOUND`: mismo grafo, H_b=U=0, estado NOT_RUN y cuatro hashes `""` shared, exactamente161/87/187.
- `SNAPSHOT_MAX_60M` ligado: sr48000,C2,F172800000,M=Q36000,563 words por máscara,N7200,S64,P2016,B2048,L32,d22,D576,G21 grupos de3,R0,H_b4,J2,E=E_REQ=94,U194. Polinomios31419+5E /18818+2E /34283+12E: exactamente31889 identidades/19006 arrays/35411 edges. Payloads centrales585008/1267200/360448/2097152 bytes sin cambios. Fixture estructural sintético FAILED, no evidencia PASSED. Cada field/alias/cap/enum nuevo y retención indebida de core/run/binding/codec/loader falla por regla específica; unaccounted=0.

Para cada identidad alcanzable, `totalRetainedBytes` suma el shallow size del agente. Exactamente una regla válida mueve ese mismo tamaño a `accountedAllowedBytes`; cero/múltiples reglas lo mueve a `unaccountedRetainedBytes` y el recorrido continúa. El padre reconstruye filas y exige `totalRetainedBytes==accountedAllowedBytes+unaccountedRetainedBytes`, después `unaccountedRetainedBytes==0` y por tanto `total==accounted`. El reporte conserva root/path/ordinal/clase/regla/count/length/shallow bytes. `externalBoundaryBytes` registra PCM/Dense/legacy pero nunca compensa un desconocido; falsear `ShadowMemoryCounters` no cambia el oracle. [UC-001/003/006; RF-005/006/012; RNF-003..005/008/009]

#### Contraejemplos sub-F y mutantes obligatorios

A 48 kHz, `F=2,880,000 / 28,800,000 / 172,800,000` frames para 1/10/60 min. La tabla usa payload de elementos exacto, sin header; el shallow size real adicional del array/holder lo aporta el agente:

| Retención no registrada | 1 min bytes / MiB | 10 min bytes / MiB | 60 min bytes / MiB | crecimiento MiB 1→10 / 10→60 |
|---|---:|---:|---:|---:|
| `byte[F/2]` | 1,440,000 / 1.373291015625 | 14,400,000 / 13.73291015625 | 86,400,000 / 82.3974609375 | 12.359619140625 / 68.66455078125 |
| `short[F/4]` (`F/4` elementos, 2 bytes) | 1,440,000 / 1.373291015625 | 14,400,000 / 13.73291015625 | 86,400,000 / 82.3974609375 | 12.359619140625 / 68.66455078125 |
| `byte[multiplyExact(F,9)/10]` | 2,592,000 / 2.471923828125 | 25,920,000 / 24.71923828125 | 155,520,000 / 148.3154296875 | 22.247314453125 / 123.59619140625 |

Los tres caben bajo `<256 MiB` y sus dos pendientes bajo `<64/<128 MiB`; longitud `<F` tampoco los detecta. Por ello los perfiles negativos `UNREGISTERED_BYTE_HALF`, `UNREGISTERED_SHORT_QUARTER` y `UNREGISTERED_BYTE_NINE_TENTHS` insertan cada array en un field deliberadamente ausente de la whitelist y lo mantienen hasta `DONE`. `UNREGISTERED_NESTED` retiene `UnregisteredHolder.parts=Object[]{byte[F/4],byte[F/4]}`: payload total F/2 más shallow sizes del holder/outer array. Los cuatro se ejecutan al menos una vez con F real de 60 min, auto-contadores falsos a cero y el mismo RSS/NMT; todos deben terminar `MEMORY_OWNERSHIP_UNACCOUNTED` por path/tipo aun si pasan RSS. [UC-001/003/006; RF-005/006/012; RNF-003..005/008/009]

Los mutantes de escape son ejecutables y test-only, nunca una excepción productiva a la política. `ClassfileMutator` añade a una copia aislada de `LevelerProcessor.class` el field `private static byte[] ownerEscape`; el agente la carga solo en el perfil negativo y el harness asigna por reflexión `byte[F/2]` después de probar el guard sobre los bytes prístinos. El guard debe rechazar la copia por schema/field y el static scan debe encontrar `ownerEscape`; un control `STATIC_SCAN_ONLY` demuestra que retirar el scan vuelve falsamente verde el perfil. Fixtures fuera del universo productivo envuelven la llamada síncrona y después: (a) guardan `byte[F/2]` en un `ThreadLocal` sin key static de un thread llamante baseline persistente; (b) encolan un `FutureTask`/lambda bloqueada que captura el array en una cola y thread baseline; (c) registran un callback test-only capturante en esa cola; y (d) escriben `HarnessEscapeSentinel.value`. No crean un thread adicional entre baseline y DONE. Los diffs de ThreadLocal/cola/sentinel deben hallar cada root en `SHADOW_READY` y `DONE`; quitar cada probe vuelve verde su mutante y hace fallar el control. Separadamente, classfiles fixture con cada referencia async, `LambdaMetafactory` y `putstatic` no constante deben ser rechazados por el guard antes de ejecutar. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010]

Dos fixtures method-table son independientes de calls y de timing JVM. `FINALIZER_DECLARATION` añade a una copia de clase `final` conforme un método de instancia `protected finalize()V` cuyo único bytecode es `return`, sin Methodref, field access, bootstrap, excepción ni segunda mutación; debe fallar únicamente `FINALIZER_DECLARATION`. Una instancia así, aunque producto no la publique, puede ser registrada por la VM bajo una root `java.lang.ref.Finalizer` y retener transitivamente su `Object[]{byte[F/2]}` fuera de shadow/statics/ThreadLocal/colas/sentinel. El test no fuerza GC ni espera finalización: quitar solo la regla declarativa debe aceptar el fixture y, por ello, hacer fallar el control. `NATIVE_DECLARATION` añade por separado un método `ACC_NATIVE` sin `Code`; falla únicamente `NATIVE_METHOD_DECLARATION`, y retirar solo esa regla lo deja pasar y hace fallar su control. Ambos rechazos ocurren antes de RSS/ejecución. [UC-001/003/006; RF-005/006/012; RNF-003..005/008..010]

Mutaciones de control también son obligatorias: sin bytecode guard, sin static scan, sin `-javaagent`, con root omitida, schema/whitelist hash distinto, regla default que permita `UNKNOWN`, auditor que devuelva ceros, reconciliación eliminada o cualquiera de los dos add-opens ausente, el gate falla. Se conservan además los ataques previos `(a) float[F], (b) alias PCM, (c) espectro por hop, (d) chunks concatenados, (e) direct buffer F`; deben fallar el auditor independiente y, cuando aplique, counters/RSS. NMT no puede mostrar una arena/direct exclusiva desconocida. RSS/Private/NMT detectan presupuesto/host; guard+statics+roots demuestran exhaustividad de publicación y retención; ninguno sustituye a los otros. [UC-001/003/006; RF-001/005/006/012; RNF-003..005/008..010]

Antes de reservar todo array se usa aritmética `long`/`Math.multiplyExact`, cap por presupuesto y conversión comprobada a `int`. Features pueden almacenarse por hop porque para una hora N≈7,200; no se retienen STFTs, espectros por frame ni PCM duplicado. Matrices son solo S×S. La UI no redownsamplea toda la pista en cada zoom. [UC-001/003/004; RF-001/005/008; RNF-004..006/009]

## Seguridad y privacidad

Todo cálculo es offline y determinista: sin sockets, URLs, telemetría, modelos ni descarga de corpus. Fixtures musicales deben estar legalmente autorizados y no entrar al paquete de producción salvo licencia explícita. SHA-256 identifica cache, no autentica archivos ni sale del proceso. [RNF-010]

Audio y metadatos se consideran entrada no confiable: límites y finitud se validan antes de indexar/asignar; no se construyen rutas desde tags; diagnósticos no serializan muestras. Objetos publicados son inmutables; los temporales M-004 pertenecen a la llamada síncrona y el worker es solo del harness. `process` no bloquea ni toma locks. Las generaciones evitan que un resultado de otra pista cruce de sesión. [UC-003/006; RF-005/006/012; RNF-002..005/010]

La entrega usa solo dependencias/versiones existentes, `jpackage` y rutas absolutas verificadas. Desplegar en `C:\Program Files\QuickMaster` requiere identificar/cerrar exclusivamente procesos QuickMaster, validar destino antes de reemplazar y comparar el árbol final completo en ambos sentidos más los tres JAR según AC12; nunca se borra recursivamente una ruta calculada o amplia. [UC-006; RF-012; RNF-008]

## Módulos y ownership de clases

| Paquete/archivo lógico | Clases propietarias | Dependencias permitidas |
|---|---|---|
| `processing.dynamics` | `GainSchedule`, `DenseGainSchedule`, `SparseGainSchedule`, `GainPiece`, `PublishedGain`; adaptación de `AnalysisDynamicsProcessor` | JDK; ninguna UI |
| `processing.dynamics.LevelerProcessor` | façade, propiedades compatibles, cache/factory/adopción, orquestación de análisis | base + `dynamics.leveler` |
| `processing.dynamics.leveler` | M-004: `LevelerAnalysisEngine`, perfil/norma/token, diez componentes de análisis, LoudnessCore, ConformanceCodec y ConformanceArtifactLoader; helpers como métodos; M-005 añade `GainPlanner`/`TruePeakSafety` fuera del universo M-004 | allowlist exacta; I/O solo loader del mismo JAR; DSPark solo FFTReal y DspMath legacy del pin/tuples fijados; ninguna JavaFX |
| `processing.dynamics.leveler.model` | clases `final`/enums M-004 `ShadowAnalysisResult/Cache/Snapshot`, timelines, layout/descriptores, scores, group/pair/target/validation/status; M-005 añade tipos distintos | JDK permitido; sin lógica UI, records ni colecciones JDK retenidas |
| test harness `processing.dynamics.leveler.memory` | `ShadowReachabilityAgent`, `AsyncEscapeBytecodeGuard`, `RetainedGraphAuditor`, `RetentionWhitelistV1`, `ClassfileMutator`, `LevelerShadowMemoryHarness` | JDK `java.lang.instrument`/reflection/classfile parser; nunca dependencia productiva ni app-image |
| test harness de extensión | oracle de wiring directo y JVM hija pequeña con `ThreadMXBean`/JDI | JDK `jdk.management`/`jdk.jdi` solo tests; no fields/hooks/paquete productivo ni ampliación de `SCANNED_M004_CLASSFILES` |
| `ui.waveform` | `WaveformViewport`, `TimelineViewState`, `TimelineEditRebaser`, `WaveformPeakIndex` puros; `WaveformGestureAdapter` como único borde JavaFX | modelos puros → JDK; adapter → JavaFX |
| `ui.MainController` | wiring snapshot/generation, conversión fade-out, render y handlers; no fórmulas DSP/viewport | façades anteriores |

No se crea un “framework” genérico MIR ni un módulo Maven nuevo. Los DTO M-004 son clases `final` package-private agrupables por dominio, con exactamente el schema fijado; ningún array mutable se expone. Dependencias fluyen input→análisis→decisión→plan y modelo→adapter→controller, nunca al revés. [RNF-009]

## Pruebas y estrategia de verificación

### Capas y oráculos

1. **Conformidad normativa y EOF.** G-101 sigue como golden matemático no oficial y PCM44.1/48/96 mono/estéreo/antifase. Se ejecuta la matriz finita C01..C30 de ADR-011: 94 claves offline (39ITU+55EBU), dos NO_LOUDNESS categóricas, ±.1 inclusivo, readout frame-clock, layouts1/2/5/6 con producto1/2, parser de originales y tres RIFF cortos exactos, reset/EOF/hash, factory/codec/build y ausencia de corpus sin PASS. Solo el runner autorizado completo y el JAR revalidado pueden aportar PASSED ligado al build. EBU11/14 quedan LIVE no aplicables explícitos; TP15..23 y FiniteTruePeakStream/+ .2/- .4dBTP se verifican separadamente en M-005. No se mide un analyzer test-only ni se certifica todo EBU Mode. [UC-001/003/006; RF-001/006/012; RNF-003/004/008..010]
2. **Goldens de decisión.** `BoundaryDetectorTest` ejecuta `FT_MAD0_TIE`, `FT_EXACT_64`, `FT_PERSISTENT_65`, `FT_RETRY_ORDER`, `FT_EXACT_TAIL_ALIAS` y `FT_INVALID_EXTENT` con `totalFrames` explícito y boundaries/IDs/end exactos; este último incluye `F=4294967297,H=1,size=1` rechazado antes de novelty/reserva y los válidos `F=1,H=1`/`F=Long.MAX_VALUE,H=Long.MAX_VALUE` con una región exacta. Mutar intervalo, mediana, igualdad, tie, separación, secuencia de retries, ignorar F, inferir la cola o estrechar `N_expected` sin comprobación segura falla. `LevelerAnalysisEngineIntegrationTest` ejecuta ambos PCM cero exactos `F=47999/48000` a 48 kHz mono y exige `H=24000`, centros `[11999,35999]`, `DONE/READY` y ends 47999/48000 iguales al `AudioFormat.frames` cacheado; no acepta un caso no múltiplo sustituto. `BodyContextGateTest` ejecuta los dos PCM sketches, los cuatro vecinos IEEE de `1e-4/.05`, orden de lag/canal/cola y N08 sin gate como mutación negativa. `SegmentComparatorTest` cubre 32/24/23 bins, racha 5, NaN, norma cero, transposición/tie-break y valores `IDENTICAL=1`, `ORTHOGONAL H=1,T=.4,A=.65,C=.45`. `ComparableGroupBuilderTest` reproduce matriz A/B/C/X/Y, cadena no transitiva, externa, confianza exacta, mutual-best/tie y margen `.15`. `ReferencePlannerTest` cubre water-filling `(1,0,0)->(.4,.3,.3)`, mediana inferior, punto medio y afirma por separado `reference=-20`, `rawDb=+5`, `gConf=.43860126820672457`, `confidenceWeightedDb=2.1930063410336227`; Leveling/Speed no cambian ningún bit. [UC-001/002/003; RF-001..005; RNF-001/003..005/009]
3. **Goldens de plan/lifecycle.** `RampAllocatorTest` ejecuta a 48 kHz los 27 cruces P/M/M/P con `d_iFrames=StrictMath.round((rho*Tnom)*48000)`, `FrameRange(long)` y el mismo orden no preplegado `q→R→needFrames→fits`; incluye target M=0, casos sin solución, geometría inválida, tres runs bit a bit y la aserción negativa del antiguo `0x1.c2717456e04bdp-2`. Comprueba caps/targets/endpoints regenerados, `<=192` piezas, no solape, C1, pendiente <=2 dB/s, protegido exacto y magnitud no decreciente con Leveling. En fixtures separados que mantienen `FrameRange` fijo comprueba monotonía de Speed y duración no creciente para targets fijos; también `allocateFixed` al reducir solo boosts. `DenseGainScheduleCompatibilityTest` ejecuta la tabla raw-bit de `-2/-1/0`, final, después del final y posiciones fraccionales a 1x/2x/4x, el contraejemplo `0x3ef35eea!=0x3ef35ee9`, selección única de renderer por bloque, cero asignaciones en `process` y meter Dense legacy. `SparseGainScheduleTest` añade unidad fuera de dominio, lookup secuencial/seek, conversión dB/StrictMath, meter desde `gDb` y equivalencia temporal 1x/2x/4x. `AnalysisDynamicsCompatibilityTest` garantiza en M-003 la salida previa raw-bit de Peak/Beat/Punch/Leveler, y en M-004 que el cálculo nuevo en sombra no puede cambiar `PublishedGain` ni entrar por adopción. M-005 añade el oracle del único switch atómico Dense→Sparse tras pasar rampas/TP/fixtures, manteniendo Peak/Beat/Punch bit a bit, además de adopción concurrente. `LevelerCacheTest` usa contadores de FFT: controles conservan cero recomputaciones; un bit PCM/order/rate/profile distinto invalida. [UC-002/003/006; RF-004..006/012; RNF-002..005/007/009]
4. **Fixtures musicales multirrasgo.** Se sintetizan con transitorios, bajo, acordes, melodía/voz y ruido/reverb controlados, no tonos que revelen trivialmente el oracle; 44.1/48 kHz, mono/estéreo. `unchanged` significa `|gain|<=.5 dB` y ninguna rampa invasora; `leveled`, gap residual baja >=2 LU sin invertir orden. [UC-001..003; RF-001..006; RNF-001..005/009]

| Set | Casos obligatorios y oracle esencial |
|---|---|
| Positivos | `P01_ABA_LEVEL`: pareja interior no gain-scaled converge; `P02_REPEAT_OUTLIER`: solo outlier de grupo cambia; `P03_STEREO_LINKED`: misma curva L/R, imagen y TP conservados. |
| Negativos | `N01_LONG_INTRO`, `N02_LONG_BREAK`, `N03_SHORT_BREAK`, `N04_OUTRO_FADE`, `N05_CRESCENDO`, `N06_UNIQUE_BRIDGE`, `N07_SILENCE_SPARSE`, `N08_GAIN_SCALED_INTENT`, `N09_FLAT_INTRO_REPEAT`, `N10_FLAT_OUTRO_REPEAT`, `N11_REPEAT_IN_BUILDUP`: razón prevista y unidad/contorno preservado. |
| Adversariales | `A01_SHARED_ACCOMP`, `A02_SAME_CHROMA_TIMBRE`, `A03_TRANSPOSED_REPEAT`, `A04_BOUNDARY_JITTER`, `A05_INTERSAMPLE_PEAK`, `A06_DENSE_NOVELTY`, `A07_ANTIPHASE_RATE` a 44.1/48/96 kHz y `A08_SHORT_EMPTY`: gates, TP, cap, finitud y fallback previstos. |

5. **Propiedades.** Para seeds persistidos: misma entrada/runtime da decisiones/targets bit a bit en tres runs; disabled/Leveling0/unidad son bit-exact; longitud/alineación/canales se conservan; ningún output es no finito; gain está `[-6,+3]`, protegido exactamente 0 dB, derivada acotada, piezas ordenadas; la diferencia L/R relativa permanece; cambio de controls no altera grupos/raw targets. Todo resultado `PROVEN` se remide con kernel/canal/candidato nuevo y exactamente seis ceros EOF, y satisface `candidateTp<=min(1,inputTp*10^(.1/20))`. `A05` debe tener sample peak <0 dBFS y true peak cercano/sobre el techo para demostrar que el test no confunde métricas. [UC-001..003/006; RF-001..006/012; RNF-001..005/009]
6. **Memoria/tiempo/escape.** `AsyncEscapeBytecodeGuardTest` conserva las reglas semánticas ADR-007 y aplica solo los owners/fields/tuples enumerados de ADR-011 al universo cerrado. El guard mantiene además los mutantes async/TL/Unsafe/ClassLoader, bootstrap lambda, `putstatic`, clase QuickMaster extra, cada desviación de `Enum.valueOf`, `finalize()V` y `ACC_NATIVE`. `LevelerShadowMemoryHarness` ejecuta 1/10/60 min, dos JVM y tres repeticiones con ambos add-opens, agent JAR test-only construido antes, hashes/ausencia de paquete, PCM tocado, markers/holds, OS/NMT y auditorías en `SHADOW_READY`/`DONE`. Recalcula `total=accounted+unaccounted`, exige statics all-owner, probes thread/TL/colas/sentinel, schema exacto y caps `M/Q/N/S/P/C/B/L/d/D/G/R/E/J/H_b/U`; reproduce `SNAPSHOT_MIN/MAX` y muta cada field/container/alias/cap. M-004 exige shadow increment y pendientes `<256/64/128 MiB`, permite solo Dense legado y nunca usa `Runtime.freeMemory`; M-005 repite sin Dense. [UC-001/003/006; RF-001/005/006/012; RNF-003..005/008..010]

7. **Viewport puro e integración.** `WaveformViewportTest` ejecuta UI01–UI18 aprobados: anchor 20 pasos, 1,000 round-trips, límites, reset/resize, prioridad/fade, todas interacciones, cola, deletes before/inside/after, crops/trims, pista .1 s, offscreen -500/1500, selección colapsada e inercia 4 combinaciones. `WaveformPeakIndexTest` compara scan/index exactos y los goldens 48/96 kHz de frames 0/255/256/último con ambas polaridades, pista .1 s, cola parcial, presupuesto 60 min, fallback `INDEXING`, cancelación y publicación de generación. Añade pan Alt/Option con clamps y Shortcut+0. `WaveformGestureAdapterTest` construye eventos JavaFX y comprueba `isShortcutDown`, consume y ausencia de mutación; `MainControllerWaveformIntegrationTest` verifica ownership/copy-on-write, que no queda cálculo `/duration` en las rutas enumeradas y ejecuta hit-tests con S>0. [UC-004/005; RF-007..011; RNF-005/006/009]

#### Sincronización normativa completada por el Orchestrator

ADR-009 sustituye los objetos completos siguientes en acceptance/test-plan; no se concatenan con los literales anteriores. `architect-adjudication.006.json` contiene los mismos valores y los pares exactos de reemplazo de mission.md. La sincronización y el preflight están completados; la decisión 011 autoriza al Builder la construcción acotada de extensión/perfil.

```json
{
  "id": "M004-AC-03",
  "criterion": "Extracción estructural y segmentación conservan hops/centros, distancias, percentiles inferiores, MAD/fallback, máximos/ties/separación y retry inicial→P97.5→P99: READY es una partición contigua de 1..64 regiones de [0,F); el fallo devuelve lista vacía, nunca truncada. BoundaryDetector es final y package-private, con un único detect de instancia package-private (FeatureTimeline,long totalFrames,LevelerCalibrationProfile); LevelerAnalysisEngine.analyzeShadow pasa directamente source.frames(). AudioFormat.frames es la autoridad retenida de F. Antes de novelty o reserva/trabajo proporcional al extent se valida F>0, H>0, N_expected=1L+((F-1L)/H)==(long)features.size() y cada centro start+(min(H,F-start)-1L)/2L, con start=(long)i*H y aritmética exacta. Null, overflow o mismatch devuelven INSUFFICIENT_FEATURES/empty sin excepción; solo el resultado de fallo puede reservar tamaño constante. No se infiere, redondea ni rellena F. El guard dirigido prueba una única referencia usada/invocación directa first-party desde el engine, la firma nueva y ausencia de firma antigua/inferTotalFrames; no demuestra aislamiento frente a reflection, MethodHandles, JNI o dependencias maliciosas. QuickMaster y sus dependencias fijadas son código confiable del build. Esta decisión O2 aislada tiene delta cero de schema/memoria. La faceta posterior ADR-011 aporta únicamente sus owners/fields/atoms y nuevos MIN/MAX documentados; roots/aliases restantes, guardO2 y autoridad Dense permanecen intactos.",
  "verification": "FT_MAD0_TIE gana b511; FT_EXACT_64 da 64; FT_PERSISTENT_65 falla sin truncar; FT_RETRY_ORDER da 81→51→31 y acepta 51. El detector acepta ambas extensiones 47999/48000 con H=24000 y el mismo timeline de centros [11999,35999]. Separadamente, el engine completo procesa PCM cero mono a 48 kHz para esos dos F y produce DONE/READY con esos centros y ends exactos iguales a cache.format.frames. Rechaza F<=0, cardinalidad o centro discordantes y F=4294967297,H=1,size=1; acepta F=1,H=1,center=0 y F=Long.MAX_VALUE,H=Long.MAX_VALUE,center=4611686018427387903 como [0,F). El scan sobre target/classes recién compilado cuenta solo referencias usadas por instrucciones invoke first-party: una invokevirtual al descriptor nuevo en analyzeShadow, con source.frames() directo; segundo caller, firma antigua e inferTotalFrames fallan. JVM hija con -Xms16m -Xmx64m -XX:+UseSerialGC, timeout 60 s y ThreadMXBean de asignaciones ejecuta los inválidos de ADR-009: tras 128 warmups, 32 llamadas/caso asignan <=16384 bytes cada una, retornan el fallo esperado y no entran en rawNovelty, observado por JDI test-only. OOM, excepción, timeout, contador no disponible u observador incompleto fallan. Un positivo válido alcanza novelty; reserva proporcional previa al guard y cast truncante que sustituye la comparación long fallan, mientras un range-check y conversión comprobada seguros pasan. Estos oráculos son de regresión funcional finita, no un proof de equivalencia de helpers ni del empaquetador."
}
```

```json
{
  "id": "M004-AC-12",
  "criterion": "Después de suite completa y clean package, jpackage soportado genera la app-image portable Windows final. Se toma fuera de ella un manifiesto completo de todas sus rutas relativas, tipos, tamaños y SHA-256 de archivos, incluidos QuickMaster.exe, app, runtime y recursos generados. Se copia íntegramente ese árbol final a C:/Program Files/QuickMaster y se exige igualdad bidireccional de rutas/tipos/tamaños/hashes, sin extras ni ausentes. target/quickmaster.jar, el JAR de app-image y el instalado tienen el mismo SHA-256. El EXE instalado permanece vivo >=8 s y produce un segmento nuevo de log limpio. jpackage y el build son confiables; se verifica la copia final, sin atestiguar internamente la transformación del empaquetador ni crear un instalador.",
  "verification": "Registrar comandos/exit codes, JDK/jpackage exactos, roots absolutas, manifiestos externos source/destino y diff vacío, hashes de los tres JAR, EXE y runtime, PID/ruta/tiempos, offsets anterior/posterior del log y cierre controlado. Positivo real incluye todos los archivos generados por jpackage. Sobre copias temporales del árbol final, añadir, eliminar o alterar por separado JAR, .class, .properties y archivo sin extensión falla INSTALLED_TREE_MISMATCH; también falla directorio extra/ausente o cambio de tipo. Los controles de paths y hash prueban por separado extras/ausencias y cambio de bytes de igual tamaño. Reenumerar la fuente antes y después de copiar debe reproducir el manifiesto inicial. El lanzamiento usa exclusivamente C:/Program Files/QuickMaster/QuickMaster.exe; el nuevo log debe contener inicio verificable y cero ERROR, excepción no capturada o fallo JavaFX. Backup fuera del destino verificado; sin MSI/setup, commit ni push."
}
```

```json
{
  "id": "TEST-M004-STRUCTURE",
  "type": "unit_property",
  "target": "StructuralFeatureExtractor, BoundaryDetector y SegmentDescriptorBuilder",
  "oracles": [
    "hops/centros exactos; chroma12/spectral8/onset/activity; percentiles inferiores, novelty 2/4/8 s, MAD=0, máximos/ties/separación y partición contigua",
    "FT_MAD0_TIE b511; FT_EXACT_64=64; FT_PERSISTENT_65=TOO_MANY_SEGMENTS; FT_RETRY_ORDER 81→51→31 acepta P97.5/51",
    "FT_EXACT_TAIL_ALIAS: mismo FeatureTimeline H=24000, centros [11999,35999], F=47999/48000 produce READY y ends distintos exactos",
    "ENGINE_EXACT_TAIL_COLLISION: engine completo con PCM cero mono/48 kHz para F=47999 y 48000 produce DONE/READY, H=24000, centros [11999,35999] y end==cache.format.frames",
    "FT_INVALID_EXTENT: null, F<=0, size/centro discordantes y F=4294967297,H=1,size=1 devuelven INSUFFICIENT_FEATURES/empty sin excepción",
    "Extremos del detector, sin PCM gigante ni AudioFormat artificial: F=1,H=1,center=0 y F=Long.MAX_VALUE,H=Long.MAX_VALUE,center=4611686018427387903 producen [0,F)",
    "Guard directo sobre todos los .class first-party de target/classes del build limpio: BoundaryDetector final/package-private, detect único package-private con descriptor nuevo, una referencia usada/invokevirtual en analyzeShadow y argumento directo source.frames(); firma antigua, inferTotalFrames y segundo caller fallan BOUNDARY_DIRECT_WIRING",
    "El scan ignora CP sin uso y no aplica política dinámica al resto del producto/dependencias; no requiere inventario jpackage, CFG anti-adversario, sello TOCTOU ni proof interprocedural. El guardO2 permanece separado; universo y guardsemántico incorporan solo el delta exacto ADR-011 conservando las reglas ADR-007",
    "BOUNDARY_EARLY_REJECTION: JVM hija -Xms16m -Xmx64m -XX:+UseSerialGC, timeout 60 s; ThreadMXBean soportado/habilitado, 128 warmups y 32 llamadas/caso con <=16384 bytes asignados cada una; fallo esperado sin excepción y cero entradas rawNovelty mediante JDI externo test-only",
    "Matriz hija: H=1,size=1,center=0 y F=2,4294967297,4296015872,5368709119,Long.MAX_VALUE; además F=48000,H=24000,size=2,centros=[11999,36000]. No se crea PCM proporcional a F; fixtures/medición/logging quedan fuera del delta",
    "Positivo observador: F=48000,H=24000,centros=[11999,35999] alcanza rawNovelty y READY. Una ejecución sin observador válido no pasa. JVMs positivas mínima/máxima dan READY sin exigir novelty con N=1",
    "Mutantes aislados del detector: comparación (int)N_expected==count que sustituye o elude la validación long, y reserva new byte[(int)ceil(F/H)] previa al guard, también a través de helper privado, fallan status/asignación/heap; mover novelty antes de validar centros falla el contador. Range-check seguido de conversión comprobada después de validar cardinalidad y centros es positivo",
    "Mutar +1/-1 o inferir F en engine falla wiring/colisión; mutaciones musicales de intervalos/igualdad/ties/retry conservan sus fallos. O2 no aporta frame²/PCM duplicado ni delta de memoria. Los nuevos fields y MIN/MAX son únicamente el delta posterior ADR-011; roots/aliases restantes intactos"
  ]
}
```

```json
{
  "id": "TEST-M004-INSTALLED-DELIVERY",
  "type": "windows_portable_delivery",
  "oracles": [
    "Suite completa y clean package pasan; dependencia runtime copiada, jpackage --type app-image con Launcher/icono/módulos del workflow y JDK exacto documentado; sin instalador",
    "Manifiesto externo del árbol final completo generado por jpackage: rutas relativas ordenadas, directorios/tipos y todo archivo regular con tamaño/SHA-256; sin exclusiones de launcher, cfg, runtime, JAR o recursos por extensión",
    "Roots source y C:/Program Files/QuickMaster resueltas y distintas; enumeración sin seguir reparse/symlink/junction; identidad de paths Windows no ambigua; la copia completa reproduce en ambos sentidos paths/tipos/tamaños/hashes, sin extras ni ausentes",
    "Relectura source antes/después de copiar reproduce el manifiesto inicial; source/destino se comparan antes del arranque, con backups y evidencias fuera del destino",
    "SHA-256 target/quickmaster.jar==app-image/app/quickmaster.jar==C:/Program Files/QuickMaster/app/quickmaster.jar; EXE, runtime y recursos coinciden por el mismo manifiesto",
    "Copias temporales de app-image final: add/remove/alter de JAR, .class, .properties y sin extensión, directorio extra/ausente y cambio de tipo fallan INSTALLED_TREE_MISMATCH; controles separados de path-set y bytes de igual tamaño",
    "Lanzar EXE de C:/Program Files/QuickMaster, verificar PID/ruta vivo >=8 s, evidencia de inicio y segmento nuevo del log sin ERROR/excepción no capturada/fallo JavaFX; cerrar solo ese proceso de prueba",
    "jpackage es herramienta confiable: no se atestigua input→transformación; este gate demuestra igualdad app-image final↔copia instalada y JAR build↔app-image↔instalado. Sin MSI/setup, commit ni push"
  ]
}
```


| Suite propuesta | Milestone propietario | Gate |
|---|---|---|
| conformidad loudness, score/group/reference | M-004 análisis y `ReferencePlan` en sombra | sin autoridad sobre audio |
| schedule, compatibilidad, lifecycle y cache | M-003 Dense para los cuatro; aislamiento sombra M-004; switch Sparse/cache/remap M-005 | salida legacy bit a bit hasta el único switch |
| TP, P01..P03, N01..N11, A01..A08 y propiedades | sombra M-004 donde aplique; intervención M-005; reejecución M-007 | rampas/TP/fixtures completos antes del switch |
| RSS/tiempo real + guard/roots/reachability exhaustivos | M-004 sync/bytecode/statics/DTO goldens, delta sombra y sub-F/escapes; M-005 total sin Dense; repetición M-007 | antes de cada switch/entrega instalada |
| UI01..UI18, pan/reset, adapter/controller/index | skeleton M-003; completo M-006; reejecución M-007 | antes de habilitar zoom |
| suite completa y delivery instalado | cada milestone de aplicación M-003..M-006; cierre M-007 | condición de cierre, no fase posterior |

### Poder de regresión y calibración

Los nuevos tests se ejecutan primero contra el commit base y deben observar fallos: el Leveler actual altera secciones únicas/edge por su mediana global (`N01/N06/N08/N09/N10`), materializa ~659.18 MiB de envelope en una hora, usa sample peak y carece de viewport/Shortcut+Scroll. No se “actualiza” una aserción existente para ocultarlo: se conserva la cobertura histórica y se añade un test cuyo oracle distingue explícitamente comportamiento antiguo/nuevo. La suite base de 104 pruebas debe permanecer verde, salvo cambio de expectativa directamente contradictorio que requiere change request del Orchestrator. [UC-001..006; RNF-007/009]

Los thresholds V1 se promocionan solo tras corpus autorizado de >=30 pistas y 6 familias con anotación independiente: 0 falsos positivos en fades/crescendos/breakdowns de aceptación, precisión de pares >=.95 con intervalo reportado, <=.5 dB en todas las negativas, tres runs deterministas y métricas 1/10/60 min. Barridos obligatorios: thresholds H/T/A, penalización transposición, tau `.75/1/1.5`, hop `.25..1 s` y mínimo 2..5 ventanas. Escucha A/B ciega busca pumping/clicks y es complementaria; nunca sustituye tests. [UC-001..003; RF-001..006; RNF-001..005/009]

### Gate de entrega tras cada milestone de aplicación

Cada entrega de M-003..M-006 que cambie Java/FXML/CSS/config/package exige suite completa y `mvn clean package`, dependencias runtime más JAR y `jpackage --type app-image` con main `com.quickmaster.Launcher`, módulos e icono del workflow. Se documenta release 17, JDK local Temurin 25.0.4+7/jpackage 25.0.4 y diferencia con CI Temurin 21. M-007 repite el gate. [UC-006; RF-012; RNF-007/008]

Tras terminar jpackage se manifiesta fuera de su árbol todo el app-image final: rutas relativas ordenadas, directorios/tipos y cada archivo regular con tamaño/SHA-256, incluidos EXE, cfg, app, runtime y recursos. Roots absolutas distintas, paths Windows no ambiguos y enumeración sin seguir reparse/symlink/junction. Se copia ese árbol completo a `C:/Program Files/QuickMaster`, con recuperación/evidencia fuera del destino, y se exige igualdad bidireccional de paths/tipos/tamaños/hashes, sin extras/ausentes. Fuente reenumerada antes/después de copiar debe reproducir el manifiesto inicial; no se rebaselina una diferencia. El JAR además cumple target↔app-image↔instalado por SHA-256. Se confía en jpackage: el gate mide integridad de la copia final y no atestigua internamente input→transformación. [UC-006; RF-012; RNF-008/009]

Los mutantes se aplican únicamente a copias temporales del árbol final: add/remove/alter de JAR, .class, .properties y archivo sin extensión; directorio extra/ausente y cambio de tipo. Fallan `INSTALLED_TREE_MISMATCH`. Controles separados retiran comparación de paths o hash para demostrar extras/ausencias y cambio de bytes de igual tamaño. No se necesita una allowlist de archivos generados. [UC-006; RF-012; RNF-008/009]

Se cierra únicamente la instancia QuickMaster identificada para copiar y después se lanza exclusivamente el EXE instalado, se verifica PID/ruta y vida >=8 s, y se inspecciona solo el nuevo segmento de `%APPDATA%/QuickMaster/quickmaster.log`: debe haber inicio verificable y cero ERROR, excepción no capturada o fallo JavaFX. Se conservan comandos/exit codes, manifiestos/diff vacío, hashes, timestamps/offsets y cierre controlado del proceso de prueba. Log vacío/no nuevo o salida prematura falla. Sin MSI/setup, commit ni push. [UC-006; RF-006/012; RNF-003/007/008]

## Orden de build recomendado

1. **M-003 · Walking skeleton verificable:** tests de regresión; `GainSchedule/PublishedGain` envolviendo con el default `DenseGainSchedule` lineal a Peak/Beat/Punch y también al Leveler legado, con clamps, interpolación, multiplicación y meter raw-bit previos a través de analyze/prepare/process/seek/oversampling/adopción; `WaveformViewport` full-view y transformaciones conectadas a dibujo/playhead/scrub sin cambio visual. Entrega instalada completa.
2. **M-004 · Análisis y autorización:** loudness/features/segmentación exacta, vetos, sketch/body/context, comparator, complete-link/pair y `ReferencePlan` independiente de controles, todo síncrono y exclusivamente en sombra; materializa estados de conformidad sin fingir PASS y ejecuta guard classfile, statics all-owner, goldens DTO, probes de escape y barrido RSS/reachability 1/10/60 con mutantes sub-F. Ninguna salida nueva se asigna a `PublishedGain` ni se adopta, y el audio conserva `DenseGainSchedule` bit a bit. Entrega instalada completa.
3. **M-005 · Intervención y seguridad true-peak:** guard oficial obligatorio, cache/remap, Leveling/Speed, schedule sparse real, rampas, TP/bisección, fixtures P/N/A, propiedades y presupuesto de hora sin Dense; solo `ConformanceState.PASSED` ligado al build y todos los demás gates permiten el único switch atómico al `SparseGainSchedule` nuevo. Entrega instalada completa.
4. **M-006 · Viewport completo:** zoom/prioridad/inercia, navegación/reset, índice de peaks y rebase edit, UI01–UI18 e integración JavaFX. Entrega instalada completa.
5. **M-007 · Calibración y auditoría global:** corpus/escucha complementaria, sensibilidad, performance p95, suite 104+nuevas, app-image/despliegue/hash/EXE/log final.

Este orden aprobado entrega primero dos cortes verticales pequeños y reversibles y evita construir todo el analizador antes de probar los dos bordes reales de integración; corresponde al roadmap M-003..M-007 derivado por el Orchestrator. [UC-001..006; RF-001..012; RNF-001..010]

## Trazabilidad

### Casos de uso

| UC | Recorrido arquitectónico | Evidencia de cierre |
|---|---|---|
| UC-001 | loudness/estructura → protecciones/contexto → comparabilidad → referencia local → schedule | P01/P02, N01/N06/N08, A01..04, goldens de score/grupo |
| UC-002 | vetos previos e irrevocables, ambigüedad a unidad, rampas confinadas, stereo link | N02..05/N09..11, P03, A07, propiedad protegido=0 dB |
| UC-003 | controles → sparse plan → TP → lifecycle por bloques y fallback finito | plan/lifecycle/cache, A05/A08, memoria, seek/oversampling |
| UC-004 | D/S/V puro → Shortcut+Scroll cursor-céntrico → peak index visible | UI01..06/UI08/UI16/UI18, pan/reset e integración JavaFX |
| UC-005 | transformación compartida → hit-test/render → mapas edit/rebase | UI07/UI09..17 e integración de todas las rutas |
| UC-006 | `PublishedGain` snapshot/live + pipeline real + gate instalado | compatibilidad Peak/Beat/Punch, full suite, app-image/hash/EXE/log |

### Requisitos funcionales y no funcionales

| ID | Regla/componente que lo satisface | Tests/gate |
|---|---|---|
| RF-001 | `LoudnessAnalyzer`, guard oficial, features independientes de nivel, segmentación exacta/acotada | G-101 + estados oficiales, FT goldens, P01/P02, A03/A06/A07 |
| RF-002 | `ProtectionClassifier`, `BodyContextGate`, comparator multirrasgo | N01..11, A01/A02/A04 |
| RF-003 | vetos union-first, unidad por duda y rampas fuera de protegido | negativos + frontera exacta |
| RF-004 | complete-link/pair y referencia/raw/confianza intragrupo separada de controles | goldens A/B/C y +5/.438601/2.193006, P01/P02 |
| RF-005 | schedule disperso lento, cache exacta y cancelación | lifecycle/cache/memoria |
| RF-006 | enlace de canales, finitud/longitud/bypass y true peak | P03, A05/A07/A08, propiedades |
| RF-007 | fórmula Shortcut+Scroll con anchor/factor 1.25 | UI01/UI03/UI05/UI18 |
| RF-008 | invariantes/Vmin/reset/pan/index/rebase | UI01..04/UI08..16 |
| RF-009 | `timeAtX/xAtTime` único y `TimelineViewState` atómico | UI02/UI07/UI09..17 |
| RF-010 | prioridad zoom/fade y consumo solo manejado | UI05/UI06/UI18 |
| RF-011 | `ScrollEvent.isShortcutDown()` | adapter JavaFX Win/mac semantics |
| RF-012 | lifecycle snapshot/installed package trazable | integración + delivery gate |
| RNF-001 | identidad no prueba intención; vetos y ambigüedad a unidad | negativos high-match/corpus 0 FP |
| RNF-002 | publicación atómica, C1, <=2 dB/s y protegido exacto | concurrencia/rampas/P03 |
| RNF-003 | double/finitud, estados oficiales fail-closed, normas BS.1770/TP y fallback | G-101, guard EBU/BS.2217, sketch, A05/A07/A08 |
| RNF-004 | degeneración explícita, S<=64, determinismo/tie-breaks | goldens/A06/3 runs |
| RNF-005 | cotas O(F log K), O(S²), O(F), RSS OS/tiempo/cancelación y reachability contabilizado | legacy-only vs sombra real 1/10/60 + whitelist/unaccounted/sub-F; total M-005 |
| RNF-006 | viewport puro, anchor y round-trip <=1 px | UI01..18 |
| RNF-007 | adaptador compatible y suite base no debilitada | 104+nuevas verde |
| RNF-008 | atestación ligada al build y Maven/app-image/deploy/hash/startup/log reproducibles | guard de hashes + delivery M-003..M-006 y cierre M-007 |
| RNF-009 | packages/DTO/profile/ADRs y tests headless | revisión estructural + suite |
| RNF-010 | solo JDK/DSPark/corpus externo autorizado local, sin descarga, red ni telemetría | inspección dependencias/red/logs/corpus no empaquetado |

## Riesgos residuales y respuestas

| Riesgo | Prob./impacto | Respuesta arquitectónica y residual explícito |
|---|---|---|
| F colisionantes no identificables desde features | intrínseca/media | source.frames directo, scan first-party y ambos engine fixtures exactos; no se promete aislamiento ante código arbitrario dentro de la JVM confiable. |
| Observación de asignación/fase requiere JDK compatible | baja/media | APIs locales verificadas; el harness pequeño falla explícitamente si asignación/JDI no son observables, sin skip ni sustitución por heap libre. |
| Umbrales MIR V1 no generalizan | media/alta | perfil versionado, barridos y corpus 30/6; aun aprobado puede haber falsos negativos deliberados. |
| Identidad/contexto no revelan intención artística | intrínseca/alta | gain-scaled ambiguity + unidad por duda; no se promete eliminar falsos positivos universales. |
| DSPark TruePeak no cumple señales EBU | media/alta | gate de conformidad y fallback FIR propio auditable; Builder no decide tolerancias. |
| Input protegido ya sobre 0 dBTP | baja/media | declarar `INFEASIBLE_INPUT_BASELINE`, no limitar ni fingir seguridad; requiere decisión de usuario fuera de este efecto si debe bajar. |
| Cota/hint TP del tile no puede demostrarse para kernel real | media/baja | deshabilitar optimización y medir siempre el stream finito con cola; corrección se conserva, remap puede ser más lento. |
| Hash+TP de remap sigue siendo O(F) | alta/media en una hora | evita FFT/segmentación y debounce/cancelación; medir UX, sin reemplazar key exacta por identidad insegura. |
| Orden Dynamics mutable cambia entrada Leveler | alta/alta | fingerprint del PCM post-upstream y versión/order invalida; la garantía TP es a la salida del Leveler, procesadores posteriores tienen contratos propios. |
| Trackpad/inercia difiere por plataforma | media/media | rechazo pre-mutation, pruebas Windows wheel/trackpad; macOS queda evidencia pendiente hasta runner/dispositivo, no cambio de fórmula. |
| Acoplamiento amplio en `MainController` | media/media | modelo puro + único adapter, walking skeleton y UI01..18; riesgo de ruta olvidada cubierto por integración/búsqueda estática. |
| JDK local 25 frente a CI 21 | alta/baja | release 17, registrar runtime, ejecutar gate local y CI 21 antes de release; no asumir equivalencia silenciosa. |
| Presupuesto p95/RSS depende del host | media/media | protocolo/host registrados y hard fallback por estimación; optimizar sin cambiar semántica si no cumple. |
| Sampling RSS/NMT pierde un pico corto | baja/alta | holds de 3 s, intervalo 100 ms/hueco máximo 250 ms y counters de ownership; run incompleto falla, no interpola. |
| Retención O(F) sub-F evade RSS/counters auto-reportados | media/alta | agente test-only, reachability desde roots/schema cerrados, sizes reales, `unaccounted=0` y mutantes half/quarter/.9/nested; un tipo/campo nuevo exige change control, no auto-whitelist. |
| Finalización/JNI crea roots o efectos fuera de calls visibles | baja/alta | scan exhaustivo de `method_info` rechaza cualquier override `finalize()V` y todo `ACC_NATIVE`; fixtures y controles discriminantes, sin depender de GC ni flags de lanzamiento. |
| Atestación oficial ausente/alterada | alta/alta en el checkout actual | estados separados, manifest/hash/algoritmo/perfil ligados al JAR y guard M-005 fail-closed; G-101 nunca sustituye corpus. |
| Conformidad oficial ITU/EBU pendiente | alta/media | Corpus externo; EBU v5.0 autorizado y adquirido (ref239/seq246, ref247/seq255), con medición `NOT_RUN`; no se empaqueta audio. La falta de conformidad medida bloquea autoridad nueva, no la implementación de sombra determinista. |
| Sketch/D_ctx no observa intención universal | intrínseca/alta | binning/lag/contexto y umbrales tienen goldens independientes; N08 falla al retirar gate. Aun así se prefieren falsos negativos y M-007 exige corpus/escucha. |

Ninguno de estos riesgos deja una elección estructural al Builder: sus respuestas y fallback están fijados. Las únicas incertidumbres son calibración empírica, conformidad de una implementación concreta y evidencia de plataforma.

## ADRs vigentes

| ADR | Estado | Decisión |
|---|---|---|
| [ADR-001](decisions/adr/ADR-001-staged-conservative-leveler-pipeline.md) | PROPUESTO | pipeline por gates, separaciones, sombra sin autoridad y unidad ante duda tras el switch |
| [ADR-002](decisions/adr/ADR-002-deterministic-comparability-and-reference.md) | ACEPTADO | complete-link, ruta estricta de pares y referencia intragrupo |
| [ADR-003](decisions/adr/ADR-003-sparse-gain-schedule-lifecycle-cache.md) | PROPUESTO | Dense lineal bit-exacto y Sparse dB con dispatch por bloque; rollout, lifecycle y cache |
| [ADR-004](decisions/adr/ADR-004-true-peak-boost-safety.md) | ACEPTADO | true peak, margen, reducción de boosts e imposibilidad explícita |
| [ADR-005](decisions/adr/ADR-005-pure-waveform-viewport-and-edits.md) | ACEPTADO | D/S/V puro, gesto, navegación y rebase tras edits |
| [ADR-006](decisions/adr/ADR-006-hierarchical-waveform-peak-index.md) | ACEPTADO | índice min/max jerárquico por generación |
| [ADR-007](decisions/adr/ADR-007-deterministic-shadow-analysis-contract.md) | ACEPTADO | análisis sombra determinista, guard classfile/method-table y memoria RSS+reachability exhaustiva |
| [ADR-008](decisions/adr/ADR-008-explicit-track-extent-for-boundary-detection.md) | SUSTITUIDO_POR ADR-009 | histórico; sus garantías/literales anteriores no son norma vigente |
| [ADR-009](decisions/adr/ADR-009-functional-track-extent-and-delivery-integrity.md) | ACEPTADO | O2 funcional, rechazo temprano observable con memoria acotada y copia íntegra del app-image final |
| [ADR-010](decisions/adr/ADR-010-musical-comparison-context.md) | ACEPTADO | contexto exterior/rate para ocho perturbaciones, Q original compartida y estadísticas en posiciones temporales reales |
| [ADR-011](decisions/adr/ADR-011-official-offline-loudness-conformance-contract.md) | ACEPTADO | núcleo loudness compartido, perfil offline94 lecturas, binding al mismo JAR y delta retenido explícito |
| [ADR-012](decisions/adr/ADR-012-static-baseline-compatibility.md) | ACEPTADO | cierre estático finito, fields/enums/tuples/metadatos exactos y retirada del helper sin alterar Dense |

La base M-003 permanece aceptada: DEF-M003-PF-001..003 siguen cerrados mediante Dense lineal bit-exacto → M-004 sombra sin autoridad → M-005 único switch Sparse dB. DEF-M004-PF-001..010 están cerrados y el doble preflight independiente de iteración 5 fue aprobado; ADR-007 queda aceptado. ADR-008 queda histórico por REPLAN010 y ADR-009 queda `ACEPTADO` tras sincronización contractual, preflight 011 y [decisión del Orchestrator 011](milestones/M-004/orchestrator-decision.011.json), `ACCEPT_WITH_NOTES`. Esta formalización INTENT259 habilita al Builder la base independiente de extensión/perfil de M-004 iteración 2; la implementación no queda aceptada, M-004 sigue abierto y Dense conserva la autoridad. La conformidad oficial permanece pendiente de medición y adjudicación separada.

ADR-010 queda aceptado por [decisión012](milestones/M-004/orchestrator-decision.012.json), tras preflight ciego y reejecución root de contrastes/dry-run. INTENT312 sincroniza exactamente los24 replacements revisados y formaliza estado/índice; la decisión transcribe los tres descriptors JVM y access/opcodes aprobados. Los riesgos axiales, radios nominales y ausencia conservadora siguen declarados; fixtures reales, JDI, memoria/rendimiento y entrega M004 permanecen pendientes. ADR010 por sí mismo no modifica retención ni conformidad; ADR011 y el cierre estático del baseline se adjudican por separado. No hay permiso genérico para nuevos helpers, fields, tipos o dependencias.

ADR-011 queda aceptado por [decisión013](milestones/M-004/orchestrator-decision.013.json), tras preflight013 y reejecución root341/343. INTENT344 sincroniza46 replacements revisados y cuatro aclaraciones de alcance PF013-N01, sin cambiar matemáticas, presupuestos ni autoridad Dense. Las94 lecturas oficiales, clases productivas, mismo-JAR, suite, grafo semántico compuesto/medido y entrega portable siguen pendientes. ADR012 mantiene su preflight separado; los mirrors de LoudnessStandard y DSPark solo pueden deduplicarse por identidad exacta, nunca por orden de merge ni auto-whitelist.

ADR-012 queda aceptado por [decisión014](milestones/M-004/orchestrator-decision.014.json), tras Critic014 APPROVE y reejecución root361 (643/785 comprobaciones, 148 aserciones Java y 451 observaciones legacy). INTENT362 aplica14 replacements revisados, con un rebase AC10 explícito que conserva íntegro ADR011. Fields/enums/ConstantValue, ocho tuples y metadatos se fijan por anexo literal; el helper del switch debe desaparecer reparando la fuente. No se añade retención ni se cambian presupuestos o autoridad Dense; el guard productivo, schema semántico compuesto, probes/grafo, memoria/rendimiento y entrega M004 siguen pendientes. Los 120 rechazos históricos reejecutados no constituyen un PASS.

// Reproducible report snapshot; reads the verified study, never recaptures audio.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const base = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const dir = path.join(base, 'stereo-study-20260928');
const bytes = fs.readFileSync(path.join(dir, 'study.json'));
const study = JSON.parse(bytes), check = JSON.parse(fs.readFileSync(path.join(dir, 'verification.json')));
assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), check.study_sha256);
assert.equal(study.rows.length, 50); assert.equal(check.status, 'passed');
const labels = {
  electronic:'Electrónica', pop:'Pop', rock:'Rock', metal:'Metal', 'hip-hop':'Hip-hop',
  'rnb-soul-funk':'R&B / soul / funk', 'acoustic-folk-country':'Acústico / folk / country',
  'jazz-blues':'Jazz / blues', 'orchestral-cinematic':'Orquestal / sinfónico / cine', latin:'Latina'
};
const mean = xs => xs.reduce((a,b)=>a+b,0)/xs.length;
const median = xs => [...xs].sort((a,b)=>a-b)[Math.floor(xs.length/2)];
const f = (x,n=2) => x.toLocaleString('es-ES',{minimumFractionDigits:n,maximumFractionDigits:n});
const duration = s => `${Math.floor(s/60)}:${String(Math.round(s)%60).padStart(2,'0')}`;
const families = [], songs = [], tonal = [];
for (const [key,family] of Object.entries(labels)) {
  const rows = study.rows.filter(r=>r.job.group===key), group = study.groups[key];
  assert.equal(rows.length,5); assert.ok(rows.every(r=>r.accepted && r.qc.accepted));
  const values = rows.map(r=>r.stereo.pooled_spectral_side_mid_db);
  assert.ok(Math.abs(median(values)-group.side_mid_db.p10_p50_p90[1])<1e-10);
  const g = {family, key, count:5, median:median(values), min:Math.min(...values), max:Math.max(...values)};
  for (const hz of [200,250,300]) g[`low${hz}`] = 100*median(rows.map(r=>r.stereo.cutoffs.find(c=>c.cutoff_hz===hz).fraction_side_below));
  g.rmsRange = median(rows.map(r=>r.tonal.dynamics.rms_400ms_p90_minus_p10_db));
  g.crest = median(rows.map(r=>r.tonal.dynamics.sample_crest_db_peak_channel_over_mean_stereo_power));
  families.push(g);
  const t = {family};
  rows[0].tonal.broad_bands.forEach((b,i)=>{
    t[`band${i}`] = 100*mean(rows.map(r=>r.tonal.broad_bands[i].equal_time_mean_fraction));
  });
  assert.ok(Math.abs(Object.values(t).filter(v=>typeof v==='number').reduce((a,b)=>a+b,0)-100)<1e-7);
  tonal.push(t);
  for (const r of rows.sort((a,b)=>a.job.artist.localeCompare(b.job.artist))) songs.push({
    id:r.job.id, family, key, artist:r.job.artist, title:r.job.titleMatch, url:r.job.url,
    edition:r.job.edition, seconds:r.source_identity.duration, duration:duration(r.source_identity.duration),
    sideMid:r.stereo.pooled_spectral_side_mid_db,
    low250:100*r.stereo.cutoffs.find(c=>c.cutoff_hz===250).fraction_side_below,
    correlation:r.full_file.correlation_uncentered,
    coverage:100*r.qc.coverage_fraction_media_clock,
    rmsRange:r.tonal.dynamics.rms_400ms_p90_minus_p10_db,
    crest:r.tonal.dynamics.sample_crest_db_peak_channel_over_mean_stereo_power
  });
}
const titles = {
  title:'No hay una única relación Mid/Side para todas las canciones',
  summary:`## Resumen de resultados\n\nSe han medido **50 canciones: diez familias con cinco referencias cada una**, con 4 h 8 min 40 s de duración de las fuentes. El estudio cubre la reproducción pública en YouTube, no los masters originales sin pérdida.\n\n- La mediana de Side respecto a Mid va de **−16,18 dB en hip-hop a −2,81 dB en orquestal/cine**: una diferencia de 13,37 dB entre estas selecciones.\n- El género tampoco fija una anchura única: en acústico/folk/country, las cinco canciones abarcan **14,19 dB**. Un perfil debe orientar, no imponer el mismo objetivo a todo.\n- Hay energía Side grave real en las referencias. Por debajo de 250 Hz representa una mediana del **11,9 % al 55,5 % del Side total**, según la familia. No equivale a un porcentaje de anchura ni demuestra que convenga generar Side nuevo en graves.\n\n**Cinco referencias por familia sirven como punto de partida empírico, no como un estudio representativo ni como prueba de una relación «perfecta».**`,
  ratio:`## 1. Cuánto Side hay respecto al Mid\n\nMid = (L + R) / 2; Side = (L − R) / 2. La medida es 10 · log₁₀(energía Side / energía Mid). Un valor más negativo indica menos energía diferencial: −10 dB significa que el Side tiene una décima parte de la energía del Mid. No mide por sí solo calidad, anchura percibida ni seguridad mono.\n\nLa tabla muestra la mediana y los extremos de las cinco canciones de cada familia. Cada canción cuenta una vez; una canción larga no pesa más. Son relaciones calculadas agrupando las potencias espectrales de ventanas Hann de 3 segundos, no promedios de dB instantáneos.`,
  bass:`## 2. Los graves del Side existente no deben eliminarse por defecto\n\nCada porcentaje indica qué parte de **toda la energía del Side** está por debajo del corte indicado. Las columnas son acumulativas y no se suman. Son medianas entre canciones, no objetivos de diseño.\n\nPara Stereo Image, desmarcar **Generate Low Frequencies** debe impedir la generación nueva en graves, no convertir a mono los graves originales. Este estudio no decide si el corte óptimo es 200, 250 o 300 Hz; esa elección sigue necesitando comparar el generador y sus efectos audibles.`,
  tonal:`## 3. Datos adicionales para una futura EQ por estilo\n\nTambién se guardaron espectros L/R/M/S normalizados, variación tonal por ventanas y bloques de 15 segundos, energía por bandas, rango RMS y factor de cresta. La tabla resume la energía estéreo en cinco bandas amplias: primero se normaliza cada ventana válida, se promedia dentro de la canción y después se da el mismo peso a cada canción.\n\nLas bandas tienen anchos distintos: estos porcentajes son **energía acumulada, no altura de una curva EQ ni ganancia recomendada**. Suman aproximadamente 100 % por fila; puede haber diferencias por redondeo. Una futura EQ necesitará usar las curvas finas y su variabilidad, evitar realzar bandas sin señal fiable y conservar la intención del tema.`,
  dynamics:`El rango RMS es P90 − P10 de niveles en ventanas de 400 ms. El factor de cresta compara el pico de muestra máximo de los canales con la potencia estéreo media. Aquí se muestra la mediana entre las cinco canciones. **No son EBU LRA, LUFS ni true peak.**`,
  corpus:`## 4. Las 50 canciones analizadas\n\nEsta es la lista medida, no una selección pendiente. Cada enlace abre la fuente concreta de YouTube. «S/M» usa la misma definición que la tabla de familias; «Side <250 Hz» es la fracción del Side total. La duración corresponde al reproductor de la fuente. Todas pasaron los controles de captura; la cobertura registrada supera el 99,5 %.\n\nLos detalles de edición describen la entrega pública identificada. No certifican que coincida con una edición concreta del master original.`,
  methods:`## 5. Método, controles y límites\n\n**Captura.** Reproducción pública normal, a velocidad 1×, en una aplicación aislada y silenciada: estéreo a 48 kHz, ganancia del reproductor constante y comprobación de volumen estable. AGC, cancelación de eco y supresión de ruido desactivados. Sin Spotify, extensiones de Chrome ni audio guardado en la entrega.\n\n**Cobertura.** Se midió prácticamente toda cada canción; se detuvo aproximadamente 250 ms antes del final para evitar el contenido posterior (20 ms en las dos primeras capturas pop). Puede haber un pequeño margen silencioso de encaminamiento. No es una copia PCM exacta de principio a fin. Hay evidencia del reloj cada segundo en 48 capturas y cada 30 segundos en las otras dos. Los intentos con anuncios o deriva de reloj se excluyeron.\n\n**Cálculo.** Ventanas espectrales Hann de 3 s, salto de 1,5 s; dinámica corta de 400 ms con salto de 200 ms. Bandas FFT disjuntas aproximadamente de tercio de octava, con límites adicionales a 120, 200, 250, 300 Hz, 2 kHz y 10 kHz. No son filtros de medida IEC calibrados. Los bloques de 15 s no identifican estrofas o estribillos.\n\n**Verificación.** 50 archivos de resultados, identificadores únicos y diez grupos de cinco. Se comprobaron hashes, controles de calidad, sumas espectrales, todas las características estéreo/tonales y las agregaciones de nuevo. Hay identidad de fuente en las 50 referencias; 49 conservan además el elemento seleccionado del catálogo y una se verificó directamente en la página oficial.\n\n**Límites.** Selección de conveniencia de canciones conocidas, épocas y ediciones distintas, incluida una grabación orquestal en directo. Cinco canciones no estiman la distribución de todo un género. El códec y el remuestreo pueden alterar especialmente los agudos. Los dBFS absolutos del navegador no permiten comparar el nivel de master entre canciones; los cocientes y espectros normalizados sí cancelan una atenuación común constante. No se midieron LUFS integrados BS.1770, EBU LRA, true peak ni coherencia espectral cuadrática. No hubo evaluación perceptiva controlada.`,
  implications:`## 6. Qué cambia esto en el diseño de QuickMaster\n\n- **Perfiles orientativos:** usar estos datos como referencias iniciales por familia y mostrar su dispersión; no convertir la mediana en una obligación ni en un límite de seguridad.\n- **Adaptación al tema:** combinar el perfil con el análisis de la propia canción y, cuando exista, una referencia elegida. No elevar automáticamente todo tramo estrecho: puede ser una decisión musical.\n- **Graves independientes:** separar el permiso para generar Side nuevo en graves de la preservación del Side original.\n- **Validación pendiente:** número de bandas, profundidad y velocidad de modulación, saturación del delta y corte de graves necesitan pruebas DSP y de escucha. No se han establecido «sweet spots» mediante este corpus.\n\n**Estado del producto:** este informe entrega la investigación y sus datos. No significa que Stereo Image o una nueva EQ espectral estén implementados ni validados en la aplicación.`
};
const source = (ids,defs=[]) => ({label:'Estudio de entregas públicas de YouTube — 50 referencias',
  files:['stereo-study-20260928/study.json','stereo-study-20260928/verification.json'],
  executedAt:study.created_utc, caveats:['Muestra de conveniencia, n=5 por familia; no establece un óptimo perceptivo.'],
  evidenceFlow:[{title:'Captura pública verificada',detail:'Reproducción aislada silenciada; guardados descriptores y procedencia, no audio.'},
    {title:'Integridad',detail:`SHA-256 study.json: ${check.study_sha256}; verificación ${check.verified_utc}.`},
    {title:'Transformación',detail:'build_stereo_report.mjs lee el estudio verificado, calcula medianas por canción y medias espectrales con igual peso.'}],
  metricDefinitions:defs.map(([label,definition])=>({label,definition,componentIds:ids}))});
const snapshot = {surface:'report',title:titles.title,generatedAt:study.created_utc,status:'reviewed',buildStatus:'creating',
  report:{asOf:'2026-09-28'},filters:[],queries:{
    families:{rows:families,source:source(['study-summary','family-ratios','bass-ratios','family-dynamics','implications'],[
      ['S/M (dB)','10 log10(Ps/Pm), potencias agrupadas de ventanas espectrales Hann completas de 3 s; mediana y extremos entre cinco canciones.'],
      ['Side bajo el corte (%)','Mediana entre canciones de 100 × energía Side bajo el corte / energía Side total.'],
      ['Dinámica','Medianas por familia del rango RMS P90−P10 (400 ms) y del factor de cresta de muestra. No LRA ni true peak.']])},
    tonal:{rows:tonal,source:source(['tonal-table'],[['Distribución tonal (%)','Media entre canciones de la media temporal de energía estéreo normalizada por ventana válida; no potencia agrupada ni dB de EQ.']])},
    songs:{rows:songs,source:source(Object.keys(labels).map(k=>`songs-${k}`),[
      ['S/M (dB)','10 log10(Ps/Pm) a partir de potencias espectrales agrupadas de la canción.'],
      ['Duración','Duración registrada por el reproductor público de la fuente; no longitud de captura con márgenes.'],
      ['Cobertura (%)','Fracción del reloj multimedia de la fuente cubierta por la captura aceptada.']])},
    verification:{rows:[{...check,minimumCoverage:Math.min(...songs.map(s=>s.coverage))}],source:source(['methods'],[['Verificación','Recomputación de datos y comprobación de integridad; no certifica el master original ni preferencia auditiva.']])}
  },narrative:titles};
fs.writeFileSync(path.join(dir,'report-snapshot.json'),JSON.stringify(snapshot,null,2)+'\n');
let md=`# ${titles.title}\n\nInforme de investigación de QuickMaster · 28 de septiembre de 2026\n\n${titles.summary}\n\n${titles.ratio}\n\n| Familia | Mediana S/M (dB) | Mínimo–máximo (dB) |\n|---|---:|---:|\n`;
for(const g of families) md+=`| ${g.family} | ${f(g.median)} | ${f(g.min)} a ${f(g.max)} |\n`;
md+=`\n${titles.bass}\n\n| Familia | Side <200 Hz | Side <250 Hz | Side <300 Hz |\n|---|---:|---:|---:|\n`;
for(const g of families) md+=`| ${g.family} | ${f(g.low200,1)} % | ${f(g.low250,1)} % | ${f(g.low300,1)} % |\n`;
md+=`\n${titles.tonal}\n\n| Familia | 0–120 Hz | 120–300 Hz | 300 Hz–2 kHz | 2–10 kHz | 10–24 kHz |\n|---|---:|---:|---:|---:|---:|\n`;
for(const t of tonal) md+=`| ${t.family} | ${[0,1,2,3,4].map(i=>f(t['band'+i],1)+' %').join(' | ')} |\n`;
md+=`\n${titles.dynamics}\n\n| Familia | Rango RMS (dB) | Factor de cresta (dB) |\n|---|---:|---:|\n`;
for(const g of families) md+=`| ${g.family} | ${f(g.rmsRange)} | ${f(g.crest)} |\n`;
md+=`\n${titles.corpus}\n`;
for(const [key,label] of Object.entries(labels)) {
  md+=`\n### ${label}\n\n| Artista — canción / fuente | Duración | S/M (dB) | Side <250 Hz |\n|---|---:|---:|---:|\n`;
  for(const r of songs.filter(r=>r.key===key)) md+=`| [${r.artist} — ${r.title}](${r.url}) | ${r.duration} | ${f(r.sideMid)} | ${f(r.low250,1)} % |\n`;
  md+='\nEdiciones identificadas:\n\n';
  for(const r of songs.filter(r=>r.key===key)) md+=`- **${r.artist}:** ${r.edition}\n`;
}
md+=`\n${titles.methods}\n\n${titles.implications}\n\n## Evidencia reproducible\n\n[Datos íntegros](study.json) · [Verificación](verification.json) · [Resumen técnico](README.md).\n\nSHA-256 del estudio: \`${check.study_sha256}\`.\n`;
fs.writeFileSync(path.join(dir,'INFORME-RESULTADOS.md'),md);
console.log(JSON.stringify({songs:songs.length,families:families.length,minimumCoverage:snapshot.queries.verification.rows[0].minimumCoverage,snapshot:path.join(dir,'report-snapshot.json'),document:path.join(dir,'INFORME-RESULTADOS.md')}));

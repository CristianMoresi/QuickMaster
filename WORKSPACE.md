# Carpeta oficial de QuickMaster

La única carpeta de trabajo es `E:/Code/Projects/JA-DAW/QuickMaster`, en la rama
`main`. El código actual se conserva aquí; los históricos no son copias activas.
Los cambios verificados se versionan y publican desde esta carpeta. Un commit y
push a `main` no crea por sí solo un release: la versión `1.3.3` se publica
mediante su tag y el flujo de empaquetado verificado.

| Ubicación | Uso |
|---|---|
| `src/`, `vendor/`, `libs/`, `pom.xml` | Aplicación y DSPark actuales |
| `docs/`, `tools/` | Documentación, investigación y herramientas vigentes |
| `target/` | Compilación y resultados de pruebas; regenerable |
| `dist/` | Imágenes portables, informes y evidencias locales |
| `test-data/official/` | Señales oficiales de ensayo y autorización local; no publicar |
| `.archive/` | Trabajo anterior y respaldos; no es código activo |

La aplicación de uso está en `C:/Program Files/QuickMaster/QuickMaster.exe`.
Es portable, sin instalador. La imagen de esta entrega está en
`dist/mono-monitor-20260929/image/QuickMaster/`.

## Qué se conservó al ordenar

- El antiguo checkout principal: `.archive/original-checkout-20260929-155157/`.
- Su compilación anterior: `.archive/original-target-20260929-155157/`.
- Los directorios Rapid, Recovery y Helper de pruebas, dentro de `.archive/`.
- El árbol histórico de Universal Orchestrator, dentro de `.archive/`.
  Conserva enlaces de fixtures antiguos con destinos absolutos obsoletos; no
  ejecutar ese árbol como proyecto actual. Sus metadatos quedaron registrados.
- El contenido único de Integration, incluidos informes y compilaciones, en
  este proyecto. Su checkout duplicado se retiró después de comparar hashes.

El historial Git sigue en `.git/`. Los cambios anteriores del checkout principal
tienen además un stash de recuperación
`976cd1ce5294dd944515c777df4910e3b4a20af9`. No aplicarlo sobre el trabajo actual
sin comparar primero. El bundle anterior a la consolidación, los inventarios
SHA-256 y el recibo `consolidation-result.json` están en
`dist/stereo-interaction-20260929/`.

Los respaldos y las señales oficiales están excluidos de Git. Las rutas antiguas
que aparecen en logs son procedencia histórica, no instrucciones para recrear
carpetas hermanas. Desarrollo futuro: solo aquí; consultar `AGENTS.md`,
`DEVELOPMENT_PLAN.md` y `docs/VALIDATION.md`.

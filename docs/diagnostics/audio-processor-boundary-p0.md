# AudioProcessor: frontera de cancelación y bypass (P0)

Revisión de código y bytecode, 2026-09-27. El contrato histórico M004 conserva
su hash original; el contrato del producto activo necesita una revisión explícita
porque la interfaz añade dos métodos default. No se autoriza aceptar hashes
observados automáticamente ni desactivar el escáner.

Cambios revisados:

- `analyzeWhenBypassed()`: devuelve `false`. PeakNormalizer, PeakComp y BeatComp
  optan explícitamente por conservar las medidas que alimentan sus controles.
- `analyze(float[], int, CancellationToken)`: comprueba el token, lanza
  CancellationException si está cancelado y, en caso contrario, delega en el
  método de análisis anterior. El Leveler ya dispone de la implementación
  cooperativa de esa misma firma.
- Los métodos anteriores mantienen su comportamiento. No hay campos, referencias
  retenidas, creación de hilos, ejecutores, callbacks ni trabajo asíncrono dentro
  de esta interfaz. CancellationException es una señal síncrona de aborto, no
  una autorización para ejecutar tareas dentro del núcleo del Leveler.

Bytecode Temurin 25.0.4.7, `--release 17`, revisado mediante `javap -c -p`:
SHA-256 `6be57528d72681f3d96f1ae336c006fe193d810d8913b3fa5eb4a754c1a805f3`.
La primera ejecución completa detectó correctamente la incompatibilidad con la
frontera histórica `5647bbbb…`; ese fallo no se considera una validación aprobada.

La aceptación debe cubrir cancelación previa sin invocar el analizador heredado,
delegación con token nulo/no cancelado, bypass sin procesar y las medidas
explícitas de controles. El hash activo sigue siendo literal y cerrado. Las
pruebas históricas de dependencias, native/finalizer y escapes no se relajan.

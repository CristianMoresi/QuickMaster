# Validación y entrega portable

La suite completa incluye pruebas de audio oficial ITU-R BS.2217-1 y EBU LTS 5.0. Los WAV y la autorización de uso EBU son entradas externas: no se incluyen en Git ni en la aplicación. Los contratos históricos necesarios para las pruebas están en `src/test/resources/leveler/contracts`; sus bytes están fijados por hash. Los resultados nuevos se escriben bajo `target`.

## Build completo

Usar Eclipse Temurin 25.0.4.7 para reproducir el binario auditado de Windows,
incluida la compilación de DSPark Java 0.2.3. El contrato de aceptación comprueba
el hash exacto de esa dependencia. Primero, desde la raíz del proyecto:

```powershell
.\mvnw.cmd -f vendor/dspark-java/pom.xml clean install
Get-FileHash vendor/dspark-java/target/dspark-0.2.3.jar -Algorithm SHA256
```

Hash auditado: `56df525290b09435a8d8cb5e3b56508546ad11b88f4ce2e858bdb1c2f2bb77b9`,
idéntico a `libs/dspark-0.2.3.jar`. Un compilador distinto puede producir otros
bytes aun apuntando a Java 17; no se debe cambiar el pin automáticamente para
sortear una prueba. Fuentes, referencia C++ y comprobaciones independientes en
[migración DSPark](diagnostics/dspark-java-0.2-migration.md) y corrección de
remuestreo 0.2.1 en [auditoría A15](diagnostics/product-audit-20260927.md),
y curvas/port SuperFlux 0.2.2 en [dinámica y clips](diagnostics/dynamics-clips-audit.md),
y Auto Gain 0.2.3 en [autogain EQ](diagnostics/eq-autogain-audit.md).
Los binarios históricos se conservan; el portable debe incluir una sola versión
de DSPark, nunca todos los JAR antiguos de `libs` mediante un comodín.

La carpeta de trabajo local es `E:/Code/Projects/JA-DAW/QuickMaster`, sobre
`main`; no usar los checkouts históricos de `.archive`. Ver `../WORKSPACE.md`.
Los corpus se conservaron en `test-data/official/`, excluidos de Git. Sus
manifiestos mantienen los bytes e identidades originales: los campos de rutas
de adquisición son históricos, mientras el runner usa las rutas explícitas
de abajo. No modificar los manifiestos fijados por hash para cambiar de carpeta.

Después, desde la raíz, usar estas rutas locales (en otra máquina, sustituirlas
por sus corpus autorizados):

```powershell
.\mvnw.cmd clean package `
  '-Dqm.officialLoudness=true' `
  '-Dqm.ituRoot=test-data/official/itu-bs2217-1' `
  '-Dqm.ebuRoot=test-data/official/ebu-lts-v5' `
  '-Dqm.ituManifest=test-data/official/itu-bs2217-1/manifest-bs2217-1.001.json' `
  '-Dqm.ebuManifest=test-data/official/ebu-lts-v5/manifest-ebu-lts-v5.001.json' `
  '-Dqm.ebuAuthorization=test-data/official/ebu-lts-v5/ebu-authorization.json'
```

Las pruebas de aceptación exigen datos oficiales reales y fallan con un mensaje explícito si falta su configuración. No sustituyen mediciones ausentes por un certificado aprobado. La autorización debe corresponder al uso permitido por el proveedor; copiar el archivo de otro usuario no concede esa autorización.

El empaquetado incorpora la evidencia de sonoridad y comprueba el JAR de nuevo. El perfil `QM-OFFICIAL-LOUDNESS-FILE-V1` cubre las lecturas de archivo aplicables; no representa una certificación completa de EBU Mode, emisión en directo o LRA. Las pruebas de true peak se ejecutan por separado dentro de la suite.

## Imagen de Windows

Después de que la suite completa pase:

```powershell
.\mvnw.cmd -q dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/app'
Copy-Item -LiteralPath target/quickmaster.jar -Destination target/app/quickmaster.jar
& "$env:JAVA_HOME/bin/jpackage.exe" --type app-image --name QuickMaster --app-version 1.3.3 `
  --vendor 'Cristian Moresi' --input target/app --main-jar quickmaster.jar `
  --main-class com.quickmaster.Launcher --icon docs/icon.ico `
  --add-modules java.base,java.desktop,java.scripting,java.sql,java.logging,java.xml,java.prefs,java.management,java.naming,jdk.jfr,jdk.unsupported,jdk.zipfs,jdk.localedata `
  --dest dist
```

`dist/QuickMaster` es una aplicación portable completa, con su `.exe`, JAR y runtime. No contiene un instalador. Si ya existe una imagen anterior en `dist`, conservarla con otro nombre antes de generar la nueva.

## Copia y verificación local

Cerrar QuickMaster y ejecutar el siguiente script desde PowerShell con permisos de administrador, usando el SHA-256 del JAR que se acaba de validar:

```powershell
.\tools\Deploy-Portable.ps1 -ImagePath .\dist\QuickMaster -ExpectedJarSha256 '<SHA-256 verificado>'
```

El script comprueba la imagen, copia a una carpeta temporal dentro de `Program Files`, compara todos los archivos y conserva la instalación anterior en una carpeta `QuickMaster-backup-*`. Después verifica el JAR instalado y abre el ejecutable instalado para comprobar el inicio en el log. Cierra únicamente ese proceso de comprobación. Ante un fallo de arranque intenta restaurar la imagen anterior y conserva la imagen fallida. No borra documentos ni audio del usuario.

El resultado queda en `target/deployment-result.json`. `-ValidateOnly` valida la imagen fuente sin modificar la instalación. No se considera entregada una versión hasta obtener `DEPLOYED_AND_VERIFIED`.

La corrección vigente de Stereo Image y sus pruebas de potencia real, medición,
graves y propiedad de la ganancia se registran en
[Stereo power correction](diagnostics/stereo-power-correction.md). Sus pruebas
de salida PCM16 emplean margen de entrada explícito en la señal de prueba;
no debe confundirse con atenuación automática del producto. Con Peak Normalizer
apagado, Stereo no habilita una normalización oculta.

La actualización de controles, caché exacta y preview de una sola pasada se
documenta en [Stereo interaction](diagnostics/stereo-interaction-results.md).
Esa ficha distingue la primera síntesis, las ediciones con caché y la publicación
completa de mediciones; no se deben presentar sus tiempos como intercambiables.

La entrega del 29-09-2026 añade `Listen in mono`, Generation al 25 % inicial y
valores de sliders sin ventanas emergentes, sin cambiar el DSP del low cut.
Suite completa: 921 tests de aplicación y 140 de DSPark; 371 tests adicionales
sobre el JAR instalado. La interfaz real, las transiciones PCM16 con By Now y
Quiet Gold y el arranque del EXE instalado pasan. Registro, hash y límites en
[escucha mono y controles](diagnostics/mono-monitor.md). Son pruebas de la
versión de desarrollo `1.3.3-SNAPSHOT`, no una nueva publicación de release.

## Publicación de un release

1. Actualizar la versión de Maven y el changelog. Ejecutar la suite completa con
   los corpus autorizados y generar el JAR con evidencia `PASSED`.
2. Registrar versión, nombre del JAR de release y SHA-256 en
   `docs/releases/<version>.json`. Este registro identifica el artefacto binario;
   no modifica ni concede la conformidad de sonoridad.
3. Subir el commit a `main` y crear, con la cuenta del autor, un release borrador
   para `v<version>`, adjuntando `QuickMaster-core-<version>.jar`. Crear el tag
   sobre ese mismo commit después de adjuntar el JAR.
4. El workflow de tag descarga ese JAR, verifica su hash, versión Maven y el
   informe ligado a sus clases, y añade dependencias y runtime de cada plataforma.
   No recompila ni reemplaza la evidencia con un build `NOT_RUN`. No requiere
   subir señales oficiales ni autorizaciones a GitHub.
5. Comprobar los tres paquetes, entregar y arrancar el portable de Windows en
   la carpeta de uso y publicar el borrador con las notas del changelog.

El JAR central no es un portable autónomo: los usuarios deben descargar el ZIP
de su plataforma. Los ZIP incluyen el runtime de Java y no usan instalador.

# Validación y entrega portable

La suite completa incluye pruebas de audio oficial ITU-R BS.2217-1 y EBU LTS 5.0. Los WAV y la autorización de uso EBU son entradas externas: no se incluyen en Git ni en la aplicación. Los contratos históricos necesarios para las pruebas están en `src/test/resources/leveler/contracts`; sus bytes están fijados por hash. Los resultados nuevos se escriben bajo `target`.

## Build completo

Usar un JDK compatible con JavaFX 21; la entrega de Windows se ha validado con Eclipse Temurin 25.0.4.7. Sustituir las rutas siguientes por las ubicaciones locales de los corpus, sus manifiestos y la autorización de uso:

```powershell
.\mvnw.cmd clean package `
  '-Dqm.officialLoudness=true' `
  '-Dqm.ituRoot=C:/TestData/itu-bs2217-1' `
  '-Dqm.ebuRoot=C:/TestData/ebu-lts-v5' `
  '-Dqm.ituManifest=C:/TestData/itu-bs2217-1/manifest-bs2217-1.001.json' `
  '-Dqm.ebuManifest=C:/TestData/ebu-lts-v5/manifest-ebu-lts-v5.001.json' `
  '-Dqm.ebuAuthorization=C:/TestData/ebu-lts-v5/ebu-authorization.json'
```

Las pruebas de aceptación exigen datos oficiales reales y fallan con un mensaje explícito si falta su configuración. No sustituyen mediciones ausentes por un certificado aprobado. La autorización debe corresponder al uso permitido por el proveedor; copiar el archivo de otro usuario no concede esa autorización.

El empaquetado incorpora la evidencia de sonoridad y comprueba el JAR de nuevo. El perfil `QM-OFFICIAL-LOUDNESS-FILE-V1` cubre las lecturas de archivo aplicables; no representa una certificación completa de EBU Mode, emisión en directo o LRA. Las pruebas de true peak se ejecutan por separado dentro de la suite.

## Imagen de Windows

Después de que la suite completa pase:

```powershell
.\mvnw.cmd -q dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/app'
Copy-Item -LiteralPath target/quickmaster.jar -Destination target/app/quickmaster.jar
& "$env:JAVA_HOME/bin/jpackage.exe" --type app-image --name QuickMaster --app-version 1.3.1 `
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

<#
.SYNOPSIS
    Compila STOCKWISE y genera el ejecutable de escritorio para Windows con jpackage.

.PARAMETER Tipo
    app-image (por defecto): carpeta portable con STOCKWISE.exe y su propio Java (no requiere instalador).
    exe / msi: instalador de Windows (requiere WiX Toolset 3.x instalado y en el PATH).

.PARAMETER OmitirPruebas
    Compila sin ejecutar las pruebas.

.EXAMPLE
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
    .\scripts\empaquetar.ps1
    .\scripts\empaquetar.ps1 -Tipo exe
#>
param(
    [ValidateSet("app-image", "exe", "msi")]
    [string]$Tipo = "app-image",
    [switch]$OmitirPruebas
)
# "Continue": Maven y jpackage escriben avisos en stderr; los fallos se detectan con $LASTEXITCODE.
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $env:JAVA_HOME) { throw "Defina la variable JAVA_HOME apuntando a un JDK 21 (incluye jpackage)." }
$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
if (-not (Test-Path $jpackage)) { throw "No se encontró jpackage en $jpackage (se requiere un JDK 21 completo)." }

Write-Host "==> Compilando con Maven..." -ForegroundColor Cyan
$mvnArgs = @("-B", "clean", "package")
if ($OmitirPruebas) { $mvnArgs += "-DskipTests" }
& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) { throw "La compilación con Maven falló." }

Write-Host "==> Preparando archivos de la aplicación..." -ForegroundColor Cyan
$dist = Join-Path $root "target\dist"
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force (Join-Path $dist "libs") | Out-Null
Copy-Item (Join-Path $root "target\stockwise.jar") $dist
Copy-Item (Join-Path $root "target\libs\*.jar") (Join-Path $dist "libs")

$dest = Join-Path $root "target\installer"
if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }

# Módulos del JDK incluidos en el runtime embebido (la app funciona sin Java instalado ni Internet).
$modulos = "java.base,java.desktop,java.logging,java.sql,java.xml,java.naming,java.management,java.scripting," +
           "java.prefs,jdk.unsupported,jdk.localedata,jdk.charsets,jdk.crypto.ec,jdk.zipfs"

$jpArgs = @(
    "--type", $Tipo,
    "--name", "STOCKWISE",
    "--app-version", "1.0.0",
    "--vendor", "STOCKWISE",
    "--description", "Analisis de ventas, inventarios y prediccion de demanda (offline)",
    "--input", $dist,
    "--main-jar", "stockwise.jar",
    "--main-class", "com.stockwise.Main",
    "--icon", (Join-Path $root "packaging\stockwise.ico"),
    "--add-modules", $modulos,
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Xmx1024m",
    "--dest", $dest
)
if ($Tipo -ne "app-image") {
    $jpArgs += @("--win-menu", "--win-shortcut", "--win-dir-chooser", "--win-menu-group", "STOCKWISE")
}

Write-Host "==> Ejecutando jpackage ($Tipo)..." -ForegroundColor Cyan
& $jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage falló." }

Write-Host ""
if ($Tipo -eq "app-image") {
    Write-Host "Listo. Ejecutable: $dest\STOCKWISE\STOCKWISE.exe" -ForegroundColor Green
    Write-Host "Puede copiar la carpeta $dest\STOCKWISE a cualquier PC con Windows (no requiere Java ni Internet)."
} else {
    Write-Host "Listo. Instalador generado en: $dest" -ForegroundColor Green
}

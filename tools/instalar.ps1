# Compila SalaTV, la instala en la tele y comprueba que el vigía responde al botón Inicio.
# Si después de instalar el vigía deja de responder, vuelve sola a la versión anterior.
#
#   .\tools\instalar.ps1                 # tele del salón (10.0.0.100)
#   .\tools\instalar.ps1 -Tele 10.0.0.112
#   .\tools\instalar.ps1 -SinCompilar    # instala el último APK ya compilado
param(
    [string]$Tele = "10.0.0.100",
    [switch]$SinCompilar
)

$raiz = Split-Path $PSScriptRoot -Parent
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }
if (-not (Test-Path $adb)) { Write-Host "No encuentro adb. Instala las platform-tools de Android." -ForegroundColor Red; exit 1 }
$dest = "${Tele}:5555"
$apk = Join-Path $raiz "app\build\outputs\apk\release\app-release.apk"

if (-not $SinCompilar) {
    Write-Host "Compilando SalaTV..." -ForegroundColor Cyan
    Push-Location $raiz
    & .\gradlew.bat assembleRelease -q
    $ok = $?
    Pop-Location
    if (-not $ok) { Write-Host "La compilación falló." -ForegroundColor Red; exit 1 }
}
if (-not (Test-Path $apk)) { Write-Host "No está el APK: $apk" -ForegroundColor Red; exit 1 }
$mb = [math]::Round((Get-Item $apk).Length / 1MB, 1)
Write-Host "APK listo ($mb MB)"

Write-Host "Conectando con $dest..." -ForegroundColor Cyan
& $adb connect $dest | Out-Null
$estado = (& $adb -s $dest get-state 2>$null)
if ("$estado".Trim() -ne "device") {
    Write-Host "La tele no responde. Activa Ajustes > Mi Fire TV > Opciones para desarrolladores > Depuración ADB y acepta el aviso en pantalla." -ForegroundColor Red
    exit 1
}

# Copia de la versión instalada, por si hay que volver.
$copias = Join-Path $raiz "copias"
New-Item -ItemType Directory -Force $copias | Out-Null
$anterior = $null
$ruta = "$(& $adb -s $dest shell pm path com.salatv.app)".Trim() -replace '^package:', ''
if ($ruta) {
    $linea = (& $adb -s $dest shell dumpsys package com.salatv.app) | Select-String 'versionName=' | Select-Object -First 1
    $ver = "$linea".Trim() -replace 'versionName=', ''
    $anterior = Join-Path $copias "salatv-$ver.apk"
    & $adb -s $dest pull $ruta $anterior 2>$null | Out-Null
    Write-Host "Guardada la $ver en copias\ por si hay que volver"
}

Write-Host "Instalando..." -ForegroundColor Cyan
& $adb -s $dest install -r $apk
if ($LASTEXITCODE -ne 0) { Write-Host "La instalación falló." -ForegroundColor Red; exit 1 }

$servicios = "$(& $adb -s $dest shell settings get secure enabled_accessibility_services)"
if ($servicios -notmatch 'com.salatv.app') {
    Write-Host "Instalada. El vigía aún no está activo: abre SalaTV en la tele y pulsa Configurar > Configurar todo." -ForegroundColor Yellow
    exit 0
}

Write-Host "Comprobando el vigía..." -ForegroundColor Cyan
# Lo que importa es que Android vuelva a enganchar el vigía tras actualizar. Pulsar Inicio no sirve
# para comprobarlo: si la tele ya está en el inicio de Amazon, no hay cambio de ventana que ver.
$foco = ""
for ($i = 1; $i -le 6; $i++) {
    Start-Sleep -Seconds 4
    $a11y = "$(& $adb -s $dest shell dumpsys accessibility)"
    $foco = "$((& $adb -s $dest shell dumpsys window) | Select-String 'mCurrentFocus' | Select-Object -First 1)"
    if ($a11y -match 'Bound services:\{Service\[label=Vig' -or $foco -match 'com.salatv.app') {
        Write-Host "SalaTV instalada y el vigía está activo." -ForegroundColor Green
        exit 0
    }
}

Write-Host "El vigía no respondió ($foco)." -ForegroundColor Red
if ($anterior) {
    Write-Host "Volviendo a la versión anterior..." -ForegroundColor Yellow
    & $adb -s $dest install -r -d $anterior
    Write-Host "Vuelta a la versión anterior. Mira el Registro de problemas de SalaTV para ver qué pasó." -ForegroundColor Yellow
}
exit 1

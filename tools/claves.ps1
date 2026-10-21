# Conecta Crunchyroll en SalaTV desde el PC, sin escribir con el mando.
# La contraseña viaja una sola vez: SalaTV la usa para iniciar sesión, guarda solo el token
# de sesión y borra el archivo al momento.
#
#   .\tools\claves.ps1
#   .\tools\claves.ps1 -Tele 10.0.0.112 -Pais MX
param(
    [string]$Tele = "10.0.0.100",
    [string]$Pais = ""
)

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }
$dest = "${Tele}:5555"
& $adb connect $dest | Out-Null
if ("$(& $adb -s $dest get-state 2>$null)".Trim() -ne "device") {
    Write-Host "La tele no responde. ¿Está encendida y con la depuración ADB activa?" -ForegroundColor Red
    exit 1
}

$correo = Read-Host "Correo de Crunchyroll"
$seguro = Read-Host "Contraseña de Crunchyroll" -AsSecureString
$clave = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($seguro))

$tmp = Join-Path $env:TEMP "salatv-claves.txt"
$lineas = @("crunchyroll_email=$correo", "crunchyroll_password=$clave")
if ($Pais) { $lineas += "region=$Pais" }
[IO.File]::WriteAllLines($tmp, $lineas, (New-Object Text.UTF8Encoding $false))
& $adb -s $dest push $tmp /sdcard/Android/data/com.salatv.app/files/claves.txt 2>$null | Out-Null
Remove-Item $tmp -Force
$clave = $null

# SalaTV lee el archivo al ponerse delante.
& $adb -s $dest shell monkey -p com.salatv.app -c android.intent.category.LEANBACK_LAUNCHER 1 2>$null | Out-Null
Start-Sleep -Seconds 6
$queda = "$(& $adb -s $dest shell ls /sdcard/Android/data/com.salatv.app/files/claves.txt 2>$null)"
if ($queda -match 'claves.txt' -and $queda -notmatch 'No such') {
    & $adb -s $dest shell rm /sdcard/Android/data/com.salatv.app/files/claves.txt
    Write-Host "SalaTV no llegó a leer el archivo; lo he borrado. Abre SalaTV y vuelve a intentarlo." -ForegroundColor Yellow
} else {
    Write-Host "Enviado. Mira la tele: SalaTV dirá si Crunchyroll aceptó el inicio de sesión." -ForegroundColor Green
}

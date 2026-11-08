@echo off
rem Installe Hex sur le Nothing Phone (1) branché en USB (Windows).
rem Usage : install.bat chemin\vers\hex.apk
setlocal EnableDelayedExpansion
cd /d "%~dp0"
set APK=%~1
if "%APK%"=="" set APK=hex.apk
set PKG=com.rod.hex

where adb >nul 2>nul || (echo adb introuvable : installe les Android platform-tools, voir le guide. & exit /b 1)
if not exist "%APK%" (echo APK introuvable : %APK% & exit /b 1)
if not exist hex-config.txt (echo Copie hex-config.exemple.txt en hex-config.txt et remplis-le. & exit /b 1)

for /f "usebackq tokens=1,* delims==" %%a in ("hex-config.txt") do (
  set "line=%%a"
  if not "!line:~0,1!"=="#" set "%%a=%%b"
)

echo 1/6  Telephone detecte ?
adb wait-for-device
adb devices

echo 2/6  Installation de Hex
adb install -r "%APK%"

echo 3/6  Hex devient proprietaire de l'appareil (mode kiosque)
adb shell dpm set-device-owner %PKG%/.HexAdminReceiver

echo 4/6  Permissions Glyph
adb shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS
adb shell settings put global nt_glyph_interface_debug_enable 1

echo 5/6  Mot d'activation "Hey Hex"
set PPN=
for %%f in (*.ppn) do if not defined PPN set PPN=%%f
if defined PPN (
  adb push "!PPN!" /data/local/tmp/hey-hex.ppn
  adb shell run-as %PKG% mkdir -p files
  adb shell run-as %PKG% cp /data/local/tmp/hey-hex.ppn files/hey-hex.ppn
  adb shell rm /data/local/tmp/hey-hex.ppn
) else (
  echo    Pas de fichier .ppn : Hex repondra a "Jarvis" en attendant.
)

echo 6/6  Envoi de la configuration
set ARGS=
if defined CLAUDE set ARGS=!ARGS! --es claude !CLAUDE!
if defined PICOVOICE set ARGS=!ARGS! --es picovoice !PICOVOICE!
if defined TTS set ARGS=!ARGS! --es tts !TTS!
if defined PIN set ARGS=!ARGS! --es pin !PIN!
if defined MODEL set ARGS=!ARGS! --es model !MODEL!
if defined BUDGET set ARGS=!ARGS! --es budget !BUDGET!
adb shell am start -n %PKG%/.SetupActivity !ARGS!

echo.
echo Termine. Hex va se presenter dans quelques secondes.

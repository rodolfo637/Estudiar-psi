#!/usr/bin/env bash
# Compila MisGastos.apk a partir de ../gastos.html.
# Requisitos (Ubuntu/Debian): apt install aapt apksigner zipalign android-sdk-platform-23 dalvik-exchange default-jdk
set -euo pipefail
cd "$(dirname "$0")"
JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
KS=misgastos.keystore
rm -rf out && mkdir -p out/classes out/assets
cp ../gastos.html out/assets/gastos.html

# La firma identifica a la app: con otra clave, Android no deja actualizar sobre la versión instalada.
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass misgastos -keypass misgastos -alias misgastos \
    -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Mis Gastos"
fi

aapt package -f -M AndroidManifest.xml -S res -A out/assets -I "$JAR" -F out/unsigned.apk
javac -nowarn -source 8 -target 8 -bootclasspath "$JAR" -d out/classes src/ar/misgastos/app/MainActivity.java
dalvik-exchange --dex --min-sdk-version=23 --output=out/classes.dex out/classes
(cd out && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 out/unsigned.apk out/aligned.apk
apksigner sign --ks "$KS" --ks-pass pass:misgastos --key-pass pass:misgastos --ks-key-alias misgastos \
  --out ../MisGastos.apk out/aligned.apk
rm -f ../MisGastos.apk.idsig
apksigner verify --verbose ../MisGastos.apk | head -4
echo "OK -> MisGastos.apk"

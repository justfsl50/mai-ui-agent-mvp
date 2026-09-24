#!/bin/bash
set -euo pipefail
export JAVA_HOME=$HOME/sdk/jdk-17.0.20.1+1; export PATH=$JAVA_HOME/bin:$PATH
BT=$HOME/sdk/android/build-tools/34.0.0; AJ=$HOME/sdk/android/platforms/android-34/android.jar
cd $HOME/app; rm -rf build; mkdir -p build/res build/classes build/dex
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I $AJ --manifest AndroidManifest.xml --java build/gen build/res.zip --auto-add-overlay
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath $AJ:$BT/core-lambda-stubs.jar -classpath $AJ -d build/classes $(find src build/gen -name '*.java')
$BT/d8 --release --min-api 30 --lib $AJ --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
$BT/zipalign -f -p 4 build/unsigned.apk build/aligned.apk
[ -f $HOME/app/release.jks ] || keytool -genkeypair -keystore $HOME/app/release.jks -storepass maiuimvp -keypass maiuimvp -alias maiui -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=MAI-UI MVP" >/dev/null 2>&1
$BT/apksigner sign --ks $HOME/app/release.jks --ks-pass pass:maiuimvp --key-pass pass:maiuimvp --out build/MAI-UI-Agent.apk build/aligned.apk
$BT/apksigner verify --verbose build/MAI-UI-Agent.apk | head -5
ls -la build/MAI-UI-Agent.apk

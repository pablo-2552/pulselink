#!/usr/bin/env bash
# Pulse Link - tek komutla imzalı APK üretir ve indirme sayfasını açar.
# Kullanım (Codespaces terminalinde, proje klasöründe):   bash build_release.sh
set -e
cd "$(dirname "$0")"

echo "==> 1/4 Android SDK aranıyor"
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  for d in /home/codespace/android-sdk "$HOME/android-sdk" "$HOME/Android/Sdk" /usr/local/lib/android/sdk; do
    if [ -d "$d" ]; then export ANDROID_HOME="$d"; break; fi
  done
fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "HATA: Android SDK bulunamadı. ANDROID_HOME ayarla veya local.properties içine sdk.dir yaz."; exit 1
fi
echo "sdk.dir=$SDK" > local.properties
echo "    SDK: $SDK"

echo "==> 2/4 Gradle aranıyor"
if [ -x ./gradle-8.4/bin/gradle ]; then
  GRADLE=./gradle-8.4/bin/gradle
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
else
  echo "    Gradle 8.4 indiriliyor..."
  curl -L -o /tmp/gradle-8.4-bin.zip https://services.gradle.org/distributions/gradle-8.4-bin.zip
  unzip -q -o /tmp/gradle-8.4-bin.zip -d .
  GRADLE=./gradle-8.4/bin/gradle
fi
echo "    Gradle: $GRADLE"

echo "==> 3/4 APK derleniyor (ilk seferde birkaç dakika sürebilir)"
"$GRADLE" assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then echo "HATA: APK oluşmadı"; exit 1; fi

echo "==> 4/4 İndirme klasörü hazırlanıyor"
mkdir -p ~/indir
cp "$APK" ~/indir/PulseLink.apk
ls -lh ~/indir/PulseLink.apk
echo
echo "APK hazır: ~/indir/PulseLink.apk"
echo "Şimdi: PORTS sekmesinden 8000 portunu aç (gerekirse Public yap), PulseLink.apk dosyasına dokunup indir."
echo "(Sunucuyu durdurmak için Ctrl+C)"
cd ~/indir && python3 -m http.server 8000

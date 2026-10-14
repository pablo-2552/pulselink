# Pulse Link

İki telefon arasında düşük gecikmeli ekran + ses akışı (WebRTC). Yayın cihazı (ör. Samsung RTL S26) ekranını ve sesini
gönderir, izleyici cihaz (ör. Galaxy A8) görüntüyü yüzen bir pencerede oynatır.

## Derleme (Codespaces)
```bash
bash build_release.sh
```
Betik SDK'yı ve Gradle'ı bulur, imzalı APK'yı üretir ve `~/indir/PulseLink.apk` olarak 8000 portundan sunar.
(Alternatif: GitHub > Actions > "APK derle" > Run workflow.)

## Kullanım
1. Render sunucusunu uyandır: https://wildrift-overlay.onrender.com/
2. Yayın cihazında: "BU CİHAZDAN YAYIN AÇ" (ekran + ses izni ver).
3. İzleyici cihazda: "GÖRÜNTÜYÜ BAŞLAT". Aynı oda kodunu kullan.
4. Yüzen ⚙ menüsünden mod, çözünürlük, FPS ve ses ayarlanır.

## Modlar
- **MANUEL**: Seçilen çözünürlük ve FPS sabit kalır, sistem kendiliğinden düşürmez/yükseltmez.
- **OTOMATİK**: Ağa göre çözünürlük/FPS/bitrate otomatik ayarlanır (tavan: izleyici cihazın çözebildiği en yüksek profil, 60 FPS).
- 30 ve 60 FPS temiz akar. 45 FPS 60 Hz ekranda düzensiz kare aralığı üretir.

## HUD (siyah şeritteki yazı)
`60 FPS • 85 ms | 720p [M] | P2P 5.9M J12 D4`
- ms: ağ gidiş-dönüş süresi, `720p`: gerçekte gelen çözünürlük, `[M]/[O]`: manuel/otomatik
- `P2P` doğrudan bağlantı, `RELAY` aracı sunucu üzerinden (gecikme artar, turuncu görünür)
- `5.9M`: gelen bitrate (Mbps), `J`: alıcı tampon gecikmesi (ms), `D`: kare başına çözme süresi (ms)

## Ayar noktaları
`StreamProfile.kt`: profil başına bitrate tavanı (`baseBitrate60`), manuel alt sınır oranı, ölçek oranları.
`WebRtcManager.kt`: alıcı tampon sınırı (`FIELD_TRIALS`, varsayılan en fazla 20 ms).

## Mimari
- Video: MediaProjection -> 1080x2340 VirtualDisplay (19.5:9 sabit) -> H.264 donanım kodlayıcı; profil kodlayıcıda ölçeklenir.
- Ses: AudioPlaybackCapture -> IMA-ADPCM (48 kHz stereo, ~390 kbps) -> güvenilmez DataChannel -> AudioTrack (düşük gecikme).
- Sinyalleşme: `signaling_server.js` (Node + ws), Render üzerinde.

## Not
APK imzası `pulselink.keystore` ile yapılır (kişisel kullanım için repoya dahil). Uygulama kimliği `com.pulselink.stream`,
eski "WR LowLatency Overlay" ile yan yana kurulabilir.

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
- FPS sınırlayıcı kaynağın tempo salınımına uyum sağlar; oyun hedef FPS'e yakınken hiçbir kare atılmaz.
- Ayarlar (mod, çözünürlük, FPS, ses, gösterge, akıcılık, kodlayıcı, bant) hatırlanır.

## HUD (sağdaki siyah şeritte, en üstte sabit)
`30 FPS • 75 ms | 1080p [M] | P2P 6.8M J0 D12 G38 L2 N5 K1 F0`
- `ms`: ağ gidiş-dönüş süresi, `1080p`: gerçekte gelen çözünürlük, `[M]/[O]`: manuel/otomatik
- `P2P` doğrudan bağlantı, `RELAY` aracı sunucu üzerinden (gecikme artar, turuncu görünür)
- `6.8M`: gelen bitrate (Mbps), `J`: alıcı tampon gecikmesi (ms), `D`: kare başına çözme süresi (ms)
- `G`: ekranda gösterilen kareler arası en uzun boşluk (ms). 30 FPS'te ~33-45, 60 FPS'te ~17-25 normaldir. Çok üstüne çıkıyorsa takılma var.
- Sadece sorun varken görünenler: `L` son saniyedeki kayıp paket, `N` yeniden gönderim isteği, `K` anahtar kare isteği (toplam), `F` donma sayısı (toplam)
- Menüden "GÖSTERGE" düğmesiyle açılıp kapanır.

## Titreme / takılma teşhisi
1. G yüksek ama L, N, K, F yok -> kareler düzensiz geliyor: "AKICILIK: 1 KARE" dene (biraz gecikme artar).
2. L / N / K / F artıyor -> ağ paket kaybediyor: "BANT SINIRİ"nı %75 / %50 yap veya çözünürlüğü düşür.
3. Hiçbiri değişmiyor ama görüntü hâlâ geri-ileri gidiyorsa -> "KODLAYICI: VARSAYILAN" ile "HIZLI"yı karşılaştır.
   (HIZLI: B-kare/ileri tahmin kapalı. Değiştirince yayın 1-3 sn kesilip yeniden bağlanır.)

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

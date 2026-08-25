# Babam Radyo

Yaşlı bir kullanıcının kolayca kullanabilmesi için tasarlanmış, **sıfır dış servis
bağımlılığı** olan Android müzik ve radyo uygulaması.

- Büyük yazı tipleri, geniş dokunma alanları, tamamen Türkçe arayüz
- API anahtarı yok, hesap yok, giriş yok, reklam yok, takip yok
- Arka planda çalar; bildirim ve kilit ekranından kontrol edilir

## Ne yapar?

| Sekme | İşlev |
|---|---|
| **Radyo** | Türkiye'nin en çok dinlenen internet radyolarını listeler, isimle arama yapılabilir. Favoriler en üstte ayrı bölümde durur. |
| **Müzik Ara** | Önce **YouTube**, altında archive.org arşivi. YouTube sonuçları doğrudan çalınır ve mp3 olarak indirilir; arşiv sonuçlarına dokununca içindeki parçalar listelenir. |
| **İndirilenler** | İndirme düğmesine basılan parçalar buraya iner ve **internetsiz** dinlenir. |
| **FM Radyo** | Üst çubuktaki düğme, telefonda kurulu dahili FM radyo uygulamasını açar. |
| **Uyku zamanlayıcı** | 15–90 dakika sonra müziği durdurur. |

### Oynatma

- Arka planda çalar; bildirim ve kilit ekranından duraklat/önceki/sonraki
- İstasyon logosu ve albüm kapağı listede, oynatıcıda ve bildirimde
- **Yayın koparsa kendiliğinden geri bağlanır**: artan bekleme süreleriyle
  yeniden dener, ayrıca ağ geri gelir gelmez bağlantı kurar
- Son dinlenen istasyon/şarkı hatırlanır, uygulama açılışında hazır bekler
- Ses odağı: gelen aramada duraklar, kulaklık çıkınca durur

### İndirmeler

İndirme ayrı bir ön plan servisinde yürür: bildirimde ilerleme çubuğu ve
iptal düğmesi görünür, uygulamadan çıkılsa bile indirme sürer.

## FM radyo hakkında

Gerçek FM radyo bir uygulamayla "yazılamaz": telefonun içinde **FM anten çipi**
bulunmalı ve **kablolu kulaklık** anten görevi görmelidir. Bu yüzden uygulama,
cihazda kurulu dahili FM uygulamasını (Oppo/ColorOS'ta genelde
`com.android.fmradio`) bulup açar. Çip yoksa uygulama bunu açıkça söyler ve
kullanıcıyı internet radyosu sekmesine yönlendirir.

## YouTube araması

Arama sonuçlarının ilk bölümü YouTube'dan gelir. Bunun için kendi backend'imiz
kullanılır: [babam-download-api](https://github.com/ErcouldnT/babam-download-api),
Raspberry Pi 5 üzerinde çalışır. YouTube Data API veya herhangi bir anahtarlı
servis kullanılmaz.

Sunucu adresi ve gizli anahtar `api.properties` dosyasından derleme sırasında
`BuildConfig`'e gömülür. Bu dosya **`.gitignore` içindedir** — depo herkese
açık olduğu için anahtar depoya girmez. Kurulum için `api.properties.example`
dosyasını kopyalayıp doldur.

Anahtar APK içinde XOR ile karıştırılmış halde durur. Bu, `strings` ile bakan
birini engeller; **kararlı bir tersine mühendisi engellemez** — APK'ya sahip
olan anahtarı çıkarabilir. Asıl koruma, anahtarın sızması hâlinde sunucuda
değiştirilebilmesidir.

Backend kapalıysa YouTube bölümü sessizce atlanır: arşiv sonuçları, radyo ve
indirilenler normal çalışmaya devam eder.

## Kaynaklar (hepsi açık ve anahtarsız)

- **[radio-browser.info](https://www.radio-browser.info/)** — topluluk tarafından
  yürütülen, ücretsiz ve açık kaynaklı canlı radyo dizini. API anahtarı istemez.
- **[archive.org](https://archive.org/)** — Internet Archive'ın açık ses arşivi.
  `advancedsearch.php` ile arama, `metadata/` ile parça listesi,
  `download/` ile doğrudan mp3. Kamu malı ve Creative Commons içerik.

Ağ katmanı yalnızca Android'in kendi `HttpURLConnection` ve `org.json`
sınıflarını kullanır — üçüncü parti bir HTTP/JSON kütüphanesi yoktur.

## Neden YouTube yok?

İstenmişti, denendi ve ölçüldü:

1. **Arama sayfası kazıma (scraping)** — `youtube.com/results` isteği bot
   kontrolü nedeniyle `HTTP 302` döndürüyor; sonuç sayfası hiç gelmiyor.
2. **Resmi RSS akışları** — `youtube.com/feeds/videos.xml` çalışıyor (`HTTP 200`)
   ancak yalnızca **belirli bir kanalın** son video **başlıklarını** veriyor.
   Arama yok, ses akışı yok, indirilebilir dosya yok.
3. **Ses akışı çıkarma** — imza/`n` parametresi şifre çözümü gerektirir; YouTube
   bunu sık sık değiştirdiği için sürekli güncellenen bir çıkarıcı kütüphane
   olmadan aylarca çalışan bir uygulama yazmak mümkün değil. Ayrıca YouTube
   Hizmet Şartları'na aykırı.

Bu yüzden aynı kullanıcı deneyimi (ara → çal → indir → internetsiz dinle)
yasal ve kalıcı olarak çalışan kaynaklarla kuruldu.

## Test durumu

Android 14 (API 34) emülatöründe uçtan uca doğrulandı:

- Türk radyo listesi yükleniyor, seçilen istasyon gerçekten çalıyor
  (`PlaybackState=PLAYING`, tamponlama sürüyor)
- Arama gerçek sonuç veriyor, albüm içi parçalar süreleriyle listeleniyor
- İndirme diske gerçek mp3 yazıyor (9,4 MB test dosyası)
- **Uçak modunda** indirilen şarkı sorunsuz çalıyor
- Sekmeler arasında geçerken çalma kesilmiyor, çökme kaydı yok
- Medya bildirimi postalanıyor (`category=transport`), servis ön planda
  (`isForeground=true, types=mediaPlayback`)
- **Kilit ekranında** oynatma kartı görünüyor: başlık, duraklat, önceki/sonraki,
  çıkış cihazı seçici — ekran kilitliyken çalma sürüyor
- Ekran kilidi **kapalıyken** de sorunsuz: uygulama arka planda ve ekran kapalı
  iken çalma kesintisiz sürdü, servis ön planda kaldı
- Favoriler kalıcı, en üstte ayrı bölümde listeleniyor
- **90 saniyelik ağ kesintisinden** sonra yayın kendiliğinden geri geldi
- Son dinlenen istasyon, emülatör yeniden başlatıldıktan sonra bile geri yüklendi
- Uyku zamanlayıcı kalan süreyi gösteriyor ve iptal edilebiliyor
- İndirme bildirimi ilerleme çubuğu ve iptal düğmesiyle çalışıyor; 26 MB'lık
  dosya indi, bitince servis kendini kapattı

Babanın cihazı Android 10 (API 29); test edilen API 34 daha katı kurallara
sahip (ön plan servis tipleri, bildirim izni), dolayısıyla API 29'da da
çalışması bekleniyor — ancak gerçek cihazda ayrıca denenmedi.

## Kurulum (telefona yükleme)

1. `app-release.apk` dosyasını telefona kopyala.
2. Dosya yöneticisinden dosyaya dokun.
3. Oppo "bilinmeyen kaynak" uyarısı verirse izin ver.
4. Kur ve aç.

Gereken en düşük sürüm: **Android 8.0 (API 26)**. Babanın telefonu Android 10,
uyumlu.

## Geliştirme

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew :app:assembleRelease
# çıktı: app/build/outputs/apk/release/app-release.apk
```

### İmzalama

İmzalama anahtarı ve parolaları **depoda tutulmaz**. Kök dizinde
`keystore.properties` dosyası ve işaret ettiği `.keystore` dosyası varsa
release derlemesi imzalanır; yoksa imzasız üretilir.

```properties
# keystore.properties  (git tarafından yok sayılır)
storeFile=babam-release.keystore
storePassword=...
keyAlias=babam
keyPassword=...
```

Yeni bir anahtar üretmek için:

```bash
keytool -genkeypair -keystore app/babam-release.keystore \
  -alias babam -keyalg RSA -keysize 2048 -validity 10000
```

Not: Telefondaki uygulamayı **güncelleyebilmek** için aynı anahtarla imzalamak
gerekir. Anahtar kaybolursa, güncelleme yüklenmeden önce eski sürümü kaldırman
gerekir. Anahtarı yedekle.

## İzinler

| İzin | Neden |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Radyo yayını ve arama |
| `WAKE_LOCK`, `FOREGROUND_SERVICE*` | Ekran kapalıyken çalmayı sürdürmek |
| `POST_NOTIFICATIONS` | Oynatma bildirimi (Android 13+) |

Depolama izni **istenmez**: indirilen dosyalar uygulamaya özel klasöre yazılır.

# Değişiklikler

Sürümler [SemVer](https://semver.org/lang/tr/) kuralına uyar; `versionCode` her sürümde bir artar. Her sürümün imzalı APK'sı GitHub'daki sürüm sayfasına eklenir.

## 0.1.0

İlk sürüm: masaüstü Inky 0.15.2 Android'de, telefon, tablet ve DeX için. Özellik listesinin taşınmayan ya da farklı yapılan maddeleri nedenleriyle `docs/TASINMAYANLAR.md`'de, nasıl kullanıldığı `docs/KULLANIM.md`'de.

### Yazmak
- Kitin kod düzenleyicisinde ink: Ace'in ink kurallarının birebir taşınmışıyla renklendirme, satır numaraları, uzun satırların kaydırılması, eş aralıklı ya da orantılı yazı, yakınlaştırma.
- Bul ve değiştir (büyük/küçük harf, tam sözcük, düzenli ifade), masaüstündeki otomatik tamamlama (yönlendirme hedefleri, değişkenler, projenin sözcükleri, ink'in anahtar sözcükleri), Ctrl+Space.
- Tanıma git: Ctrl+ ya da Alt+tıklama, F12, dokunmatikte uzun basınca açılan menü; `INCLUDE` yolu dosyayı açar.
- Hatalar, uyarılar ve TODO notları düzenleyicinin kenarında ve satırında, iletileriyle.
- Telefonda ekran klavyesinin üstünde ink'in işaretlerini sunan şerit (`->`, `*`, `{ }`, `<>`, sekme, geri al …).
- Otomatik kaydetme (varsayılan açık; kapalıyken masaüstündeki gibi kaydet ve kapatırken sor) ve her zaman taslaklar: kaydedilmemiş metin uygulama ya da telefon kapansa da geri gelir.

### Oynatmak
- Hikâye yazdıkça derlenir ve yapılan seçimlerle titremeden yeniden oynatılır; derlenemeyen metinde son iyi hikâye gösterilir.
- Bir seçim geri al, hikâyeyi baştan başlat, etiketler (gizlenebilir), komut önekli satırlar, hikâyenin sonu.
- Bir paragraftan onu üreten ink satırına gitmek (dokunarak; fareyle Ctrl ya da Alt ile).
- Çalışma hataları ve uyarıları satırlarına bağlantı olarak; sonu gelmeyen döngüler ve kendini çağıran işlevler uygulamayı kilitlemeden durdurulur.
- İzleme ifadeleri: her turdan sonra değerlendirilir, işlev çağrıları da çalışır.
- Yeni metnin yavaşça belirmesi (kapatılabilir; "Animasyonları kaldır"a uyar).

### Proje ve dosyalar
- Proje bir klasör ve ana ink dosyasıdır: Android'in depolama erişimiyle açılan klasörler, uygulamanın içinde tutulan projeler, yeni projelerin oluşturulduğu bir projeler klasörü.
- Klasör aç (birden çok aday varsa ana dosya sorulur), dosya aç (klasörüne erişim sonradan verilebilir), dosya yöneticilerinden `.ink` açma, son projeler.
- `INCLUDE` ile çok dosyalı projeler: dosyalar masaüstündeki gibi gruplanır, kullanılmayan dosyalar ayrı, var olmayan `INCLUDE` dosyaları oluşturulabilir.
- Yeni ink dosyası ekle (ana dosyaya `INCLUDE` satırıyla); masaüstünde olmayan yeniden adlandırma (`INCLUDE` satırları da değişir) ve silme.
- Dışarıda değişen dosyalar pencere öne gelince ve F5 ile alınır; kaydedilmemiş değişikliği olan dosyada "benimkini koru / ötekini yükle" sorulur.
- Proje ayar dosyası: özel parçacıklar (alt menüler, tuşlar) ve komut öneki; hataları bir şeritte.
- Uygulama yeniden açıldığında son proje, açık dosya, imleç ve yan panelin sekmesi geri gelir.

### Gezinmek
- Her şeye git (Ctrl+P, Ctrl+K): dosyalar, düğümler ve dikişler, bütün projede metin, satır numarası, çalışma yolu, ekranlar ve komutlar.
- Anahat (açık dosyanın düğümleri, dikişleri, işlevleri; imleci izler), sorunlar listesi ve sonraki sorun (Ctrl+.), geri ve ileri (Alt+←, Alt+→).

### Parçacıklar, belgeler, dışa aktarma
- Ink menüsünün bütün parçacıkları, adları Türkçe ve İngilizce; menüde, telefonda "Parçacık ekle…" ile ve komut paletinde.
- inkle'ın *Writing with ink* kılavuzu uygulamanın içinde: kendi yazısıyla çizilir, içindekiler, kılavuzda arama, ink renkleriyle örnekler (kopyalama, düzenleyiciye ekleme), yazı boyu, kalınan yer.
- Üç dışa aktarma: JSON, yalnızca `story.js`, web için (inkjs 2.4.0 ile oynanan sayfa); ink'te hata varken yapılmaz, seçici son yerde açılır.
- Proje istatistikleri (masaüstündeki "Word count and more"): aynı yedi sayı, aynı not.

### Düzen, ayarlar, dil
- Telefonda alt çubuk ve "⋯", tablette ray ve yan yana düzenleyici ile oynatıcı, DeX'te menü çubuğu (Dosya, Düzen, Görünüm, Hikâye, Ink, Yardım), kenar çubuğunda projenin dosyaları ve birden çok pencere.
- Masaüstünün kısayolları (kitin ortak tuşlarıyla çakışanlar değişti; liste `docs/KISAYOLLAR.md`'de), Meta+/ listesi.
- Ayarlar: tema ve vurgu rengi, giriş yoğunluğu, düzenleyici, oynatıcı, otomatik kaydetme, projeler klasörü, kısayollar, hakkında ve açık kaynak lisansları.
- Türkçe ve İngilizce arayüz; ink kavramlarının karşılıkları `docs/SOZLUK.md`'de.

### Altyapı
- Electron uygulaması depodan kaldırıldı; geçmişte ve upstream'de (inkle/inky) duruyor.
- Gradle projesi DeskMail'in düzeninde: Gradle 9.8.2, Android Gradle eklentisi 9.4.1, Kotlin 2.4.20, her sürüm `gradle/libs.versions.toml`'da sabit.
- `:core`: saf Kotlin/JVM; ink'in derleyicisi ve çalıştırıcısı blade-ink 1.3.3 (ink 1.2). Renklendirici Ace'in, derleyici ve oynatıcı resmî inklecate 1.2.0'ın çıktılarıyla karşılaştırılarak sınanır.
- `:app`: desktop-kit (kod düzenleyicili sürüm) üzerinde; Robolectric testleri ve telefon, tablet, DeX boyutlarında, açık ve koyu temada ekran görüntüleri.
- CI ve sürüm iş akışları: derleme, testler, ekran görüntüsü karşılaştırması, imzalı APK; istek üzerine emülatörde duman testi.

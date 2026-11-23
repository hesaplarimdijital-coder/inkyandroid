# Inky (Android)

inkle'ın [Inky](https://github.com/inkle/inky) düzenleyicisinin Android'e taşınmış hâli: etkileşimli
hikâye dili [ink](https://github.com/inkle/ink) ile yazmak ve yazılanı hemen oynatmak için. Kişisel
kullanım içindir; telefonda, tablette ve Samsung DeX'te çalışır. Kotlin ile yazılır, arayüzün tamamı
[desktop-kit](https://github.com/hesaplarimdijital-coder/desktop-kit) ile çizilir. Bu depo
inkle/inky'nin bir çatalıdır; Electron uygulaması geçmişte ve upstream'de durur. Play'de değildir; APK
olarak kurulur.

> **Durum:** 0.1.0, ilk sürüm. Masaüstü Inky 0.15.2'nin özellik listesinin tamamı ya uygulamada ya da
> nedeniyle birlikte `docs/TASINMAYANLAR.md`'de. Uygulama Robolectric'te, ekran görüntüleriyle sınandı;
> gerçek bir telefonda, tablette ve DeX'te henüz denenmedi: denenmesi gerekenler aşağıda. Yapılanlar
> `CHANGELOG.md`'de.

## Neler var

- **Yazmak:** ink'i masaüstü Inky'nin kurallarıyla renklendiren düzenleyici (Ace'in ink kipinin birebir
  taşınmışı), bul ve değiştir, masaüstündeki otomatik tamamlama, tanıma git (Ctrl+ ya da Alt+tıklama,
  F12, dokunmatikte uzun basma), kenarda hata, uyarı ve TODO işaretleri, yakınlaştırma, eş aralıklı ya da
  orantılı yazı. Telefonda klavyenin üstünde ink'in işaretlerini sunan bir şerit.
- **Oynatmak:** hikâye yazdıkça yeniden derlenir ve yapılan seçimlerle baştan oynatılır; bir seçim geri
  alma, baştan başlatma, etiketler, bir paragraftan onu üreten ink satırına gitme, çalışma hataları
  satırlarına bağlantı olarak, izleme ifadeleri. Sonu gelmeyen döngüler uygulamayı kilitlemez.
- **Proje:** bir klasör ve içindeki ana ink dosyası; `INCLUDE` ile çok dosyalı projeler, yeni dosya
  ekleme, yeniden adlandırma, silme. Klasörler Android'in depolama erişimiyle (SAF) açılır; ayrıca
  uygulamanın içinde tutulan projeler ve yeni projeler için bir proje klasörü. Otomatik kaydetme
  (kapatılabilir) ve her zaman taslaklar: kaydedilmemiş metin uygulama kapansa da kaybolmaz.
- **Gezinmek:** her şeye git (dosyalar, düğümler, dikişler, metin, satır numarası, çalışma yolu), anahat,
  sorunlar listesi, sonraki sorun, geri ve ileri.
- **Masaüstündeki ekler:** Ink menüsünün parçacıkları (adları Türkçe) ve projenin kendi parçacıkları;
  üç dışa aktarma (JSON, `story.js`, web için); proje istatistikleri; inkle'ın *Writing with ink*
  kılavuzu, uygulamanın içinde, içindekiler, arama ve ink renkleriyle.
- **Her pencere düzeninde:** telefonda alt çubuk, tablette ray ve yan yana düzenleyici ile oynatıcı,
  DeX'te menü çubuğu, kenar çubuğunda projenin dosyaları, klavye kısayolları ve birden çok pencere.
  Açık, koyu ve OLED tema; Türkçe ve İngilizce.

Nasıl kullanıldığı ekran ekran `docs/KULLANIM.md`'de.

## Kurulum ve güncelleme

Her sürümün imzalı APK'sı [Releases](https://github.com/hesaplarimdijital-coder/inkyandroid/releases)
sayfasındadır (`Inky-<sürüm>.apk`).

**Elle:** telefonda ya da tablette sürüm sayfasını aç, APK'ya dokunup indir, indirileni aç. Android "bu
kaynaktan yüklemeye izin ver" diye sorarsa tarayıcıya izin ver. Yeni sürüm eskisinin üstüne kurulur.

**Obtainium ile (isteğe bağlı):** [Obtainium](https://github.com/ImranR98/Obtainium) uygulamayı sürüm
sayfasından kurar ve yeni sürüm çıkınca haber verir. **Add App** › adres:
`https://github.com/hesaplarimdijital-coder/inkyandroid`, sonra **Add** ve **Install**. Ön sürümleri de
istersen **Include Pre-releases**'i aç.

Bütün sürümler aynı anahtarla imzalıdır (aşağıda); güncelleme eskisinin üstüne gelir, projeler, taslaklar
ve ayarlar kalır.

**İlk açılış:** uygulama "İlk hikâye" adlı, uygulamanın içinde tutulan bir projeyle açılır; hemen yazılıp
oynatılabilir. Kendi klasörlerin için Projeler › Klasör aç (Ctrl+O); yeni projelerin bir klasörde
oluşması için Ayarlar › Dosyalar › Proje klasörü (örneğin `Documents/Inky`).

## Belgeler

| Dosya | İçerik |
| --- | --- |
| `docs/KULLANIM.md` | Masaüstü Inky'nin her özelliğine telefonda, tablette ve DeX'te nereden ulaşılır |
| `docs/KISAYOLLAR.md` | Bütün klavye kısayolları, masaüstünden farklarıyla |
| `docs/TASINMAYANLAR.md` | Masaüstünden farklı olan ve taşınmayan her şey, nedeniyle; ink'in burada çalışışındaki farklar |
| `docs/SOZLUK.md` | ink ve Inky kavramlarının arayüzdeki Türkçe karşılıkları |
| `docs/BAKIM.md` | Güncellerken bakılacaklar; emülatörde duman testi |

## Cihazda denenecekler

Robolectric ve emülatör şunları söyleyemez; sürümden önce cihazda bir kez denenir:

- **Depolama sağlayıcıları:** dahili depolamada ve SD kartta bir klasör (`Documents/Inky`) seçmek, proje
  oluşturmak, kaydetmek, dosyayı başka klasöre taşıyarak yeniden adlandırmak, silmek; `.ink` ve `.json`
  adlarının `.txt` almadığı. Google Drive ve Samsung bulut klasörlerinde kaydetmenin yerinde yazdığı ve
  kaydedilen dosyanın "dışarıda değişti" sanılmadığı.
- **Dosya yöneticilerinden açmak:** Samsung Dosyalarım, Files by Google ve Drive'da bir `.ink` dosyasına
  dokununca Inky'nin önerilmesi; salt okunur paylaşılan dosyada "klasöre erişim ver" yolu.
- **İzinler:** cihaz yeniden başlayınca klasör izinlerinin sürmesi; izin Ayarlar'dan geri alınınca
  şeridin çıkması; SD kart çıkarılınca projenin ulaşılamaz görünmesi.
- **DeX:** pencereyi fareyle boyutlandırırken düzenin değişmesi ve açık dosyanın, imlecin, oynatıcının
  yerinde kalması; iki pencerede iki proje; menü çubuğunun başlık çubuğuna yerleşmesi; kısayollar ve
  Meta+/ listesi.
- **Klavyeler:** Samsung Klavye ile yazarken ink şeridi, öneriler açık ve kapalı (Ayarlar › Düzenleyici);
  klavye kapağı ve Bluetooth klavyeyle Türkçe Q düzeninde Ctrl+/, Ctrl+= gibi tuşlar.
- **Hız:** uzun bir projede (Ink menüsündeki *The Intercept*, yaklaşık 1.700 satır) yazarken takılma olmaması,
  oynatıcının yazmayı bekletmemesi.
- **Dışa aktarma:** web için dışa aktarılan klasörün bir bilgisayarda tarayıcıda açılıp oynaması.

## Yapı

| Modül | İçerik |
| --- | --- |
| `:core` | Saf Kotlin/JVM, Android'siz; JVM testleriyle sınanır. `syntax` (ink'in renklendiricisi, Ace'in kurallarıyla), `symbols` (düğümler, dikişler, değişkenler, `INCLUDE`'lar), `completion`, `search` (her şeye git), `snippets`, `settings` (proje ayar dosyası), `project` (proje modeli, taslaklar, gezinme geçmişi), `engine` (derleme, oynatma, izleme, istatistik, dışa aktarma; ink'in derleyicisi ve çalıştırıcısı [blade-ink](https://github.com/bladecoder/blade-ink-java) 1.3.3, ink 1.2 düzeyinde, modülün kendi API'sinin dışında kalır), `live` (yazdıkça derleyip oynatan oturum). |
| `:app` | Android uygulaması: `storage` (SAF ve uygulama içi projeler, son projeler, dışa aktarma yazıcıları), `session` (penceredeki açık proje, otomatik kaydetme, taslaklar, geri yükleme), `settings`, `ink` (çekirdek ile kitin düzenleyicisi arasındaki bağlar), `ui` (kabuk, menüler, düzenleyici, oynatıcı, dosyalar, projeler; `ui.extras`: belgeler, ayarlar, istatistikler, dışa aktarma, parçacık adları). |

Araçlar desktop-kit ile aynıdır: Android Gradle eklentisi 9.4.1, Gradle 9.8.2, Kotlin 2.4.20; `minSdk` 34,
`compileSdk` 37, `targetSdk` 36. Testler Java 21 ister (Robolectric 4.17, API 36). Bütün sürümler
`gradle/libs.versions.toml`'da sabittir.

## Derleme ve testler

```
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
```

- Bağımlılıklar Google Maven, Maven Central ve (yalnızca desktop-kit için) JitPack'ten iner.
- `:core`'un testleri saf JVM'de koşar (yaklaşık 350). Renklendirici Ace'in kendi çıktılarıyla,
  derleyici ve oynatıcı resmî inklecate 1.2.0'ın çıktılarıyla karşılaştırılır; bu çıktıları üreten
  betikler `core/src/test/golden/`'da, bilinen farklar
  `core/src/test/resources/com/inkyandroid/core/golden/engine/KNOWN-DIFFERENCES.md`'de.
- `:app`'in testleri Robolectric'te koşar (yaklaşık 300): oturum, depolama, ekranların davranışı ve
  ekranların telefon, tablet ve DeX boyutlarında, açık ve koyu temada ekran görüntüleri
  (`app/build/outputs/screenshots`; başvuru görüntüleri `app/src/test/screenshots`).
- CI (`.github/workflows/ci.yml`) her çekme isteğinde ve `master`'a her gönderimde derler, testleri
  çalıştırır, ekran görüntülerini başvurularla karşılaştırır ve APK'yı çıktı olarak yükler (`inky-apk`).
  Çekme isteklerinde derleme günlüğü ve test sonuçları `ci/logs-pr-<numara>`, ekran görüntüleri
  `ci/screenshots-pr-<numara>` dalına konur.
- Emülatörde duman testi (`.github/workflows/smoke.yml`) istek üzerine çalışır; nasıl, `docs/BAKIM.md`'de.

## Sürüm çıkarma

Sürümler [SemVer](https://semver.org/lang/tr/) kuralına uyar; `versionCode` her sürümde bir artar.

1. Sürümü taşıyan PR `master`'a birleşir (`app/build.gradle.kts`'teki `versionName` ve `CHANGELOG.md`'deki
   bölüm o PR'ın son commit'idir).
2. GitHub'da **Releases › Draft a new release**: etiket `versionName` ile aynı ("v" yok), hedef `master`,
   başka hiçbir şey yazmadan **Publish release**.
3. `.github/workflows/release.yml` uygulamayı o etiketten sabit anahtarla derler, APK'nın imzasını
   denetler, `Inky-<sürüm>.apk` dosyasını sürüme ekler, sürümün metnini `CHANGELOG.md`'deki bölümden
   doldurur ve adında "-" olan sürümü ön sürüm olarak işaretler.

Etiket `master` üzerinde değilse ya da `versionName` ile uyuşmuyorsa iş akışı durur ve nedenini söyler.
`CHANGELOG.md`'de her madde tek satırdır: sürüm notlarında satır sonları olduğu gibi görünür.

## İmza

Kurulabilir her APK aynı sabit anahtarla (PKCS12, takma adı `inky`) imzalanır; yoksa yeni sürüm
eskisinin üstüne kurulamaz. Anahtar depoda durmaz: CI onu `SIGNING_KEYSTORE_BASE64` ve
`SIGNING_KEYSTORE_PASSWORD` gizli değişkenlerinden okur ve her derlemede APK'nın şu sertifikayla
imzalandığını denetler:

```
SHA-256 14:67:49:EF:7E:16:D8:65:B9:1B:99:D5:C8:C4:21:14:BC:BA:66:27:D1:67:35:E4:91:C1:94:B4:DA:36:E7:8D
```

Yerelde aynı anahtarla derlemek için `INKY_KEYSTORE_FILE` anahtar dosyasını, `INKY_KEYSTORE_PASSWORD`
şifresini gösterir. Bunlar yoksa derleme makinenin geçici debug anahtarıyla imzalar; böyle bir APK sabit
anahtarla imzalanmış bir kurulumun üstüne kurulamaz. **Anahtar kaybolursa güncelleme yapılamaz:**
uygulamayı kaldırıp yeniden kurmak gerekir. Anahtarın ve şifresinin bir yedeği GitHub dışında da
(parola yöneticisinde) durmalıdır.

## Teşekkür ve lisans

- [Inky](https://github.com/inkle/inky) ve [ink](https://github.com/inkle/ink): inkle Ltd., MIT lisansı.
  Bu uygulama Inky'nin tasarımını izler; başlatıcı simgesi Inky'nin kendi simgesinden yapıldı. *Writing
  with ink* kılavuzu, parçacıklar, örnek hikâyeler ve web için dışa aktarmanın şablonu Inky'nin
  deposundan, değiştirilmeden gelir.
- [blade-ink](https://github.com/bladecoder/blade-ink-java): Rafael Garcia; ink'in Java portu (MIT;
  Maven paketleri Apache-2.0 der).
- [inkjs](https://github.com/y-lohse/inkjs) 2.4.0: inkle Ltd. ve inkjs katkıcıları, MIT; web için dışa
  aktarılan sayfa onunla oynar.
- [desktop-kit](https://github.com/hesaplarimdijital-coder/desktop-kit): Apache-2.0; IBM Plex yazı
  tipleri (OFL 1.1) ve Haze (Apache-2.0) ile.
- [commonmark-java](https://github.com/commonmark/commonmark-java): Atlassian, BSD-2-Clause; kılavuzu okur.

Uygulama MIT lisansıyla dağıtılır (`LICENSE`); üçüncü taraf parçaların ayrıntısı `NOTICE`'ta, lisans
metinleri uygulamada Ayarlar › Hakkında › Açık kaynak lisansları'nda.

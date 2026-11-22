# Inky (Android)

inkle'ın [Inky](https://github.com/inkle/inky) düzenleyicisinin Android'e taşınmış hâli: etkileşimli
hikâye dili [ink](https://github.com/inkle/ink) ile yazmak ve yazılanı hemen oynatmak için. Kişisel
kullanım içindir; telefonda, tablette ve Samsung DeX'te çalışır. Kotlin ile yazılır, arayüzün tamamı
[desktop-kit](https://github.com/hesaplarimdijital-coder/desktop-kit) ile çizilir. Bu depo
inkle/inky'nin bir çatalıdır; Electron uygulaması geçmişte ve upstream'de durur. Play'de değildir; APK
olarak kurulur.

> **Durum:** kurulum aşamasında. Proje iskeleti, derleme, testler ve CI hazır; uygulama tek bir ekranla
> açılır ve kurulmakta olduğunu söyler. Düzenleyici ve oynatıcı sonraki adımlarla gelir. Yapılanlar
> `CHANGELOG.md`'de.

## Yapı

| Modül | İçerik |
| --- | --- |
| `:core` | Saf Kotlin/JVM, Android'siz. ink'e dair her şey burada durur ve JVM testleriyle sınanır. ink'in derleyicisi ve çalıştırıcısı [blade-ink](https://github.com/bladecoder/blade-ink-java) 1.3.3'tür (ink'in Java portu, ink 1.2.0 düzeyinde); modülün kendi API'sinin dışında kalır. |
| `:app` | Android uygulaması: arayüz (desktop-kit). |

Araçlar desktop-kit 2.9.0 ile aynıdır: Android Gradle eklentisi 9.4.1, Gradle 9.8.2, Kotlin 2.4.20;
`minSdk` 34, `compileSdk` 37, `targetSdk` 36. Testler Java 21 ister (Robolectric 4.17, API 36). Bütün
sürümler `gradle/libs.versions.toml`'da sabittir.

## Derleme ve testler

```
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
```

- Bağımlılıklar Google Maven, Maven Central ve (yalnızca desktop-kit için) JitPack'ten iner.
- `:core` testleri saf JVM'de, `:app` testleri Robolectric'te koşar. Ekran görüntüsü testleri uygulamayı
  telefon ve DeX boyutlarında çizer (`app/build/outputs/screenshots`; başvuru görüntüleri
  `app/src/test/screenshots`, henüz yok).
- CI (`.github/workflows/ci.yml`) her çekme isteğinde ve `master`'a her gönderimde derler, testleri
  çalıştırır ve APK'yı çıktı olarak yükler (`inky-apk`). Çekme isteklerinde derleme günlüğü ve test
  sonuçları `ci/logs-pr-<numara>`, ekran görüntüleri `ci/screenshots-pr-<numara>` dalına konur.

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
  Bu uygulama Inky'nin tasarımını izler; başlatıcı simgesi Inky'nin kendi simgesinden yapıldı.
- [blade-ink](https://github.com/bladecoder/blade-ink-java): Rafael Garcia; ink'in Java portu.
- [desktop-kit](https://github.com/hesaplarimdijital-coder/desktop-kit): Apache-2.0; IBM Plex yazı
  tipleri (OFL 1.1) ve Haze (Apache-2.0) ile.

Uygulama MIT lisansıyla dağıtılır (`LICENSE`); üçüncü taraf parçaların ayrıntısı `NOTICE`'ta.

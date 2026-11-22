# Değişiklikler

Sürümler [SemVer](https://semver.org/lang/tr/) kuralına uyar; `versionCode` her sürümde bir artar. Her sürümün imzalı APK'sı GitHub'daki sürüm sayfasına eklenir.

## Yayımlanmamış

Android portunun iskeleti; uygulama henüz kurulum aşamasında.

### Altyapı
- Electron uygulaması depodan kaldırıldı; geçmişte ve upstream'de (inkle/inky) duruyor.
- Gradle projesi DeskMail'in düzeninde: Gradle 9.8.2, Android Gradle eklentisi 9.4.1, Kotlin 2.4.20, her sürüm `gradle/libs.versions.toml`'da sabit.
- `:core`: saf Kotlin/JVM modülü; ink'in derleyicisi ve çalıştırıcısı olarak blade-ink 1.3.3. Kısa bir ink metnini derleyip oynatan bir test.
- `:app`: desktop-kit 2.9.0 üzerinde tek ekranlı kabuk; ekran uygulamanın kurulduğunu söyler. Başlatıcı simgesi Inky'nin kendi simgesinden.
- Testler: gerçek etkinliği Robolectric'te başlatan test ve telefon ile DeX boyutunda ekran görüntüleri.
- CI ve sürüm iş akışları (`.github/workflows/ci.yml`, `release.yml`): derleme, testler, ekran görüntüsü karşılaştırması, imzalı APK.

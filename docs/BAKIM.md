# Bakım notları

Inky'nin Android sürümünü güncellerken ve bir değişikliği denerken bakılacakları toplar. Her madde ya
"değişmedi" diye geçilir ya da kendi PR'ına dönüşür.

## Cihazda duman testi (emülatör)

Robolectric testleri uygulamanın gerçek bir Android'de açılıp açılmadığını, kitin cam yüzeylerinin ve
yerleşimlerinin çizilip çizilmediğini, döndürmeye, arka plana ve sürecin öldürülmesine dayanıp
dayanmadığını söyleyemez. Bunun için `.github/workflows/smoke.yml` var: bir commit'in APK'sını derler,
GitHub'ın makinelerinde iki emülatörde açar ve `tools/smoke.py` ile adım adım gezer.

- **Telefon:** Pixel 6, Android 14 (API 34), üç düğmeli gezinme, ekran klavyesi.
- **Tablet:** Pixel Tablet, Android 17 (API 37), yatay; 1280 dp genişlik, yani kitin geniş pencere
  düzeni (menü çubuğu ve kenar çubuğu). DeX'teki gibi donanım klavyesi takılı.

**Çalıştırmak:** commit'i `smoke/<ad>` adlı bir dala itmek yeter
(`git push origin <commit>:refs/heads/smoke/<ad>`), ya da GitHub'da Actions › Device smoke › Run workflow
(alanlar: denenecek commit ya da dal, sonuçların adı, senaryolar). Bir çalıştırma 12–15 dakika sürer.

**Sonuçlar:** her profil için `ci/smoke/<ad>/<profil>` dalına konur (`git clone --depth 1 -b
ci/smoke/<ad>/phone …`): her adımın ekran görüntüsü (`NN-<adım>.png`), adım adım rapor (`report.txt`,
son satırı `RESULT: PASS` ya da `FAIL`), uygulamanın logcat'i, `STATUS`. Bir adımın denetimi tutmazsa,
uygulama çökerse ya da yanıt vermez olursa iş kırmızı olur. Raporun yeşil olması metinlerin ekranda
olduğunu söyler; ekranın doğru göründüğünü görüntülere bakarak anlarız.

**Senaryolar** `tools/smoke.py`'nin başında veri olarak durur (adımlar, eylemler, denetimler; nasıl
yazıldıkları orada anlatılıyor). Senaryo 1 uygulamanın kendisini dener. Telefonda: ilk açılıştaki proje
düzenleyicide ve oynatıcıda; tuşlarla yazılan bir satır ve ekran klavyesinin tuşlarına basılarak yazılan
bir sözcük, ikisi de oynatıcıda; seçim, bir seçim geri, baştan başlatma; var olan bir sözcüğün hemen
önüne ekran klavyesiyle yazmak (imleç dokunarak ve Home tuşuyla konmuş); hata ve düzeltmesi; dosyalar,
belgeler, ayarlar; döndürme, arka plan, süreç ölümü; zorla durdurmadan üç saniye önce yazılan sözcüğün
yeniden açılışta yerinde olması; Türkçe (cihazın dili gerçekten değiştirilerek). Tablette: menü çubuğu,
kenar çubuğu, yan yana düzenleyici ve oynatıcı, komut paleti (Ctrl+K), bul (Ctrl+F), Dosya menüsü,
sorunlar, zorla durdurma, döndürünce dar düzen, süreç ölümü, Türkçe. `ime-control` senaryosu (istenince)
aynı yazma yollarını Android'in sıradan bir metin alanında dener: bir hata klavyeden mi düzenleyiciden
mi, onu ayırmak için.

**Bakımda:**

- Emülatör sürümleri `smoke.yml`'nin `matrix` bölümündedir. Telefonun API düzeyi ancak yenisinin
  güvenilir açıldığı görülünce yükseltilir; tablet sahibinin cihazıyla aynı Android sürümünde tutulur.
- `reactivecircus/android-emulator-runner` ve diğer eylemlerin yeni ana sürümleri.
- Uygulamanın metinleri değişince senaryo 1'in `S1` sözlüğü de değişir (adımlar düğmeleri yazılarından
  ya da içerik açıklamalarından bulur).

**Emülatörün söyleyemedikleri:** DeX'in kendisi (pencereler, fareyle boyutlandırma, görev çubuğu), One UI
ve Samsung Klavye, Barış'ın klavyeleri ve faresi, gerçek SAF sağlayıcıları (Google Drive, Dosyalarım,
USB) ve hız (emülatör yazılımla çizer). Bunlar sürümden önce cihazda elle denenir.

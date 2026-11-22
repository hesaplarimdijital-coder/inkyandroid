# Masaüstü Inky'den farklar ve taşınmayanlar

Inky 0.15.2'nin özellik listesindeki (`inky-spec.md`, F-001 … F-188) her madde ya uygulamada vardır ya da
burada nedeniyle birlikte yazılıdır. "Taşınmadı" bölümündekiler bu sürümde yoktur; "Farklı" bölümündekiler
aynı işi Android'in ya da kitin yoluyla yapar. Tuşların farkları `KISAYOLLAR.md`'de.

## Taşınmadı

| Madde | Masaüstü Inky | Neden |
| --- | --- | --- |
| F-002 | Pencere 1300 × 730, en küçük 350 × 250 | Pencerenin boyutunu Android ve DeX belirler. |
| F-005 | İşletim sistemine göre pencere başlığı | Pencere başlığını sistem gösterir (uygulamanın adı). |
| F-008 | Kenar çubuğu ile düzenleyici arasında sürüklenen ayraç | Kitin kenar çubuğu sabit genişliktedir; düzenleyici ile yan panelin arasındaki ayraç sürüklenir ve hatırlanır (F-007). |
| F-010 | Kenar çubuğunun 200 ms'lik kayması, sütun genişlikleri | Kitin kenar çubuğu kendi hareketini kullanır; iki sütun yoktur. |
| F-021 | Pencere menüsü | Pencereleri Android yönetir. |
| F-057 | macOS'ta açık dosyayı bırakınca penceresinin öne gelmesi | macOS'a özgü. |
| F-060 | Çıkarken her pencereye kaydetme sorusu | Android'de "çık" yoktur; kaydedilmemiş metin taslak olarak her zaman saklanır ve geri gelir. |
| F-071 | Seçili sözcüğün öteki geçişlerinin vurgulanması | Kitin düzenleyicisinde yok (etkin satır ve eşleşen ayraç vurgulanır). |
| F-072 | Çoklu imleç, dikdörtgen seçim | Düzenleyici kitin `CodeEditor`'üdür (mimari 4.4). |
| F-075 | Yorum satırı yap / kaldır (`//`, `/* */`) | Kitin düzenleyicisinin satır komutları henüz yayımlanmadı; gelince Alt+/ ile eklenecek. Menülerde görünmez. |
| F-076 | Düğüm ve dikiş satırlarında katlama | Kitin düzenleyicisinde katlama yok (mimari 4.4). |
| F-085 | Ace'in "önceki hata" komutu | "Sonraki sorun" (Ctrl+.) sorunlar arasında döner; geriye giden ayrı bir komut yok. |
| F-176 | Tam ekran | Pencereyi sistem yönetir. |
| F-182 | HTML'deki durağan metinlerin yüklenirken çevrilmesi | Arayüz HTML değil; bütün metinler kaynak dosyalarında, Türkçe ve İngilizce. |
| F-187 | Geliştirici menüsü (pencereyi yeniden yükle, geliştirici araçları) | Masaüstüne özgü. |

## Farklı

| Madde | Masaüstü Inky | Burada |
| --- | --- | --- |
| F-001 | Yeni proje ve Aç hep yeni pencere açar | Pencere projesini değiştirir (açık proje önce kapanır; otomatik kaydetme kapalıysa upstream'in sorusu sorulur). Yeni pencere Ctrl+Shift+N (DeX). Bir proje aynı anda tek pencerede açılır; ikinci pencere bunu söyler ve başka proje seçtirir. |
| F-009, F-011 | Gizli başlayan iki panelli kenar çubuğu; dosyalar paneli ilk `INCLUDE`'da kendiliğinden açılır | DeX'te dosyalar kenar çubuğunda hep listelidir, anahat yan panelin sekmesidir. Telefonda ve tablette "Dosyalar" ekranı (Dosyalar ve Anahat). |
| F-018 | Görünüm menüsünde tema ve yüzde olarak yakınlaştırma | Tema Ayarlar'da; yakınlaştırma kitin adımlarıyla (Ctrl+=, Ctrl+-, Ctrl+0, kıstırma, Ctrl+tekerlek), düzenleyici ve oynatıcı birlikte. |
| F-020, F-180 | Ink menüsünde kategoriler alt menüdür | Bir kategori komut paletini o kategorinin parçacıklarıyla açar; kitin iç içe menüleri yayımlanınca alt menü olacak. Projenin kendi parçacıkları ve tuşları menüde. |
| F-022 | Yardım › Hakkında | Hakkında ve lisanslar Ayarlar ekranında. |
| F-023 | Menü öğeleri hep etkin | Yapacak işi olmayan öğe soluk görünür (kitin kuralı). |
| F-024, F-040, F-041 | "Untitled.ink"; ilk kaydetmede yer sorulur | Yeni proje oluşturulurken adı ve yeri sorulur (projeler klasörü, seçilen bir klasör ya da uygulamanın içi); ana dosya projenin adını taşır. İlk açılışta "İlk hikâye" projesi upstream'in yeni proje metniyle açılır. |
| F-038 | Dosya yeniden adlandırılamaz, silinemez | Dosyalar ekranından, kenar çubuğundaki girişin menüsünden yeniden adlandırılır ve (onayla) silinir. |
| F-045 | Yüklenmekte olan dosya yarı saydam | Dosyalar proje açılmadan okunur. Okunamayanlar (UTF-8 değil, izin yok) Dosyalar ekranında ayrı listelenir, nedeniyle ve "Yeniden dene" düğmesiyle. |
| F-046 … F-051 | Proje klasörünün sürekli izlenmesi | Android'in depolama erişimi (SAF) klasör izleyemez: pencere öne geldiğinde ve "Depolamadan yeniden yükle" (F5) ile denetlenir; kurallar upstream'inki. |
| F-054 | Son açılanlar alt menüsü (5 yol, "Temizle") | Projeler ekranı: son açılanlar, listeden çıkarma, temizleme. Dosya › Son açılanlar oraya götürür. |
| F-080 | Yönlendirme hedefleri ve `INCLUDE` yolları hep altı çizili | Bağlantı renginde gösterilir; fare üstündeyken Ctrl ya da Alt basılıysa altı çizilir ve el imleci çıkar (kitin düzenleyicisi). Dokunmatikte uzun basınca "Tanıma git". |
| F-090 | Önerilerin fuzzaldrin sıralaması | Öneriler upstream'in kurallarıyla toplanır, kitin eşleştirmesiyle süzülüp sıralanır (büyük/küçük harf ve Türkçe harfler katlanır). |
| F-094 | Atlanan satır ortalanır | Uzaktaki hedef ekranın üçte birine gelir (kitin düzenleyicisi). |
| F-140 | Araç çubuğunda sorunların açılır listesi | Sorunlar sekmesi (DeX, tablet) ya da ekranı (telefon) ve sayı rozeti; tıklanan sorun satırını açar. |
| F-152 | fuzzaldrin-plus puanlaması (eşik 6000) | Kitin komut paletinin eşleştirmesi. |
| F-154 | Çalışma yolu sorgusu (`knot.0.c-1`) yazarken çözülür | Seçilince çözülür (kitin paleti sonuçları sorgu başına bir kez hesaplar). |
| F-167 | Son dışa aktarma yolu bir sonrakinin varsayılanı | Sistemin dosya seçicisi kendi son klasörünü hatırlar. |
| F-171 | Açık, Koyu, Kontrast, Odak temaları | Kitin açık, koyu, OLED temaları ve vurgu rengi; "odak" yan paneli kapatmaktır (mimari 4.4). |
| F-172 | %50–%300 yakınlaştırma ön ayarları | Kitin yakınlaştırması, kalıcı. |
| F-175 | "Etiketler görünsün" pencere başına, kaydedilmez | Uygulamanın ayarı: kaydedilir, bütün pencerelere uygulanır. |
| F-177 | Görünüm ayarları bir JSON dosyasında | Android'in ayar deposunda. |

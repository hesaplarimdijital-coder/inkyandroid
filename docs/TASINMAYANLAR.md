# Masaüstü Inky'den farklar ve taşınmayanlar

Inky 0.15.2'nin özellik listesindeki (`inky-spec.md`, F-001 … F-188) her madde ya uygulamada vardır ya da
burada nedeniyle birlikte yazılıdır. "Taşınmadı" bölümündekiler bu sürümde yoktur; "Farklı" bölümündekiler
aynı işi Android'in ya da kitin yoluyla yapar. Tuşların farkları `KISAYOLLAR.md`'de; sondaki bölüm,
ink'in kendisinin burada nasıl çalıştığını (hikâyenin oynatılışındaki farkları) anlatır.

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
| F-075 | Blok yorumu yap / kaldır (`/* */`) | Kitin düzenleyicisinde yok; satır yorumu (`//`) var (Düzen › Yorum satırı yap / kaldır, Alt+/). |
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
| F-020, F-180 | Ink menüsünde kategoriler alt menüdür | Bir kategori komut paletini o kategorinin parçacıklarıyla açar. Projenin kendi parçacıkları ve tuşları menüde. |
| F-022 | Yardım › Hakkında | Hakkında ve lisanslar Ayarlar ekranında. |
| F-023 | Menü öğeleri hep etkin | Yapacak işi olmayan öğe soluk görünür (kitin kuralı). |
| F-024, F-040, F-041 | "Untitled.ink"; ilk kaydetmede yer sorulur | Yeni proje oluşturulurken adı ve yeri sorulur (projeler klasörü, seçilen bir klasör ya da uygulamanın içi); ana dosya projenin adını taşır. İlk açılışta "İlk hikâye" projesi upstream'in yeni proje metniyle açılır. |
| F-038 | Dosya yeniden adlandırılamaz, silinemez | Dosyalar ekranından, kenar çubuğundaki girişin menüsünden yeniden adlandırılır ve (onayla) silinir. |
| F-045 | Yüklenmekte olan dosya yarı saydam | Dosyalar proje açılmadan okunur. Okunamayanlar (UTF-8 değil, izin yok) Dosyalar ekranında ayrı listelenir, nedeniyle ve "Yeniden dene" düğmesiyle. |
| F-046 … F-051 | Proje klasörünün sürekli izlenmesi | Android'in depolama erişimi (SAF) klasör izleyemez: pencere öne geldiğinde ve "Depolamadan yeniden yükle" (F5) ile denetlenir; kurallar upstream'inki. |
| F-054 | Son açılanlar alt menüsü (5 yol, "Temizle") | Projeler ekranı: son projeler, listeden çıkarma, temizleme. Dosya › Son projeler oraya götürür. Klasörü ya da izni gitmiş projeler, Projeler açılınca listeden çıkar. |
| F-059 | Kapatma sorusunda "Kaydet" kaydeder, pencere açık kalır | "Kaydet" kaydeder ve projeyi kapatır (sorunun sorulduğu iş, örneğin başka bir projeyi açmak, sürer). |
| F-080 | Yönlendirme hedefleri ve `INCLUDE` yolları hep altı çizili | Bağlantı renginde gösterilir; fare üstündeyken Ctrl ya da Alt basılıysa altı çizilir ve el imleci çıkar (kitin düzenleyicisi). Dokunmatikte uzun basınca "Tanıma git". |
| F-090 | Önerilerin fuzzaldrin sıralaması | Öneriler upstream'in kurallarıyla toplanır, kitin eşleştirmesiyle süzülüp sıralanır (büyük/küçük harf ve Türkçe harfler katlanır). |
| F-094 | Atlanan satır ortalanır | Uzaktaki hedef ekranın üçte birine gelir (kitin düzenleyicisi). |
| F-140 | Araç çubuğunda sorunların açılır listesi | Sorunlar sekmesi (DeX, tablet) ya da ekranı (telefon) ve sayı rozeti; tıklanan sorun satırını açar. |
| F-152 | fuzzaldrin-plus puanlaması (eşik 6000) | Kitin komut paletinin eşleştirmesi. |
| F-154 | Çalışma yolu sorgusu (`knot.0.c-1`) yazarken çözülür | Seçilince çözülür (kitin paleti sonuçları sorgu başına bir kez hesaplar). |
| F-171 | Açık, Koyu, Kontrast, Odak temaları | Kitin açık, koyu, OLED temaları ve vurgu rengi; "odak" yan paneli kapatmaktır (mimari 4.4). |
| F-172 | %50–%300 yakınlaştırma ön ayarları | Kitin yakınlaştırması, kalıcı. |
| F-175 | "Etiketler görünsün" pencere başına, kaydedilmez | Uygulamanın ayarı: kaydedilir, bütün pencerelere uygulanır. |
| F-177 | Görünüm ayarları bir JSON dosyasında | Android'in ayar deposunda. |
| F-119 | Yeni metin 1 sn'de, satırlar 200 ms arayla belirir | 400 ms'de, 60 ms arayla (kitin hareket süreleri); "Oynatıcı animasyonu" kapalıysa ve sistemin "Animasyonları kaldır" ayarında hiç hareket yok. |
| F-121 | Bir seçeneğin etiketleri metninin arkasında | Seçeneğin altında, kendi satırında (paragrafların etiketleri gibi). |
| F-127 | Artık olmayan bir seçim atlanır, oynayış kayarak sürer | Yeniden oynatma o seçimde durur; oradan sonrası yeni seçimlerle sürer. |
| F-132, F-133 | İzleme ifadesinin varsayılanı `x is {x}`, iki `x` birden seçili; sonuçlar seçeneklerin altında | Yalnızca ilk `x` seçili (çoklu imleç yok, F-072): adı yazınca ikinci `x` elle değişir. Alanda ink renkleri yok. Canlı turda sonuçlar seçeneklerin üstünde. |
| F-139 | Sorun yokken araç çubuğunda "No issues." | Sorun yokken hiçbir şey yazmaz; sorun sayısı rozetle gelir. |
| F-168 | Var olan bir klasör adına web için dışa aktarma reddedilir | Aynı addaki klasörün içine yazar, içindeki aynı adlı dosyaları değiştirir. |
| F-183, F-184 | Belgeler ayrı bir pencerede, başlık listesi yanında, yalnızca koyu temada koyu | Belgeler bir ekrandır (F1): kılavuz uygulamanın kendi yazısıyla çizilir, temanın bütün renklerine uyar; içindekiler (genişte yanda, telefonda alttan açılan sayfa), kılavuzda arama, örneklerin ink renkleri, kopyalama ve düzenleyiciye ekleme, yazı boyu ve kalınan yer eklendi. |

## Hikâyenin oynatılışı

Uygulama ink'i telefonun içinde, ink'in Java'ya aktarılmış derleyicisiyle (blade-ink) derleyip oynatır;
masaüstü Inky ise inkle'ın kendi derleyicisini (inklecate) çalıştırır. İkisi aynı hikâyeden aynı metni ve
aynı seçenekleri üretir; bunu uygulamayla birlikte gelen 25 örnek hikâye ve 22 sınama hikâyesi üzerinde,
inklecate'in çıktısıyla satır satır karşılaştıran testler denetler. Derleyicinin hata ve uyarı
iletileri, masaüstü Inky'de olduğu gibi ink'in kendi İngilizce metinleridir ("Divert target not
found…"); arayüz Türkçeyken de öyle kalır, Sorunlar'da iletiye dokununca satırı açılır. Bir yazarın
fark edebileceği iki ayrım var:

- **Ondalık sayılar biraz farklı yazılır.** Bir değişkenin değeri tam sayıya denk gelen bir ondalık
  sayıysa uygulama `3.0` yazar, masaüstü Inky `3`; çok büyük sayılar `1.0E8` gibi görünür. Kesirli
  sayılar (`0.5`) iki yerde de aynıdır. Hikâyenin oyuncuya göstereceği bir sayıyı tam sayı olarak
  tutmak (ya da `INT()` ile yazdırmak) bu farkı ortadan kaldırır.
- **Rastgelelik başka sayılar üretir.** `RANDOM`, karışık sıralar (`{~a|b|c}`) ve `LIST_RANDOM` aynı
  tohumla bile masaüstündekinden başka sonuçlar verir; web'e aktarılan sürüm (inkjs) de üçüncü bir
  sıra üretir. Bir oynayış kendi içinde tutarlıdır: metin değişip hikâye yeniden derlenince yapılmış
  seçimler aynı sonuçları verir; hikâyeyi baştan başlatmak zarı yeniden atar.

İşine yarayacak farklar ise şunlar:

- **Sonsuz döngü uygulamayı kilitlemez.** Hiç bitmeyen bir hikâye (hep metin basan ya da hiçbir şey
  basmadan dönen bir döngü, kendini durmadan çağıran bir işlev, kendine dönen bir tünel) belli bir satır,
  süre ya da derinlikten sonra durdurulur ve oynatıcı nedenini söyler. Masaüstü Inky'de metin basmayan
  bir döngü sessizce takılır, kendini çağıran bir işlev belleği doldurur.
- **Derleyicinin çöktüğü yerde bir ileti.** Tam sayının sıfıra bölünmesi ya da derleyicinin
  taşıyamayacağı kadar iç içe süslü ayraçlar masaüstünde derleyiciyi çökertir ("the ink compiler
  crashed"). Burada ilkinde hikâye o noktada durur ve oynatıcı bunu söyler, ikincisi Sorunlar'da bir hata
  olarak görünür; metin her durumda güvendedir.
- **İzleme ifadelerinde işlev çağrısı çalışır.** `double(x)` gibi bir ifade masaüstü Inky'de hiç
  yanıt vermez; burada değeri gösterilir.

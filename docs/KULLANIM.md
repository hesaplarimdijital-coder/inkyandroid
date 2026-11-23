# Kullanım

Masaüstü Inky'nin her özelliğine telefonda, tablette ve DeX'te nereden ulaşıldığı. Tuşların tam tablosu
`KISAYOLLAR.md`'de, kavramların Türkçe karşılıkları `SOZLUK.md`'de, masaüstünden farklı olan ya da hiç
taşınmayan şeyler nedenleriyle `TASINMAYANLAR.md`'de.

## Üç düzen

Uygulama cihaza değil pencerenin genişliğine bakar: DeX'te pencere daraltılınca telefon düzenine, tablet
yan çevrilince geniş düzene geçer; açık dosya, imleç, kaydırma yeri ve oynatıcı yerinde kalır. Yan
çevrilmiş telefon rayı alır; düzenleyici ile yan panel sığdığı için yan yana durur, dosyanın adı, geri ve
ileri, yan panel ve menüler üst çubuktadır.

| | Telefon | Tablet | DeX, büyük pencere |
| --- | --- | --- | --- |
| Gezinme | altta Yaz, Oyna, Dosyalar; gerisi "⋯"de | soldaki rayda bütün ekranlar | üstte menü çubuğu (Dosya, Düzen, Görünüm, Hikâye, Ink, Yardım), solda kenar çubuğu |
| Yaz | yalnızca düzenleyici | düzenleyici ve yanında yan panel (Oynatıcı, Sorunlar, Anahat, İzleme) | aynı |
| Oyna | ayrı ekran | iki bölme yan yana sığmadığında rayda | yan panelin Oynatıcı sekmesi |
| Dosyalar | Dosyalar ekranı: dosyalar ve açık dosyanın anahattı (iki sekme) | aynı, yan yana | kenar çubuğunda projenin dosyaları; anahat yan panelde |
| Sorunlar, Projeler, Belgeler | "⋯" | rayda | kenar çubuğunda |
| Ayarlar | "⋯" | rayın altında | kenar çubuğunun altında |

Masaüstü Inky'nin menülerindeki komutlar geniş pencerede menü çubuğunda, tablette üst çubuğun "≡"
düğmesinde, telefonda "⋯" sayfasında (Hikâye, Dosya, Ink, Görünüm grupları; alt menüler sayfanın içinde
yeni bir sayfa olarak açılır) ve her düzende komut paletinde (Ctrl+K; telefonda üst çubuktaki arama)
durur. Yan panel Ctrl+Alt+B ile açılıp kapanır; kapalıyken düzenleyici bütün pencereyi alır
(masaüstündeki "odak" temasının karşılığı). Paneller arası ayraç sürüklenir, yeri saklanır.

## Proje açmak ve oluşturmak

Burada bir proje, bir klasör ve içindeki ana ink dosyasıdır; klasördeki bütün `.ink` dosyaları projeye
aittir (masaüstündeki gibi). Projeler ekranı (telefonda "⋯" › Projeler) hepsinin yeridir:

- **Yeni proje** (Ctrl+N): ad ve yer sorulur: Ayarlar'da seçilen projeler klasörü, başka bir klasör ya da
  uygulamanın içi. Ana dosya projenin adını alır ve masaüstündeki yeni proje metniyle ("Once upon a
  time...") başlar.
- **Klasör aç** (Ctrl+O): sistemin klasör seçicisi. Klasörde başka hiçbir dosyanın `INCLUDE` etmediği
  birden çok dosya varsa hangisinin ana dosya olduğu sorulur ve klasör için hatırlanır; ink dosyası yoksa
  orada yeni bir proje başlatmak önerilir.
- **Dosya aç** (Ctrl+Shift+O) ya da bir dosya yöneticisinde bir `.ink` dosyasına dokunmak: dosyanın
  klasörüne daha önce erişim verildiyse proje o klasörle açılır. Verilmediyse yalnızca o dosya açılır
  ve üstte bir şerit klasöre erişim vermeyi önerir ("Erişim ver"); verilince `INCLUDE` dosyaları
  yüklenir, yenileri eklenebilir.
- **Son projeler**: son beşi; birine dokununca açılır, satırın menüsünden listeden çıkarılır, "Listeyi
  temizle" hepsini siler. Masaüstündeki File › Open Recent gibi Dosya › Son projeler de bir alt menüdür:
  son beş proje (açık olan işaretli) ve "Listeyi temizle"; telefonda "⋯" sayfasında bir sayfa olarak açılır.
  Klasörü artık yerinde olmayan ya da erişim izni geri alınmış projeler, Projeler ekranı açılınca
  listeden kendiliğinden çıkar.
- **Inky içinde**: uygulamanın kendi depolamasındaki projeler. İzin istemez, hemen açılır; satırın
  menüsünden bir klasöre kopyalanır ya da silinir. İlk açılışta "İlk hikâye" adlı böyle bir proje açılır.
- **Projeyi kapat** (Ctrl+W). Bir proje aynı anda tek pencerede açıktır; DeX'te ikinci bir pencere
  (Ctrl+Shift+N) başka bir proje açar.

Uygulama kapatılıp açıldığında son proje, açık dosya, imleç ve yan panelin sekmesi geri gelir.

## Yazmak

- Düzenleyici ink'i masaüstü Inky'nin kurallarıyla renklendirir: düğüm ve dikiş satırları başlık gibi,
  yönlendirme hedefleri ve `INCLUDE` yolları bağlantı renginde, yorumlar ve etiketler sessiz. Satır
  numaraları, uzun satırların kaydırılması ve yazı tipi (eş aralıklı ya da orantılı) Ayarlar'da.
- **Kaydetmek:** otomatik kaydetme açıkken (varsayılan) değişiklikler yazmayı bıraktıktan iki saniye sonra
  ve uygulama arka plana geçince kaydedilir. Ctrl+S (Dosya › Projeyi kaydet) her zaman bütün projeyi
  kaydeder, önce ana dosyayı. Kaydedilmemiş metin ayrıca yazmayı bıraktıktan bir saniye sonra
  uygulamanın içine taslak olarak yazılır: uygulama ya da telefon kapansa bile bir sonraki açılışta geri
  gelir; bir şerit kaç dosyanın kaydedilmemiş değişikliklerinin geri getirildiğini söyler. Kaydedilemeyen dosya bir şeritte nedeniyle söylenir, "Yeniden dene"
  düğmesiyle; metin bu arada cihazda saklı kalır.
- **Otomatik kaydetme kapalıyken** masaüstündeki gibidir: kaydedilmemiş dosyalar işaretlenir, projeyi
  kapatırken "Kaydet / Kaydetme / Vazgeç" sorulur.
- **Bul ve değiştir:** Ctrl+F, Ctrl+H (telefonda üst çubuğun menüsündeki Bul). Büyük/küçük harf, tam
  sözcük ve düzenli ifade; tümünü değiştirmek tek adımda geri alınır.
- **Yorum satırı yap / kaldır** (Alt+/; Düzen menüsü): seçimin dokunduğu satırlar `// ` ile başlar ya da
  başlamaz olur; tek adımda geri alınır. Masaüstündeki blok yorumu (`/* */`) yok.
- **Otomatik tamamlama:** masaüstündekiyle aynı öneriler (yönlendirme hedefleri, değişkenler, projenin
  sözcükleri, ink'in anahtar sözcükleri, bütün dosyalardan); yazarken açılır, Ctrl+Space ile istenir.
  Görünüm › Otomatik tamamlama ya da Ayarlar'dan kapatılır.
- **Tanıma git:** bir yönlendirme hedefinin ya da `INCLUDE` yolunun üstünde Ctrl+tıklama veya
  Alt+tıklama (masaüstündeki gibi), F12; dokunmatikte uzun basınca açılan menüde "Tanıma git". Hedef
  başka dosyadaysa o dosya açılır.
- **Sorunlar düzenleyicide:** hata, uyarı ve TODO satırlarının kenarında işareti, satırda hafif bir
  rengi; işarete dokununca ya da fareyle üstünde durunca iletisi görünür.
- **Telefonda ink yazmak:** ekran klavyesi açıkken hemen üstünde ince bir şerit `->`, `*`, `+`, `-`, `=`,
  `{ }`, `|`, `#`, `~`, `<>`, `//`, `( )`, `[ ]`, sekme, geri al ve yinele sunar; ikili işaretler imleci
  arasına koyar. Donanım klavyesi bağlıyken görünmez.
- **Yakınlaştırma:** Ctrl+= ve Ctrl+- düzenleyiciyi ve oynatıcıyı birlikte büyütür (masaüstündeki gibi),
  Ctrl+0 temanın boyuna döner. Düzenleyicide iki parmakla kıstırmak ya da Ctrl ile tekerlek yalnızca
  düzenleyiciyi büyütür.
- Geri al, yinele, kes, kopyala, yapıştır, tümünü seç: düzenleyicinin kendi tuşları ve menüsü; telefonda
  üst çubukta.

## Dosyalar ve INCLUDE

- Dosyalar ekranı (DeX'te kenar çubuğu) masaüstündeki dosya paneliyle aynı düzendedir: ana dosya, sonra
  `INCLUDE` edilen dosyalar klasörlerine göre, sonra hiçbir `INCLUDE`'un ulaşmadığı "Kullanılmayan
  dosyalar". Kaydedilmemiş dosyalar ve sorun sayıları işaretlidir; dokunulan dosya düzenleyicide açılır.
- **Yeni ink dosyası ekle** (Ctrl+Alt+N; telefonda Dosyalar ekranının ana düğmesi): ad ve "Ana ink
  dosyasına ekle" (açıkken ana dosyaya `INCLUDE` satırı yazılır, son `INCLUDE`'un altına ya da en başa).
  Ad `.ink` ile bitmiyorsa eklenir; var olan bir dosyanın adı kabul edilmez.
- `INCLUDE` satırı yazmak da yeter: dosya klasörde varsa yüklenir. Yoksa "Var olmayan INCLUDE dosyaları"
  altında listelenir; oradan oluşturulabilir (kendiliğinden oluşturulmaz, masaüstündeki gibi).
- **Yeniden adlandırmak ve silmek** (masaüstünde yok): dosyanın satırındaki menüden (sağ tık, uzun basma,
  kenar çubuğunda girişin menüsü). Yeniden adlandırınca bu dosyayı ekleyen `INCLUDE` satırları da yeniden
  yazılır, dosya klasöründe kalır. Silmeden önce sorulur; silinen dosya bildirimdeki "Geri al" ile geri
  gelir. Ana dosya ikisine de kapalıdır.
- **Dışarıda değişen dosyalar:** pencere öne gelince ve Dosya › Depolamadan yeniden yükle (F5) ile
  bakılır. Kaydedilmemiş değişikliği olmayan dosya sessizce yenilenir (imleç ve kaydırma yerinde kalır);
  olan dosyada "Benimkini koru / Depolamadakini yükle" diye sorulur; seçilene kadar hiçbirinin üstüne
  yazılmaz. Hangisi seçilirse seçilsin, yerine başkası geçen metin Dosya › Saklanan sürümler'de durur
  ("Benimkini koru"da depolamadaki sürüm; "Depolamadakini yükle"de penceredeki metin, Geri al onu geri
  getirebildiği için depolamadaki de); oradan dosyasına geri getirilir ya da unutulur. Klasöre gelen yeni
  `.ink` dosyaları projeye girer, silinenler çıkar.
- **Proje ayar dosyası** (`<ana dosya>.settings.json`, masaüstündeki gibi): özel parçacıklar
  (`customInkSnippets`) ve komut öneki (`instructionPrefix`). Dosyada bir hata varsa bir şerit söyler.

## Oynatmak

- Hikâye yazdıkça oynar: yazmayı bıraktıktan yarım saniye sonra yeniden derlenir ve şimdiye kadar
  yapılan seçimlerle baştan oynatılır; oynatıcı titremeden yeni hâline geçer, kaydırma yerinde kalır.
  Artık olmayan bir seçimde oynatma orada durur ve o turun seçenekleri gösterilir.
- Seçenekler en alttaki satırlardır; dokununca ya da tıklayınca seçilir. Turların arasında bir çizgi,
  hikâye bitince "Hikâyenin sonu".
- **Bir seçim geri al** (Ctrl+R) ve **hikâyeyi baştan başlat** (Ctrl+Shift+R): telefonda Oyna ekranının
  üst çubuğunda, genişte oynatıcının başında (masaüstünün araç çubuğundaki iki düğme).
- **Metnin kaynağı:** bir paragrafa dokununca onu üreten ink satırı açılır. Fareyle Ctrl ya da Alt basılı
  tıklanır; basılıyken imlecin altındaki sözcüğün altı çizilir (masaüstündeki Alt+tıklama).
- Etiketler satırın altında `# …` olarak görünür; Hikâye › Etiketleri göster (ya da Ayarlar) gizler.
  Komut önekiyle başlayan satırlar ayrı bir biçimde yazılır.
- Ink metninde hata varken oynatıcı derlenebilen son hikâyeyi göstermeye devam eder ve bunu söyler;
  "Sorunları göster" sorunlara götürür. Çalışma hataları oynatıcıda satır numarasıyla, dokununca o
  satıra götüren bağlantı olarak görünür.
- Sonu gelmeyen bir döngü uygulamayı kilitlemez: oynatıcı bir yerde durdurulduğunu ve nedenini söyler.
- Yeni metnin yavaşça belirmesi Görünüm › Oynatıcı animasyonu ya da Ayarlar'dan kapatılır; sistemde
  "Animasyonları kaldır" açıksa hiçbir şey hareket etmez.

## İzleme ifadeleri

Her turdan sonra değerlendirilen ink metinleri (örneğin `x is {x}`); sonuçları oynatıcıda, turun
sonunda görünür. Hikâye › İzleme ifadesi ekle; genişte yan panelin İzleme sekmesi (Alt+4), telefonda Oyna
ekranının üst çubuğundaki göz simgesi. Her biri düzenlenir ve kaldırılır. İşlev çağrıları da çalışır.

## Sorunlar

Hatalar, uyarılar ve TODO notları dosyalarına göre, masaüstündeki sırayla. Genişte yan panelin Sorunlar
sekmesi (Alt+2), telefonda "⋯" › Sorunlar ya da düzenleyicinin üst çubuğundaki sorun simgesi (yalnızca
sorun varken görünür); rayda ve kenar çubuğunda sayı rozeti. Bir soruna dokununca dosyası o satırda açılır. Hikâye › Sonraki sorun (Ctrl+.)
sırayla dolaşır.

## Gezinmek

- **Her şeye git** (Ctrl+P; Ctrl+K da açar): dosyalar, düğümler ve dikişler, bütün projede metin
  satırları, ekranlar ve komutlar tek aramada. Bir sayı yazmak o satırı ilk sıraya koyar (Ctrl+G);
  `düğüm.0.c-1` gibi bir çalışma yolu seçilince ink'teki yerine gidilir.
- **Anahat:** açık dosyanın düğümleri ve dikişleri, işlevleri ve dış işlevleri ayrı; imlecin olduğu yer
  işaretli ve görünür kalır, bir satıra dokununca oraya gidilir. Genişte yan panelin Anahat sekmesi
  (Alt+3), telefonda Dosyalar ekranında.
- **Geri ve ileri** (Alt+←, Alt+→): gezilen dosyalar ve satırlar arasında, masaüstündeki kurallarla.

## Parçacıklar

Masaüstünün Ink menüsündeki hazır ink parçaları, adları Türkçe (ink'in kendi adları olduğu gibi).

- **DeX, büyük pencere ve tablet:** Ink menüsü (tablette üst çubuğun "≡" düğmesinde): masaüstündeki
  kategoriler sırasıyla ve aralarındaki çizgilerle, her biri parçacıklarının alt menüsü.
- **Telefonda:** "⋯" › Ink › Parçacık ekle; kategoriler aynı sayfanın içinde yeni sayfalar olarak açılır.
- **Her düzende:** komut paletinde "Parçacıklar" grubu; adı Türkçe ya da İngilizce yazılarak bulunur,
  kategorisi altında yazar. Telefonda düzenleyicinin üst çubuğunun menüsündeki "Parçacık ekle…" paleti bu
  grupla açar.
- Seçilen parçacık imlecin olduğu yere masaüstündeki gibi olduğu gibi yazılır; seçim varsa onun yerine
  geçer; tek adımda geri alınır.
- **Projenin kendi parçacıkları:** proje ayar dosyasındaki `customInkSnippets`, Ink menüsünün sonunda bir
  çizgiden sonra, ayar dosyasında yazıldığı gibi iç içe (alt menüler, ayraçlar) ve `accelerator` ile
  verilen tuşlarıyla; tuşlar donanım klavyesiyle çalışır. Palette de listelenirler.

## Belgeler: ink kılavuzu

inkle'ın *Writing with ink* kılavuzu uygulamanın içinde gelir; internet gerekmez. Masaüstündeki
Yardım › Show Documentation (F1) penceresinin karşılığıdır; burada da F1 açar.

- **Telefonda:** "⋯" › Belgeler. Üst çubukta İçindekiler (liste simgesi), Bul (büyüteç) ve "⋮" altında
  yazıyı büyütme ve küçültme. İçindekiler alttan açılan bir sayfadır; bir başlığa dokununca oraya gidilir.
- **Tablette:** raydaki Belgeler. Tablet genişliğinden itibaren içindekiler metnin solunda durur; aradaki
  ayraç sürüklenerek genişletilir, yeri saklanır.
- **DeX'te:** kenar çubuğundaki Belgeler. Bul alanı, önceki ve sonraki, − ve + başlığın sağındadır.
- Okurken içindekilerde bulunduğunuz bölüm işaretlenir ve görünür kalır.
- **Bul:** yazdıkça bütün kılavuzdaki geçişler işaretlenir ve "3 / 27" gibi sayılır. Enter ya da F3
  sonrakine, Shift+Enter ya da Shift+F3 öncekine gider; sondan başa döner. Ctrl+F alanı açar; telefonda
  boş alanda Esc kapatır.
- **Yazı boyu:** düzenleyicininkiyle aynı yakınlaştırma: Ctrl ile + ve −, Ctrl+0 temanın boyu, Ctrl ile
  tekerlek, iki parmakla kıstırmak; genişte − ve + düğmeleri. Satır uzunluğu yazıyla birlikte büyür,
  okuma rahatlığı korunur. Kılavuzun boyu düzenleyicininkinden ayrı saklanır.
- **Örnekler:** kılavuzdaki her ink örneği düzenleyicinin renkleriyle bir kutudadır. Kopyala düğmesi
  örneği sekmeleriyle birlikte panoya alır; tablette ve DeX'te bir proje açıkken "Düzenleyiciye ekle"
  düğmesi onu imlecin olduğu yere yazar (parçacıkların eklendiği gibi). Sağ tık ya da uzun basma aynı iki
  işi menüyle sunar.
- Kılavuzun içindeki bağlantılar ilgili bölüme gider, dışarıya gidenler tarayıcıda açılır. Metin seçilip
  kopyalanabilir.
- Kılavuz her açıldığında kalınan yerden başlar.

## Proje istatistikleri

Masaüstündeki Story › Word count and more (Ctrl+Shift+C); burada Hikâye › Proje istatistikleri
(Ctrl+Shift+C), telefonda "⋯" altında. Sözcük, düğüm, dikiş, işlev, seçenek, birleşme ve yönlendirme sayılarını ink
derleyicisinin saydığı gibi, masaüstündeki notla birlikte gösterir: düğümlere işlevler dahildir;
birleşmelere ve yönlendirmelere derleyicinin kendiliğinden eklediği bazıları girebilir; END ve DONE da
yönlendirme sayılır. Hatalı ink için de sayar.

## Dışa aktarma

Masaüstündeki üç dışa aktarma, Dosya menüsünde (telefonda "⋯" › Dosya) ve komut paletinde:

| Komut | Ne yazar | Önerilen ad |
| --- | --- | --- |
| JSON olarak dışa aktar (Ctrl+Shift+S) | derlenmiş hikâye, ink'in çalışma zamanı JSON'u | `<ana dosya>.json` |
| Yalnızca story.js dışa aktar (Ctrl+Alt+S) | `var storyContent = …;` | `<ana dosya>.js`; ana dosya `main.ink` ise `story.js` |
| Web için dışa aktar | oynanabilir web sayfası: `index.html`, hikâyenin `.js` dosyası, `ink.js` (inkjs 2.4.0), `main.js`, `style.css` | ana dosyanın adında bir klasör |

- Ink'te hata varsa dışa aktarılmaz ve bunu bir bildirim söyler (masaüstündeki gibi).
- Dosya için sistemin "kaydet" penceresi, web sürümü için bir klasör seçme penceresi açılır; web sürümü
  seçilen klasörün içinde yeni bir klasöre yazılır, içinde aynı adlı dosyalar varsa üstüne yazılır.
  Seçici bir sonraki dışa aktarmada son seçilen yerde açılır.
- Web sayfasının başlığı ana dosyadaki `# title: …` etiketinden, yoksa klasörün adından gelir.
- Bittiğinde bir bildirim dosyanın ya da klasörün adını söyler; yazılamadıysa nedenini.

## Ayarlar

Telefonda "⋯" › Ayarlar, genişte kenar çubuğunun ya da rayın altında, Ctrl+, ile her yerden. Sırasıyla:

- **Görünüm:** tema (sistem, açık, koyu, OLED siyah) ve vurgu rengi. Masaüstünün dört teması yerine.
- **Giriş:** sıkı (fare ve klavye) ya da geniş (dokunmatik) düzen; kendiliğinden seçilir.
- **Düzenleyici:** yazı tipi (eş aralıklı, orantılı), uzun satırları kaydır, satır numaraları, otomatik
  tamamlama, klavye önerileri (ekran klavyesinin önerileri ve düzeltmeleri).
- **Oynatıcı:** oynatıcı animasyonu, etiketleri göster.
- **Dosyalar:** otomatik kaydet; projeler klasörü (yeni projelerin yeri: seç, değiştir ya da uygulamanın
  içinde tut).
- **Klavye kısayolları:** bütün liste; yalnızca fare ya da donanım klavyesi varken görünür.
- **Hakkında:** sürüm, ink'in sürümü, açık kaynak lisansları, GitHub'da Inky. Masaüstündeki About
  penceresinin karşılığı.

Görünüm menüsündeki otomatik tamamlama ve oynatıcı animasyonu, Hikâye menüsündeki etiketler buradaki
ayarların aynısıdır; biri değişince öteki de değişir ve bütün pencerelerde geçerlidir.

## Kısayollar

Masaüstü Inky'nin tuşları kitin ortak tuşlarıyla çakışmadıkça aynen korunmuştur. Bütün tablo
`KISAYOLLAR.md`'de; masaüstünden farklı olanlar şunlar:

| Komut | Burada | Masaüstü Inky |
| --- | --- | --- |
| Klasör aç | Ctrl+O | Ctrl+O bir dosya açar |
| Dosya aç | Ctrl+Shift+O | — |
| Depolamadan yeniden yükle | F5 | — (dosyalar sürekli izlenir) |
| Yeni pencere | Ctrl+Shift+N | — |
| Projeyi kapat | Ctrl+W | Ctrl+W pencereyi kapatır |
| Yinele | Ctrl+Shift+Z, Ctrl+Y | Ctrl+Shift+Z |
| Gerçek boyut | Ctrl+0 | — (yüzde menüsü) |
| Yan paneli göster / gizle | Ctrl+Alt+B | — |
| Oynatıcı, Sorunlar, Anahat, İzleme sekmeleri | Alt+1 … Alt+4 | — |
| Komut paleti, kenar çubuğu, ayarlar, kısayol listesi | Ctrl+K, Ctrl+B, Ctrl+,, Ctrl+/ | — (kitin ortak tuşları) |
| Tanıma git | F12; Ctrl+tıklama, Alt+tıklama | Alt+tıklama |
| Geri, ileri | Alt+←, Alt+→ | yalnızca araç çubuğu düğmeleri |
| Bir seçim geri al | Ctrl+R | yalnızca düğme (Ctrl+R geliştiricinin "yeniden yükle"siydi) |
| Hikâyeyi baştan başlat | Ctrl+Shift+R | yalnızca düğme |
| Satıra git | Ctrl+G her şeye git'i açar | Ctrl+G Ace'in satıra git kutusu |
| Yorum satırı yap / kaldır | Alt+/ (yalnızca satır yorumu `// `; blok yorumu yok) | Ctrl+/ (Ctrl+/ burada kısayol listesidir) |
| Kılavuzda bul, sonraki, önceki | Ctrl+F, F3, Shift+F3 (Belgeler'de) | — (kılavuzda arama yoktu) |
| Tam ekran | — | F11 |
| Pencere ve geliştirici menüleri | — | Ctrl+M, Ctrl+Shift+I … |

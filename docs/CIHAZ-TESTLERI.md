# Cihazda denenecekler

Uygulamanın testleri bilgisayarda (Robolectric) ve emülatörde koşar; aşağıdakileri ancak gerçek bir cihaz
gösterir. Her madde: ne yapılacağı, doğrusunun neye benzediği ve bilinen bir sınırsa bunun açıkça
söylenmesi. **S24** Galaxy S24 (üç düğmeli gezinme), **Tab** Galaxy Tab S11 Ultra, **DeX** tabletin DeX
masaüstü (klavye ve fare). Menü adları uygulamadaki gibidir; DeX'te menü çubuğunda, tablette üst çubuğun
"≡" düğmesinde, telefonda "⋯" sayfasında durur.

Bir madde tutmazsa ekran görüntüsü ve ne yapıldığı yeter; uygulamanın kendi günlüğü yok, Android'in hata
raporu (Ayarlar › Geliştirici seçenekleri › Hata raporu) işe yarar.

## Depolama

1. **Cihazın kendi depolaması ve SD kart** (S24 ya da Tab).
   Dene: Ayarlar › Dosyalar › Projeler klasörü › Seç… ile `Documents/Inky`'yi seç; Projeler › Yeni proje…
   ile bir proje oluştur, bir şey yaz, kaydet (Ctrl+S ya da iki saniye bekle); Dosya › Yeni ink dosyası
   ekle… ile `bolumler/ilk.ink` ekle; bu dosyayı yeniden adlandır, sonra sil. Aynısını SD karttaki bir
   klasörde tekrarla.
   Doğrusu: Samsung Dosyalarım'da dosyalar yazdığın adlarla durur; `.ink` ve dışa aktarılan `.json`
   dosyalarının sonuna `.txt` eklenmemiştir; silinen dosya klasörden de gider.
2. **Bulut klasörleri** (Google Drive, OneDrive, Samsung Cloud).
   Dene: buluttaki bir klasörü Projeler › Klasör aç… ile aç, birkaç kez yazıp kaydet, uygulamadan çıkıp
   dön, Dosya › Depolamadan yeniden yükle (F5).
   Doğrusu: kaydetmek dosyayı yerinde değiştirir (kopyalar ya da çöpte dosyalar birikmez); kendi
   kaydettiğin dosya "Inky dışında değişti" diye işaretlenmez, "Benimkini koru / Depolamadakini yükle"
   sorusu çıkmaz.
   Bilinen sınır: bazı bulut hizmetleri (çoğu zaman Google Drive) sistemin klasör seçicisinde klasör
   seçtirmez; o hizmette bu madde geçilir, oradan yalnızca tek dosya açılabilir.
3. **Dosya yöneticisinden açmak** (S24).
   Dene: Samsung Dosyalarım, Files by Google ve Drive'da bir `.ink` dosyasına dokun.
   Doğrusu: açacak uygulamalar arasında Inky var; dosya adıyla açılır. Salt okunur paylaşılmışsa üstte
   "Bu dosya salt okunur paylaşıldı. Değişiklikleri kaydetmek için klasörüne erişim verin." şeridi çıkar;
   "Erişim ver"in açtığı klasör seçici dosyanın kendi klasöründe başlar.
4. **Erişim verilmiş bir klasördeki dosyayı açmak.**
   Dene: bir klasörü Klasör aç… ile aç; sonra Dosya aç… ile aynı klasördeki bir `.ink` dosyasını seç.
   Doğrusu: proje klasörüyle açılır: Dosyalar'da öteki dosyalar da görünür, "Yalnızca bu dosya açık"
   şeridi çıkmaz.
5. **İzinler yeniden başlatmadan sonra ve izin gidince.**
   Dene: telefonu yeniden başlat, Inky'yi aç. Sonra Dosyalarım'da bir projenin klasörünün adını değiştir ve
   Inky'de o projeyi Projeler'den açmaya çalış.
   Doğrusu: yeniden başlatmadan sonra son proje izin sormadan açılır. Adı değişen klasör için "Inky bu
   klasörü artık kullanamıyor…" ya da "Dosya ya da klasör artık yerinde değil." denir; Projeler ekranı
   açılınca o proje Son projeler'den çıkar.
6. **Proje açıkken SD kartı çıkarmak.**
   Dene: SD karttaki bir proje açıkken kartı çıkar (ya da Ayarlar'dan çıkar), bir şey yaz.
   Doğrusu: kaydedilemediği bir şeritte söylenir; yazdığın metin kaybolmaz, cihazda taslak olarak durur.
   Bilinen sınır: kart takılı değilken Projeler ekranını açarsan proje Son projeler'den düşebilir;
   kartı geri takınca Klasör aç… ile yeniden açılır (taslaklar geri gelir). Ayrıca 27. madde.
7. **FAT ya da exFAT biçimli kartta büyük/küçük harf.**
   Dene: kartta `Ana.ink` varken Yeni ink dosyası ekle… ile `ana.ink` eklemeye ya da bir dosyayı böyle
   yeniden adlandırmaya çalış.
   Doğrusu: açık bir hata iletisi (örneğin "Bu adda bir dosya zaten var." ya da "Depolama isteği yerine
   getirmedi. Yeniden deneyin."); var olan dosyanın üstüne yazılmaz.

## Metin

8. **Renkler cihazda.**
   Dene: Ink › Tam hikâyeler › The Intercept'i boş bir dosyaya ekle ve baştan sona kaydır.
   Doğrusu: renkler masaüstü Inky'deki yerlere düşer: düğüm ve dikiş satırları başlık gibi, `->`
   hedefleri bağlantı renginde, yorumlar ve etiketler sessiz; renksiz kalan ya da yarısından renk değiştiren
   bir satır yoktur. Android'in düzenli ifade motoru bilgisayarınkinden farklı olduğu için bu ancak
   cihazda görülür.

## Yalnız cihazda

9. **DeX'in kendisi** (DeX).
   Dene: Inky'yi aç, pencereyi fareyle daralt ve genişlet (kenar çubuğu, ray, alt çubuk düzenleri), Dosya ›
   Yeni pencere ile ikinci bir pencere aç; Samsung Klavye, kendi klavyen ve faren; sağ tık ve tuşlar.
   Doğrusu: düzen pencerenin genişliğiyle değişir, açık dosya, imleç, kaydırma yeri ve oynatıcı yerinde
   kalır; sağ tık menüleri ve kısayollar çalışır; yazarken takılma yoktur (emülatör yazılımla çizdiği
   için hız yalnızca cihazda ölçülür).
10. **Bakmaya değer görünüş.**
    Dene: telefonu yan çevir; tableti dik tut.
    Doğrusu: yan çevrilmiş telefonda ray ve iki bölme yan yana. Bilinen sınır: kamera kesiğinin yanındaki
    şerit sayfanın renginde, ray ise yüzeyin renginde (28. madde); gezinme çubuğunun arkasındaki şerit
    (telefon yan dururken, tablet dik dururken) içerikten başka renkte olabilir. Bu sürümde böyle.

## Alt menüler

11. **Telefonda yan yana açılan alt menüler** (S24, yan çevrilmiş).
    Dene: telefon yan dururken üst çubuğun "≡" düğmesinden Ink › bir kategori, ve Dosya › Son projeler.
    Doğrusu: alt menü okunur, ekrandan taşmaz, seçilen parçacık eklenir. Dar ekranda alt menü ana menünün
    üstüne binebilir; okunduğu ve dokunulduğu sürece doğrudur. Telefon dik dururken alt menüler "⋯"
    sayfasının içinde sayfa olarak açılır (orada binme olmaz).
12. **İletişim kutusunun içindeki alt menüler.**
    Bilinen sınır: bir iletişim kutusunun ya da ayrı bir sayfanın içinden açılan alt menü o kutunun
    penceresiyle sınırlıdır, garip bir yerde durabilir. Bu sürümde böyle bir menü yok; ileride görürsen
    not et.

## Arayüz

13. **Ink şeridi** (S24, Samsung Klavye).
    Dene: düzenleyicide yazarken klavyenin hemen üstündeki şeritte `->`, `{ }`, `[ ]`, sekme, geri al.
    Doğrusu: şerit klavyenin üstünde durur, her dokunuş işaretini ekler, `{ }` imleci ayraçların arasına
    koyar, klavye kapanmaz. Donanım klavyesi bağlıyken şerit görünmez.
14. **Ctrl+Space** (DeX).
    Dene: düzenleyicide Ctrl+Space.
    Doğrusu: öneri listesi açılır. Bilinen sınır: Android bu tuşu klavye dili değiştirmeye ayırmışsa liste
    açılmaz; o zaman Düzen › Önerileri göster.
15. **Yazarken öneriler, Türkçe otomatik düzeltme açıkken** (S24).
    Dene: Samsung Klavye'nin otomatik düzeltmesi açıkken bir düğümün adının ilk harflerini yaz, bir
    öneri seç; sonra öneri listesi açıkken Enter'a bas.
    Doğrusu: liste açılır, seçilen öneri sözcüğü tamamlar ve klavyenin düzeltmesi onu bozmaz; tek öneri
    yazdığın sözcüğün kendisiyse Enter yeni satıra geçer.
16. **Uzun basınca "Tanıma git"** (S24).
    Dene: bir `-> hedef` sözcüğüne uzun bas.
    Doğrusu: seçimin menüsünde "Tanıma git" çıkar; dokununca hedefin tanımı açılır, başka dosyadaysa o
    dosya.
17. **İki parmakla yakınlaştırma.**
    Dene: düzenleyicide ve oynatıcıda iki parmakla aç ve kıs.
    Doğrusu: yazı parmakları izler, bırakınca boyut kalır ve uygulama kapanıp açılsa da saklanır.
18. **Oynatıcıda fare** (DeX).
    Dene: Ctrl ya da Alt basılıyken fareyi oynatıcıdaki metnin üstünde gezdir ve tıkla.
    Doğrusu: imlecin altındaki sözcüğün altı çizilir, el imleci çıkar; tıklayınca o metni üreten ink
    satırı açılır. Ctrl ya da Alt'sız tıklamak yalnızca metni seçer.
19. **Sistemin seçicileri.**
    Dene: Klasör aç…, Dosya aç…, tek dosyada "Erişim ver", üç dışa aktarma (24. madde).
    Doğrusu: seçiciler açılır, seçilen yer kullanılır; vazgeçince hiçbir şey olmaz. İzin gidince 5. maddedeki
    iletiler.
20. **Dosyalarım'dan bir projenin dosyasını açmak** (S24).
    Dene: daha önce Klasör aç… ile açtığın bir projenin ana dosyasına Dosyalarım'da dokun.
    Doğrusu: Inky açılır ve proje bütün dosyalarıyla açılır (4. madde gibi).
21. **Yeni pencere, pencere başına bir proje** (DeX).
    Dene: Dosya › Yeni pencere; ikinci pencerede birincide açık olan projeyi Son projeler'den açmaya çalış,
    sonra başka bir proje aç.
    Doğrusu: ikinci pencere "Bu proje başka bir pencerede açık" der ve Projeler'e götürür; başka bir proje
    sorunsuz açılır; iki pencere birbirinin metnine dokunmaz.
22. **Sistem uygulamayı kapattıktan sonra.**
    Dene: Ayarlar › Dosyalar › Otomatik kaydet'i kapat, bir şey yaz, kaydetmeden ana ekrana dön; Android
    Ayarlar › Uygulamalar › Inky › Durmaya zorla; Inky'yi yeniden aç. Sonra otomatik kaydetmeyi geri aç.
    Doğrusu: proje, açık dosya ve imleç yerinde; kaydedilmemiş metin geri gelmiştir ve "1 dosyanın
    kaydedilmemiş değişiklikleri bu cihazdan geri getirildi." şeridi çıkar.
23. **Hız: uzun bir hikâye** (S24).
    Dene: yeni bir projenin ana dosyasına Ink › Tam hikâyeler › The Intercept'i ekle (yaklaşık 1.700
    satır); yaz, hızlı kaydır, oynatıcıda birkaç seçim yap.
    Doğrusu: harfler gecikmeden görünür, kaydırma akar; yazmayı bıraktıktan yaklaşık yarım saniye sonra
    oynatıcı yenilenir ve bu sırada yazmak bekletilmez.

## Ekstralar

24. **Dışa aktarma, gerçek seçicilerle.**
    Dene: Dosya › JSON olarak dışa aktar…, Yalnızca story.js dışa aktar…, Web için dışa aktar…; ikinci
    dışa aktarmada seçicinin nerede açıldığına bak. Web klasörünü bir bilgisayara kopyalayıp `index.html`'i
    tarayıcıda aç.
    Doğrusu: önerilen adlar `<ana dosya>.json` ve `<ana dosya>.js` (ana dosya `main.ink` ise `story.js`);
    bildirim yazılan dosyanın ya da klasörün adını söyler; ikinci seçici son yerde açılır; bilgisayardaki
    tarayıcıda sayfa hikâyeyi oynatır.
    Bilinen sınır: bazı seçiciler başlangıç yerini dikkate almaz; telefonun tarayıcısı klasördeki sayfayı
    çoğu zaman oynatamaz (betiklerini yükleyemez), bu yüzden bilgisayarda dene.
25. **Belgeler.**
    Dene: bir örneğin Kopyala düğmesi (sonra düzenleyiciye yapıştır); dışarıya giden bir bağlantı
    ("Running Your Ink"); kılavuzda iki parmakla, DeX'te Ctrl ile tekerlekle yakınlaştırma; Samsung Klavye
    ile Bul alanına yazma; DeX'te Belgeler ve Ayarlar açıkken pencereyi boyutlandırma.
    Doğrusu: yapıştırılan örnek kılavuzdakinin aynısı (sekmeleriyle); bağlantı tarayıcıda açılır; yazı büyür
    ve satırlar yeniden dizilir; Bul eşleşmeleri sayar ve işaretler; içindekiler geniş pencerede yanda,
    darda alttan açılan bir sayfada.

## Veri katmanı notları

26. **Aynı klasör, iki ayrı izin.**
    Bilinen sınır: bir klasör bir kez kendisi, bir kez de onu içeren üst klasör seçilerek açılırsa Inky
    onu iki ayrı proje sayar: Son projeler'de iki kez görünür ve iki pencerede birden açılabilir; ikisine
    birden yazmak birbirinin değişikliğini ezebilir. Bir projeyi hep aynı yoldan aç.
27. **Bir an çıkarılan SD kart ya da USB bellek.**
    Bilinen sınır: kart ya da bellek kısa bir süre çıkarılıp takılınca Android klasörün iznini geri
    alabilir; proje açılırken "Inky bu klasörü artık kullanamıyor…" denir. Klasörü Klasör aç… ile yeniden
    seç; yazılanlar taslak olarak saklanmıştır.

## Emülatörde görülen bilinen sınırlar

28. **Telefon yan dururken kamera kesiğinin yanındaki şerit** rayın değil sayfanın renginde. Kitin
    kabuğundan; bu sürümde böyle.
29. **ink derleyicisinin iletileri** Türkçe arayüzde de İngilizce ("Divert target not found…"),
    masaüstü Inky'deki gibi. Sorunlar'da iletiye dokununca satırı açılır.

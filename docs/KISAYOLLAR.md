# Klavye kısayolları

Donanım klavyesiyle (DeX, klavye kapağı, Bluetooth klavye) bütün komutlar tuşla da çalışır. Aynı tablo menü
çubuğunda, "⋯" sayfasında, komut paletinde (Ctrl+K), Ayarlar'daki kısayol listesinde ve sistemin Meta+/ listesinde
görünür. Masaüstü Inky'nin tuşları, kitin ortak tuşlarıyla (Ctrl+K, Ctrl+, Ctrl+B, Ctrl+/) çakışmadıkça aynen
korunmuştur; çakışanlar ve yeni eklenenler aşağıda işaretli.

## Dosya

| Komut | Bu uygulama | Masaüstü Inky | Not |
| --- | --- | --- | --- |
| Yeni proje | Ctrl+N | Ctrl+N | |
| Yeni ink dosyası ekle | Ctrl+Alt+N | Ctrl+Alt+N | |
| Klasör aç | Ctrl+O | Ctrl+O (dosya açar) | Burada bir proje bir klasördür; tek dosya Ctrl+Shift+O ile açılır. |
| Dosya aç | Ctrl+Shift+O | — | yeni |
| Projeyi kaydet | Ctrl+S | Ctrl+S | Otomatik kaydetme açıkken de çalışır. |
| Depolamadan yeniden yükle | F5 | — | yeni: dışarıda değişen dosyalar alınır |
| JSON olarak dışa aktar | Ctrl+Shift+S | Ctrl+Shift+S | |
| Yalnızca story.js dışa aktar | Ctrl+Alt+S | Ctrl+Alt+S | |
| Web için dışa aktar | — | — | Menüde ve komut paletinde. |
| Yeni pencere | Ctrl+Shift+N | — | kitin ortak komutu; Ctrl+N yeni proje olarak kaldı |
| Projeyi kapat | Ctrl+W | Ctrl+W (pencereyi kapatır) | |

## Düzen

| Komut | Bu uygulama | Masaüstü Inky | Not |
| --- | --- | --- | --- |
| Geri al, yinele | Ctrl+Z, Ctrl+Shift+Z | aynı | Ctrl+Y de yineler. Düzenleyicinin kendi tuşları. |
| Kes, kopyala, yapıştır, tümünü seç | Ctrl+X, Ctrl+C, Ctrl+V, Ctrl+A | aynı | düzenleyicinin kendi tuşları |
| Girintile, girintiyi azalt | Tab, Shift+Tab | aynı | Upstream gibi dört boşluk. |

## Görünüm

| Komut | Bu uygulama | Masaüstü Inky | Not |
| --- | --- | --- | --- |
| Yakınlaştır, uzaklaştır | Ctrl+=, Ctrl+- | aynı | Düzenleyiciyi ve oynatıcıyı birlikte büyütür, upstream gibi. Düzenleyicide iki parmakla kıstırmak ve Ctrl ile tekerlek yalnızca düzenleyiciyi büyütür. |
| Gerçek boyut | Ctrl+0 | — | yeni (kitin yakınlaştırması) |
| Yan paneli göster / gizle | Ctrl+Alt+B | — | yeni; Ctrl+B kitin kenar çubuğudur |
| Oynatıcı, Sorunlar, Anahat, İzleme | Alt+1, Alt+2, Alt+3, Alt+4 | — | yeni: yan panelin sekmeleri |
| Arama (komut paleti) | Ctrl+K | — | kitin ortak komutu |
| Kenar çubuğu | Ctrl+B | — | kitin ortak komutu |
| Ayarlar | Ctrl+, | — | kitin ortak komutu |
| Tam ekran | — | F11 | Android'de pencereyi sistem yönetir. |

## Hikâye

| Komut | Bu uygulama | Masaüstü Inky | Not |
| --- | --- | --- | --- |
| Her şeye git | Ctrl+P | Ctrl+P | Ctrl+K ile aynı paleti açar. |
| Satıra git | Ctrl+G | Ctrl+G | Her şeye git'i açar; bir satır numarası yazmak o satırı ilk sıraya koyar. |
| Tanıma git | F12; Ctrl+tıklama, Alt+tıklama | Alt+tıklama | Dokunmatikte uzun basınca çıkan menüde. |
| Geri, ileri (gezilen yerler) | Alt+←, Alt+→ | yalnızca araç çubuğu düğmeleri | yeni |
| Sonraki sorun | Ctrl+. | Ctrl+. | |
| Bir seçim geri al | Ctrl+R | yalnızca düğme | yeni; masaüstündeki Ctrl+R (pencereyi yeniden yükle) bir geliştirici aracıydı |
| Hikâyeyi baştan başlat | Ctrl+Shift+R | yalnızca düğme | yeni |
| Sözcük sayısı ve daha fazlası | Ctrl+Shift+C | Ctrl+Shift+C | |
| İzleme ifadesi ekle | — | — | Menüde ve komut paletinde. |

## Yardım

| Komut | Bu uygulama | Masaüstü Inky | Not |
| --- | --- | --- | --- |
| Belgeleri göster | F1 | F1 | |
| Klavye kısayolları | Ctrl+/ | Düzen › Useful Keyboard Shortcuts | kitin ortak komutu; Meta+/ sistemin listesini açar |

## Taşınmayanlar

- Pencere menüsü (simge durumuna küçült Ctrl+M, öne getir) ve geliştirici araçları (Ctrl+R yeniden yükle,
  Ctrl+Shift+I): Android'de pencereleri sistem yönetir, geliştirici araçlarının karşılığı yoktur.
- Ace'in çoklu imleç, katlama, makro ve satır taşıma tuşları: düzenleyici kitin kendi düzenleyicisidir
  (architecture.md 4.4).

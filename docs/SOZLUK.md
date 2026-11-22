# Sözlük

Uygulamanın Türkçe arayüzünde ink kavramlarının karşılıkları. Menüler, ekranlar, ayarlar, parçacık adları ve
bildirimler bu tabloya uyar; yeni bir metin yazarken buradaki karşılık kullanılır, yenisi gerekirse önce buraya
eklenir. ink'in kendi anahtar sözcükleri (`VAR`, `INCLUDE`, `END`, `TODO` …) çevrilmez.

## ink

| ink | Türkçe | Örnek |
| --- | --- | --- |
| knot | düğüm | `== istasyon ==` |
| stitch | dikiş | `= salon` (bir düğümün alt bölümü) |
| divert | yönlendirme | `-> istasyon` |
| divert target | yönlendirme hedefi | `istasyon.salon` |
| tunnel | tünel | `-> anı ->`, `->->` |
| thread | iplik | `<- arka_plan` |
| choice | seçenek | `* [Kapıyı aç]` |
| sticky choice | kalıcı seçenek | `+ [Bekle]` |
| fallback choice | yedek seçenek | `* -> son` |
| gather | birleşme | `- Sonunda tren geldi.` |
| weave | örgü | seçeneklerin ve birleşmelerin bütünü |
| label | işaret | `* (soru) "Neredesin?"` |
| glue | yapıştırıcı | `<>` |
| tag | etiket | `# yer: peron` |
| function | işlev | `== function topla(a, b) ==` |
| external function | dış işlev | `EXTERNAL ses_cal(ad)` |
| variable | değişken | `VAR saat = 23` |
| temporary variable | geçici değişken | `~ temp sayi = 0` |
| constant | sabit | `CONST EN_FAZLA = 3` |
| list | liste | `LIST ruh_hali = mutlu, (sakin)` |
| inline logic | satır içi mantık | `{bilet: Var.|Yok.}` |
| multi-line logic | çok satırlı mantık | `{ - saat > 23: … }` |
| sequence, cycle, shuffle | sıra, döngü, karışık | `{&bir|iki}`, `{~yazı|tura}` |
| instruction prefix | komut öneki | projenin `instructionPrefix` ayarı, örneğin `>>>` |

## Inky

| Inky | Türkçe | Not |
| --- | --- | --- |
| project | proje | bir klasör ve içindeki ana ink dosyası |
| main ink file | ana ink dosyası | |
| include, included file | dahil edilen dosya | `INCLUDE` satırı ile |
| new included ink file | yeni ink dosyası ekle | upstream'in "Add new include" formu |
| unused files | kullanılmayan dosyalar | hiçbir `INCLUDE`'un ulaşmadığı dosyalar |
| unsaved changes | kaydedilmemiş değişiklikler | |
| editor | düzenleyici | |
| player, play view | oynatıcı | |
| turn | tur | bir seçimden ötekine kadar olan bölüm |
| issue | sorun | |
| error, warning | hata, uyarı | `TODO` notları adıyla kalır |
| runtime error | çalışma hatası | hikâye oynarken çıkan hata |
| knots view, outline | anahat | |
| watch expression | izleme ifadesi | her turdan sonra değerlendirilir |
| snippet | parçacık | Ink menüsündeki hazır ink parçaları |
| Go to anything | her şeye git | Ctrl+P, Ctrl+K |
| go to definition | tanıma git | upstream'in Alt+tıklaması |
| step back, restart | bir seçim geri al, hikâyeyi baştan başlat | |
| inspector | yan panel | düzenleyicinin yanındaki sekmeli panel |
| compile | derlemek | |
| export | dışa aktarmak | |

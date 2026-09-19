# Flashscore Canlı Sinyaller Mobile v0.4

## v0.4 — Android dokunma/toggle düzeltmesi

v0.3'te monitor ayrı process'e alınmıştı. Bazı telefonlarda dashboard WebView
içindeki üst yeşil takip düğmesi ve Tüm Maçlar Modu butonu dokunmaya tepki
vermiyordu.

v0.4:
- WebView açıkça clickable/focusable yapıldı.
- Dashboard butonlarında Android `touchend` + `click` fallback kullanılır.
- Çift tetiklemeyi engelleyen debounce eklendi.
- Butona dokunulduğu anda UI optimistik olarak değişir.
- Monitor servis cevabı gelince gerçek state ile senkronize olur.
- JavaScript bridge komutları UI thread kuyruğuna alınmadan doğrudan servise gönderilir.
- Tüm Maçlar Modu, takip toggle, sekmeler, Şimdi tümünü tara ve Flashscore'u aç
  aynı dokunma altyapısını kullanır.
- v0.3'teki ayrı `:monitor` process ANR düzeltmesi korunur.

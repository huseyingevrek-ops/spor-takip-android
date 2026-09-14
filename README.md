# Spor Takip Android v4

Kişisel Health Connect verilerini doğrudan mevcut **Spor Takip Verileri** Google Sheet dosyasına senkronize eder.

- CSV ara dosyası oluşturmaz.
- Kayıtlı satırları tekrar eklemez; eksik kayıtları ekler, değişen kayıtları günceller.
- Cihaz destekliyorsa ve geçmiş izni verilirse ilk senkronizasyonda erişilebilen tüm Health Connect geçmişini tarar.
- Geçmiş izni desteklenmiyorsa Health Connect platform sınırı nedeniyle son 30 gün okunur.
- Adım, mesafe, kalori, egzersiz/kardiyo, uyku, nabız, dinlenik nabız, kilo, vücut yağı, VO2 Max, su, kat ve yükseltiyi işler.
- Gunluk, Egzersizler, Uyku, Nabiz ve Olcumler sekmelerini günceller.
- Arka plan senkronizasyonu WorkManager ile yapılır.

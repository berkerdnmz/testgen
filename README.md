# testgen

testgen, Spring Boot projelerindeki servis sınıfları için JUnit 5 ve Mockito ile birim testlerini otomatik üreten, büyük dil modeli tabanlı çok aşamalı bir sistemdir. Hedef proje statik analizle taranır, kaynak kod ve derlenmiş bytecode üzerinden bağlam (imzalar, bağımlılıklar, çağrılan metotların gövdeleri, sabitler) çıkarılır ve her metot için ayrı bir prompt ile test üretilir. Üretilen testler derlenir ve çalıştırılır; hata alınırsa model geri bildirimle testi onarır, onarılamayan testler karantinaya (`@Disabled`) alınır ve geçerli testler sınıf bazında birleştirilir. Son aşamada JaCoCo ile kapsam, PIT ile mutasyon skoru ölçülür.

## Aşamalar

`analyze` → `context` → `generate` → `merge` → `report` → `measure`

Her aşama diğerlerinden bağımsızdır ve tek başına çalıştırılabilir. Ara çıktılar hedef projenin `.testgen` klasörüne JSON olarak yazılır.

## Gereksinimler

- Java 21+ ve Maven
- OpenRouter API anahtarı, `OR_KEY` ortam değişkeni olarak tanımlı
- Hedef proje: Maven tabanlı Spring Boot projesi (kendi başına derlenebilir olmalı)

## Kullanım

Projeyi IntelliJ'de açıp `org.example.Main` sınıfını çalıştırın. Argüman verilmezse hedef proje ve paket/sınıf dosya gezgininden seçilir.

Argümanlarla çalıştırmak için:

    Main <hedef-proje-yolu> <paket|sınıf> [--stage=analyze,context,...] [--force]

## Yapı

- `analyzer`: proje profili, JDK seçimi, sınıf tarama
- `context`: kaynak ve bytecode tabanlı tip bağlamı
- `prompt`: üretim ve onarım prompt'ları
- `llm`: model istemcileri (`LlmClient` arayüzü; OpenRouter ve Ollama gerçeklemeleri)
- `postprocess`: import düzeltme, yama, karantina, birleştirme
- `runner`: Maven çalıştırma, JaCoCo/PIT ölçümü
- `pipeline`: aşamalar ve onarım döngüsü

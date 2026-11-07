# Retro TV

Emulator starych konsol (NES, SNES, Game Boy / GBA, Mega Drive i inne) na **Android TV**,
sterowany pilotem i padem.

Projekt bazuje na [Lemuroid](https://github.com/Swordfish90/Lemuroid) (GPLv3) autorstwa
Filippo Scognamiglio i używa rdzeni emulatorów [libretro](https://www.libretro.com/).
Oryginalny opis projektu: [LEMUROID-README.md](LEMUROID-README.md). Licencja: GPLv3, patrz [COPYING](COPYING).

## Instalacja na telewizorze

Każdy push na gałąź `main` buduje APK przez GitHub Actions i publikuje je jako wydanie (release).

Najnowsze APK:
`https://github.com/Maja3d-Git/retro-tv/releases/latest/download/retro-tv.apk`

1. Na telewizorze zainstaluj z Google Play aplikację **Downloader** (AFTVnews).
2. W ustawieniach Android TV zezwól aplikacji Downloader na instalowanie nieznanych aplikacji.
3. Wpisz w Downloaderze powyższy adres i zainstaluj APK.

Albo przez `adb`: `adb connect <IP-telewizora> && adb install -r retro-tv.apk`.

Rdzenie emulatorów pobierają się przy pierwszym uruchomieniu danej konsoli (potrzebny internet).

## Funkcje

- Ekran główny na telewizor w stylu „Kartridż” (jasny, ciemny albo automatyczny motyw)
- Biblioteka konsol z okładkami, wyszukiwanie, ulubione, szczegóły gry i statystyki czasu gry
- Pady Bluetooth/USB oraz **telefon jako pad** przez kod QR (do 4 telefonów, układ przycisków dopasowany do konsoli)
- Menedżer gier: kilka folderów, import z pendrive’a, dysk sieciowy (SMB/NAS), katalog darmowych gier homebrew, usuwanie
- Menu w trakcie gry: zapisy stanu z podglądem, przyspieszenie, dźwięk, zmiana płyty
- Ekran System: aktualizacje jednym kliknięciem, stan połączenia, kontrolery, dziennik (wysyłany na telefon przez QR)
- Kopie zapisów w Google Drive (wymaga konfiguracji projektu Google Cloud, patrz niżej)
- Kreator pierwszego uruchomienia

## Katalog darmowych gier

Lista gier jest w pliku [catalog/homebrew.json](catalog/homebrew.json). Aplikacja pobiera ją przy każdym otwarciu
katalogu, a pliki gier ściąga z najnowszych wydań autorów na GitHubie. Żeby dodać grę, dopisz wpis z repozytorium autora
(tylko gry, które autor udostępnia za darmo).

## Podpis aplikacji

Wydania są podpisywane kluczem z sekretów repozytorium `RETROTV_KEYSTORE_B64` i `RETROTV_KEYSTORE_PASSWORD`
(alias `retrotv`). Bez nich używany jest publiczny klucz testowy. Zmiana klucza wymaga jednorazowej reinstalacji aplikacji.

## Uwaga prawna

Aplikacja nie zawiera gier ani nie łączy się ze stronami z nielegalnymi ROM-ami. Używaj własnych kopii
swoich gier albo darmowych gier homebrew.

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

## Plan

1. ✅ Baza: Lemuroid z obsługą Android TV, pady Bluetooth, zapisy stanu, automatyczne budowanie APK
2. Nowy wygląd ekranów TV (wg makiety)
3. Telefon jako pad przez kod QR (strona w przeglądarce, WebSocket w sieci lokalnej)
4. Rozbudowane ustawienia sterowania i profile padów
5. Menedżer ROM-ów: własne źródła (URL / NAS / katalogi homebrew), pobieranie, usuwanie

## Uwaga prawna

Aplikacja nie zawiera gier ani nie łączy się ze stronami z nielegalnymi ROM-ami. Używaj własnych kopii
swoich gier albo darmowych gier homebrew.

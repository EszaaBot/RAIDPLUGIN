# RaidPlugin 1.2.0 — Paper 1.21.4

Plugin raidów na Paper 1.21.4 (Java 21), z ekonomią Vault/EssentialsX i strukturą `raid.schem`.

## Wymagane pluginy
- WorldEdit 7.3.x — wklejanie pliku `.schem`
- Vault — most do ekonomii
- EssentialsX — saldo graczy

## Budowanie
W katalogu projektu uruchom:

```bash
gradle build
```

Gotowy JAR będzie w `build/libs/RaidPlugin-1.2.0.jar`.

## Instalacja
1. Wgraj WorldEdit, Vault i EssentialsX na Paper 1.21.4.
2. Zbuduj projekt i skopiuj JAR do `plugins/`.
3. Uruchom serwer. Plugin skopiuje `raid.schem` do `plugins/RaidPlugin/raid.schem`.
4. Ustaw świat i pozostałe opcje w `plugins/RaidPlugin/config.yml`.

## GUI administracyjne (`raid.admin`, domyślnie OP)
- `/rajdgodziny` — otwiera GUI harmonogramu. Kliknięcie ustawionej godziny usuwa ją. Przycisk „Dodaj godzinę” prowadzi do wyboru godziny 00–23, a następnie minut 00, 15, 30 lub 45. Każda zmiana zapisuje się od razu w `config.yml`.
- `/rajddrop` — otwiera GUI dropu. Kliknięcie przedmiotu na górnej liście usuwa go. Prawy przycisk myszy zmienia jego wagę losowania. Kliknięcie przedmiotu w dolnym, własnym ekwipunku dodaje dokładny item (wraz z nazwą, enchantami i metadanymi) do puli; zapisuje się automatycznie.
- `/rajdstart` — natychmiastowy spawn raidu.
- `/rajdstop` — zatrzymanie raidu.
- `/rajdreload` — przeładowanie config.yml.

Można też używać `/rajd godziny` i `/rajd drop`, aby otworzyć odpowiednie edytory GUI.

## Komendy gracza
- `/rajd` — GUI raidu.
- `/rajd tp` lub `/rajdtp` — pobiera domyślnie $500 i teleportuje w losowe, bezpieczne miejsce w promieniu 500 bloków od raidu.

Waypoint jest indywidualny: dopiero po udanej płatnej teleportacji gracz dostaje czerwony napis `RAJD`, dystans w actionbarze, kompas i widoczny dla niego napis nad strukturą. Po zmianie świata lub oddaleniu się poza skonfigurowany zasięg dostęp do waypointu jest wyłączany.

## Mechanika
- Domyślne godziny: 15:00, 18:00, 21:00, strefa `Europe/Warsaw`.
- 10 minut przed startem czerwony bossbar odlicza czas.
- W czasie wydarzenia bossbar pokazuje `SERWER NAJECHANY`.
- Domyślnie co 5 minut wypadają 2 różne losowe pozycje z puli `loot` (waga `weight`). Pierwszy drop pojawia się przy starcie.
- Struktura jest chroniona przed niszczeniem i eksplozjami.

## Ważne
- Ustaw `raid.world` na nazwę świata, w którym ma pojawiać się raid.
- `raid.drop-offset` określa położenie punktu dropu względem początku schematu.
- Lokalizacja jest wybierana losowo w granicach world borderu, plugin korzysta z już wygenerowanych chunków.
- To projekt źródłowy, nie gotowy JAR. Przed uruchomieniem na produkcji przetestuj go na kopii serwera.

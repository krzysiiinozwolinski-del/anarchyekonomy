# AnarchyEconomy

Plugin ekonomiczny dla Paper 26.2 / Java 25.

## Funkcje
- `$` i saldo graczy
- `/bal`
- `/pay <gracz> <kwota>`
- `/baltop`
- `/sell [ilość|all]`
- `/sellall`
- `/shop` — GUI: lewy klik kupuje 1 szt., prawy sprzedaje 1 szt.
- `/market` — GUI rynku graczy
- `/market sell <cena>` — wystawia cały stack trzymany w głównej ręce
- SQLite: salda i oferty przetrwają restart
- ceny sklepu w `config.yml`

## Budowanie
Wymagane JDK 25 i Gradle zgodny z JDK 25.

```bash
gradle build
```

Gotowy JAR znajdziesz w `build/libs/`.

## Instalacja
Skopiuj JAR do folderu `plugins` serwera Paper 26.2 i uruchom serwer.

# Fazua Monitor 0.3 — testovací aplikace pro Galaxy Watch Ultra2 (Wear OS)

**Co to je:** zdrojový kód pro malou samostatnou aplikaci, která z hodinek přímo přes Bluetooth LE čte data z FAZUA Ride60. Pro tento projekt jsme z telefonu na Ghost Pathriot zjistili: `0x180F/0x2A19` (98 %), `0x1818/0x2A63` (testovací notifikace s 0 W a čítačem otáček), `0x1816` (služba pro kadenci) a nezdokumentované služby `0xFD6D` a `f1480001-...`. **Baterii 98 % ještě porovnej s údajem v Locus Map**, abychom vyloučili baterii komunikační jednotky.

**Zatím to není sestavené APK.** Neprováděli jsme kompilaci Android aplikace ani test na hodinkách. Program zde otestoval pouze samostatný parser standardních dat. Součástí tohoto archivu je teď i automatický postup pro sestavení APK na GitHub Actions.

## Co se bude zobrazovat

- Stav baterie v %, pokud `0x2A19` ukazuje trakční baterii.
- Okamžitý výkon jezdce z `0x2A63`, včetně 0 W při stání.
- Kadence vypočítaná z dvojice po sobě jdoucích měření.
- Stav připojení a seznam nalezených Bluetooth zařízení.
- V režimu diagnostiky také HEX data z neznámých služeb; příkazy motoru se nepíšou. Aplikace zapisuje pouze standardní konfigurační deskriptory notifikací `0x2902`.

Výkon motoru a režim Breeze/River/Rocket **zatím nerozpoznáváme**. To není chyba aplikace; potřebujeme ověřený formát dat.

## Jak získat APK bez instalace Android Studia (pro někoho s PC)

1. Přihlas se do [GitHub](https://github.com/) a vytvoř **nový soukromý repozitář** (např. `fazua-watch-monitor`).
2. Rozbal tento ZIP a do vytvořeného repozitáře nahraj obsah rozbalené složky, včetně **skryté složky `.github`** a jejího souboru `.github/workflows/build-apk.yml`. Přes web GitHubu lze použít **Add file → Upload files** (zachovej strukturu adresářů). Pokud se skrytá složka při nahrávání ztratí, sestavení se nespustí.
3. Na GitHubu otevři záložku **Actions**, workflow **Build Fazua Monitor APK**, tlačítko **Run workflow**. Workflow se spouští také při push na větev `main`.
4. Po úspěšném běhu otevři detail workflow a v sekci **Artifacts** stáhni `fazua-monitor-wearos-debug-apk`. Po rozbalení dostaneš `watch-debug.apk`.
5. Nainstaluj APK na hodinky pomocí bezdrátového ladění přes ADB nebo Android Studio. Podle dokumentace Androidu se hodinky párují s PC přes Wi-Fi a přidělený párovací kód; následně `adb install watch-debug.apk`.
6. Na hodinkách při spuštění povol Bluetooth oprávnění, zapni kolo, vyber `PATH RIOT`. Nejprve testuj jen na odstaveném kole.

Tento postup používá veřejné Actions z GitHub Marketplace; je potřeba mít GitHub účet. APK je podepsané jen vývojářským testovacím klíčem a není určeno pro distribuci do Play Store.

## Varianta s Android Studiem

Otevři složku projektu v [Android Studiu](https://developer.android.com/studio), měj Android SDK Platform 35, Java 17, Android Gradle Plugin 8.7.3 a Gradle 8.9. Pro sestavení na PC použij `gradle :watch:assembleDebug` (projekt **nemá Gradle wrapper**). Pro instalaci na hodinky je nejjednodušší spustit `watch` modul přímo z Android Studia přes připojené hodinky s bezdrátovým laděním.

## Základní bezpečnost a omezení

- Nikdy nestiská příkazy ovládající motor, nevstupuje do DFU a nic neaktualizuje.
- Do neznámých charakteristik nikdy nezapisuje; pouze READ/NOTIFY.
- Není vyřešené dlouhodobé provozování při zhasnutém displeji ani automatické připojení po ztrátě signálu.
- Souběžné Bluetooth připojení hodinek a Locusu je nutné odzkoušet.
- Při použití na kole nerozptyluj pozornost manipulací s hodinkami.

## Kontrola parseru

```sh
mkdir -p tests/bin
javac -d tests/bin watch/src/main/java/cz/ride/fazuawatch/TelemetryParser.java tests/TelemetryParserTest.java
java -cp tests/bin TelemetryParserTest
```

Další důležitý test: porovnat **98 %** z Bluetooth s hodnotou baterie Ride60 v aplikaci FAZUA/Locus. Pokud se shodují, máme potvrzenou trakční baterii.

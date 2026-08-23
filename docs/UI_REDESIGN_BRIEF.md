# Käyttöliittymäuudistuksen lähtötiedot

Vastaukset kysymyksiin, jotka Gemini esitti ennen kuin se kirjoittaa Claude Codelle annettavan
uudistusohjeen (2026-08-23). Kaikki alla oleva on tarkistettu tästä koodipohjasta, ei muistista.

Tämä on **lähtötietomuistio, ei suunnitelma**. Se kertoo mihin uudistus osuu ja mitkä rajoitteet
ovat olemassa jo ennen kuin yhtään riviä on kirjoitettu. Kun uudistus on tehty, ratkaisut kuuluvat
`docs/ARCHITECTURE.md`:hen ja `CHANGELOG.md`:hen — ei tänne.

## 1. Millä käyttöliittymä on toteutettu

**Natiivi Android — ei mitään weppiä.** Kotlin + Jetpack Compose, Material 3. Ei Reactia, ei
Next.js:ää, ei Tailwindiä, ei HTML/CSS:ää, eikä `WebView`ta missään.

| Asia | Tila |
| --- | --- |
| Moduulit | yksi `:app`, paketti `fi.merilainen.treenivalmentaja` |
| Compose | BOM 2024.09.00 → Material3 1.3.x |
| Navigaatio | `navigation-compose` 2.8.9, `NavHost` `TreenivalmentajaApp.kt`:ssa |
| Ikonikirjasto | `material-icons-extended` **jo riippuvuutena** |
| SDK | `minSdk 26`, `compileSdk`/`targetSdk` 36 |
| Kuvat | Coil + coil-gif |

Design tokenit menevät näihin tiedostoihin, eivät mihinkään CSS-muuttujiin:

- `ui/theme/Color.kt` — raa'at `Color(0xFF…)`-arvot (`BluePrimary`, `SurfaceLight`/`SurfaceDark`,
  `BackgroundLight`/`BackgroundDark`, tekstivärit, status-värit)
- `ui/theme/Theme.kt` — `lightColorScheme()` / `darkColorScheme()` -kartoitus
- `ui/theme/Type.kt` — `Typography`; tällä hetkellä vain `bodyLarge` on ylikirjoitettu, eli
  typografiaskaalassa on tilaa ilman että mitään puretaan

Yksiköt ovat **dp ja sp**, eivät px/rem. Välistys `Arrangement.spacedBy(…dp)` ja
`Modifier.padding(…dp)`.

### Kaksi asiaa, jotka ohjeistuksen on pakko päättää

1. **Dynamic Color (Material You) on päällä** — `MyApplicationTheme(dynamicColor = true)`.
   Android 12+ -laitteella teemavärit tulevat käyttäjän taustakuvasta ja **ylikirjoittavat
   mockupin paletin**. Jos mockuppien värien pitää näkyä sellaisinaan, ohjeen on sanottava
   `dynamicColor = false`; muuten oma paletti jää vain vanhempien laitteiden fallbackiksi.
2. **Vaalea ja tumma on molemmat määriteltävä.** Sovelluksessa on 2026-08-23 alkaen
   vaalea/tumma/järjestelmä-valinta (`ThemePreference`), joten jokainen uusi pinta tarvitsee arvon
   molemmissa skeemoissa. Kumpikin on nyt käyttäjän tavoitettavissa ilman puhelimen asetusta.

### Arkkitehtuurirajoite, joka on helppo rikkoa vahingossa

Jokainen näyttö on jaettu **tilattomaan `XScreenContent(...)`-composableen ja ohueen
ViewModel-kääreeseen**. Se on ainoa syy, miksi Roborazzi-kuvakaappaustestit pystyvät renderöimään
näytöt lainkaan — ennen jakoa testi olisi joutunut pystyttämään repositoryn, tietokannan,
herätyskellon ja kaksi use casea piirtääkseen listan kortteja. Uusien näyttöjen on noudatettava
samaa. Käyttöliittymätekstit ovat suomeksi.

## 2. Liike-ikonien formaatti

**SVG ei ole Androidin ajonaikainen formaatti.** Prioriteettijärjestys:

1. **Material Symbols** — `material-icons-extended` on jo APK:ssa. Tintattava, nolla uutta
   assettia, kattaa suurimman osan tarpeesta.
2. **VectorDrawable XML** (`res/drawable/*.xml`) — tänne viivapiirrokset menevät. Gemini voi
   tuottaa SVG:n, mutta se **konvertoidaan VectorDrawableksi** (Android Studion Vector Asset tai
   `svg2vector`). Pelkkää polkudataa, ei suodattimia eikä raskaita gradientteja. Tukee tinttausta,
   joten sama tiedosto palvelee molempia teemoja.
3. **Bitmapit (PNG/WebP), tekoälygeneroituina — ei suositella.** `AGENTS.md`:n assettibudjetti:
   WebP on oletus, PNG vaatii perustelun, ei bitmappeja `-nodpi`-kansioon. Bitmappia ei voi
   tintata, joten se tarvitsee oman version vaalealle ja tummalle × viisi tiheysämpäriä.

**Binäärit kopioidaan `cp`:llä, `git mv`:llä tai tavutilaisella kirjoituksella — ei koskaan
editorin, leikepöydän tai heredocin kautta.** Repo on kerran menettänyt jokaisen kuvatiedostonsa
juuri siihen. Kuva myös tarkistetaan katsomalla se, ei otsaketta lukemalla.

### Per-liike-kuvitusta ei tarvitse paketoida

Liikkeiden kuvat ja animaatiot **haetaan jo ajonaikaisesti** ExerciseDB:stä ja wgeristä Coililla
(levyvälimuisti pois päältä palveluntarjoajan ehtojen takia, `docs/EXERCISE_GUIDE.md`). Bird
dogille, lankulle ja kissanlehmälle on siis jo kuvitus. Mukaan paketoitavien ikonien pitää olla
**pieniä kategoriamerkkejä** — lajityyppi, lihasryhmä, sarjan tila — eikä per-liike-kuvitusta, joka
kaksintaisi olemassa olevan piirteen ja vanhenisi heti kun suunnitelmaan tuodaan uusi liike.

## 3. Placeholder-välilehdet Edistymiselle ja Profiilille

**Ei suositella.** Material 3:n `NavigationBar` on 3–5 toimivalle kohteelle; harmaa "Coming soon"
-välilehti on umpikuja, joka pitää silti testata ja kuvakaapata. Kaksi parempaa reittiä:

- **Pidä navigaatio kolmessa** (Tänään / Viikko / Asetukset) ja tee visuaalinen uudistus omana
  muutoksenaan. `AGENTS.md` sanoo suoraan, että kosmeettiset muutokset kuuluvat omaan committiinsa
  ja ehdotetaan ensin — redesign on juuri sitä, joten se pysyy erillään toiminnallisuudesta.
- **Tai tee "Edistyminen" oikeasti samalla kertaa.** Data on jo Roomissa ja observoitavissa ilman
  skeemamuutosta: `workout_sessions` (tila per päivä), `session_events` (tapahtumaloki, sisältäen
  ohjatun treenin toteumat), `oura_daily_summaries` (palautuminen, uni, HRV per päivä) sekä
  intervals.icu:n harjoituskuorma ja juoksumetriikat. Read-only-näyttö olemassa olevien flowien
  päälle on oikea piirre, ei paikkamerkki.

**"Profiili" kannattaa jättää pois.** Sovellus on yhden käyttäjän, kirjautumista ei ole, ja kaikki
mitä profiiliin laitettaisiin — tunnukset, API-avaimet, ilmoitusajat, teema — on jo Asetuksissa.
Tulevat näkymät kuuluvat `docs/ROADMAP.md`:hen, eivät navigaatiopalkkiin.

## Mitä uudistusohjeeseen on lisäksi kirjattava

- **`AGENTS.md` on sitova** ja luetaan ensin; raportti alkaa rivillä `AGENTS.md luettu (v4)`.
- **Jokainen käyttöliittymämuutos muuttaa Roborazzi-perustasoja.**
  `./gradlew :app:recordRoborazziDebug`, diffit katsotaan silmällä, ei uudelleentallennusta
  refleksinä, ja committiin vain ne kuvat jotka oikeasti muuttuivat. Loput palautetaan
  `git checkout`illa, jotta diffiin ei jää uudelleentallennuskohinaa.
- **Compose BOM 2024.09.00 = Material3 1.3.** Mikään uudempi komponentti (esim. M3 Expressive) ei
  ole käytettävissä ilman BOM-nostoa, ja se on oma päätöksensä omine seurauksineen.
- Dokumentaatio päivitetään (`CHANGELOG.md`, `PROJECT_STATUS.md`, `docs/`) ja raportissa on
  **mitatut luvut** — APK-koko tavuina, testit muodossa `testit/failures/errors`, ja komennot jotka
  ne tuottivat.
- APK-kokoa mitataan `clean`illä ja saman koneen molemmilta puolilta. Mitattu ero on usein
  16 KiB:n kohdistussivu eikä piirteen todellinen hinta; `PROJECT_STATUS.md`in mittaushistoriassa
  on tästä kaksi kertaa väärin luettua esimerkkiä.

# Suunto-kokeilu 13.9.2026

Päivitys: sovellukseen on lisätty [ohjelman kokeilu](PROGRAM_TRIAL.md).
Uudessa versiossa valitse JSONin avaamisen jälkeen **Kokeile**: voit käydä
vaiheet läpi ja viedä yhden testijuoksun tälle päivälle aktivoimatta ohjelmaa.
Alla oleva korvaamisvaroitus koskee edelleen **Tuo ohjelmaksi** -polkua.
Myöhemmän testitilan ehdotus alempana kuvaa alkuperäistä suunnitelmaa;
toteutetun rajatun kokeilun toiminta ja rajoitukset ovat yllä linkitetyssä ohjeessa.

Tiedostot: `sample-data/suunto-testi-2026-09-13.json` on Treenivalmentajan
ohjelmatuontiin ja `sample-data/suunto-testi-2026-09-13-intervals.txt` on saman
harjoituksen teksti Intervals.icu:n harjoituseditoriin. JSON ei ole Suunnon
eikä Intervals.icu:n suora tiedostotuontimuoto. SchemaVersion on edelleen 1;
valinnainen `runSteps` on taaksepäin yhteensopiva lisäys.

Ohjelma sisältää vain yhden juoksun 13.9.2026 Europe/Helsinki-aikavyöhykkeellä,
ilman lukittua kellonaikaa. Se on alkuperäisen ohjelman viikon 1 tiistain
6 × 400 m -harjoitus uusilla testitunnisteilla:

- 720 s kevyttä juoksua.
- 3 × 15 s kiihdytys, välissä kaksi 45 s palautusta.
- 6 × 400 m, välissä viisi 90 s palautusta.
- 600 s loppuverryttely.

Yhteensä 18 vaihetta, 1 905 sekuntia aikavaiheita ja 2 400 metriä vetoja.
Vauhtitavoite 265 s/km koskee vain vetoja; sillä kokonaiskesto on 2 541 s
eli 42:21. Alkuperäinen 43 minuutin kokonaisarvio säilyy. Ohjeen 1:44–1:46
vetotavoitteesta valittiin kellolle hitaampi pää 1:46; nykyinen RunStep ei tue
vauhtialuetta. Matkavaihe päättyy 400 metrin täyttyessä, ei 106 sekunnin jälkeen.
Kevyessä vaihtoehdossa on yksi 1 800 sekunnin vaihe ilman vauhtitavoitetta.
WarmupSec on yhteenveto, jota ei lisätä runSteps-vaiheiden päälle viennissä.

## Nykyisen ohjelman suojaaminen

Nykyinen `TrainingRepository.importPlan` poistaa aiemmat ohjelmat sekä niiden
harjoitukset ja tapahtumat, kun uuden ohjelman korvaava aktivointi vahvistetaan.
Eri testitunniste ei estä tätä. Vanhan ohjelman päättyminen ei tee sen historiasta
tarpeetonta. Tätä JSONia ei pidä aktivoida nykyisen ohjelman päälle, jos tiedot
halutaan säilyttää.

Tämän päivän kellotestin voi tehdä lisäämällä Intervals.icu-kalenteriin erillisen
Run-harjoituksen 13.9.2026, nimeämällä sen TESTI 6 × 400 m ja liittämällä
TXT-tiedoston sisällön harjoituksen tekstieditoriin. Tarkista editorin esikatselusta
18 vaihetta ja vetojen 400 m / 4:25/km ennen tallennusta. Kytke Suunto-yhteydessä
Upload planned workouts ja lajiksi Run. Synkronoi Suunto-sovellus ja kello.
Tämä ei edellytä sovelluksen ohjelmatuontia. Oikeasti tallennettu kellosuoritus
voi silti synkronoitua tavalliseen harjoitushistoriaan.

## Ehdotus myöhemmäksi testitilaksi (ei toteutettu tässä)

Ensimmäinen rajattu toiminto voisi olla JSONin avaaminen kokeiluun aktivoimatta
ohjelmaa: esikatselu, vaiheiden läpikäynti ja erikseen vahvistettava yhden juoksun
vienti kelloon. Tämä kattaisi tämän päivän tarpeen.

Kokonaisia ohjelmia testaava tila tarvitsee erillisen Room-tietokannan sekä
erillisen sovellustilan, jotta testikirjaukset ja harjoitusmuutokset eivät päädy
oikeaan historiaan. Testikello, päivän vaihto, ajastimien nopeutus ja testitietojen
nollaus mahdollistaisivat ohjelman läpikäynnin. Oikeat muistutukset ja ulkoiset
kirjoittavat synkronoinnit olisivat oletuksena pois. Kelloviennillä olisi oma
testitunnisteiden nimiavaruus ja vain testivienteihin rajattu poisto, jotta
tavallinen seitsemän päivän vienti ei korvaisi oikeita tulevia harjoituksia.

## Tarkistus

Tämän työn aikana ei muutettu sovelluksen koodia eikä tehty uutta APK:ta.
`python .scratch/create_today_run.py` tarkistaa vaiheiden määrät ja summat.
`./.scratch/validate_today_run.ps1` käyttää aiemmin käännettyjä sovellusluokkia:
todellinen PlanJson, PlanValidator ja toPlannedRunEvent. Tarkistus hyväksyy
JSONin ja vertaa TXT-tiedostoa sovelluksen vientitulokseen. Tarkistus ei kirjoita
Roomiin eikä ota yhteyttä Suuntoon tai Intervals.icu:hun. Fyysisellä Suunto 5:llä
testaaminen jää käyttäjän tehtäväksi.

Lähteet: [Suunto 5 Guides -käyttöohje](https://www.suunto.com/Support/Product-support/suunto_5/suunto_5/suuntoplus-guides2/),
[Suunto Guides -aloitus](https://us.suunto.com/pages/how-to-get-started-with-suuntoplus-guides),
[Intervals.icu:n Suunto-vienti](https://forum.intervals.icu/t/upload-workouts-to-suunto-watches/9560).

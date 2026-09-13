# Ohjelman kokeilu ja JSONin avaaminen

Ohjelmatiedoston avaaminen asetuksista tai Androidin tiedostonhallinnasta näyttää
ensin validoidun ohjelman nimen ja harjoitusmäärän. Käyttäjä valitsee **Kokeile**,
**Tuo ohjelmaksi** tai **Sulje**. Tuonti käyttää aiempaa aloituspäivän valintaa
ja olemassa olevaa korvaamisen vahvistusta. Avaaminen itsessään ei aktivoi ohjelmaa.

Kokeilussa voi valita harjoituksen miltä tahansa ohjelman päivältä sekä kevyen
vaihtoehdon. Juoksun runSteps sekä voimaharjoittelun olemassa olevan
buildActiveWorkoutSteps-rakentajan vaiheet voi käydä läpi. Ajastinta voi käyttää
1×- tai 30×-nopeudella, keskeyttää, siirtyä seuraavaan vaiheeseen ja aloittaa alusta.
Ajastimen päättyminen siirtää seuraavaan vaiheeseen; seuraavan vaiheen käynnistys
tehdään itse. Matkavaiheen täyttyminen simuloidaan painikkeella, ei GPS:llä.
Ohjelmaa ilman vaiheita voi tarkastella tekstiohjeena ja mahdollisena kestoajastimena.

TrialProgress on muuttumaton domain-malli. Kokeilun valinnat ja edistyminen ovat
ainoastaan näkymän muistissa; kokeilu ei kutsu Room-kirjoituksia,
harjoituksen tilasiirtymiä, muistutuksia tai normaalin harjoituksen etenemistallennusta.
Sulkeminen tai prosessin päättyminen hävittää kokeilun. Tämä ei ole simuloitu
TrainingEngine-kalenteri eikä erillinen pysyvä testitietokanta.

## Kellotesti

Vaiheistetun juoksun voi erikseen vahvistamalla viedä tämän päivän testiksi.
Päivä määräytyy puhelimen paikallisen kellon mukaan; se näkyy vahvistuksessa.
IntervalsRepository.exportTestRun lähettää vain yhden upsert-pyynnön, ei poistoja.
Tunniste on `treenivalmentaja-test-run-<ohjelman ja harjoituksen hash>-<päivä>`.
Saman testin uusinta ja kevyen vaihtoehdon vienti päivittävät saman tapahtuman.
Tavallisen viennin tarkka tunnisterajaus ei koske tätä nimiavaruutta.
Sekä normaalin että testiviennin lukko on sama. Palvelun palauttamien vaiheiden
määrä ja tunniste sekä yhteyden sukupolvi tarkistetaan.

Testitapahtuma on oikea Intervals.icu-kalenterimerkintä. Sen voi poistaa sieltä
käsin. Suunto-yhteyden ja synkronoinnin on oltava käytössä. Kellolla tallennettu
oikea suoritus voi normaalien yhteyksien kautta päätyä oikeaan historiaan.
Voimaharjoituksia ei viedä kelloon.

## Androidin tiedostonavaus

MainActivity tarjoaa ACTION_VIEW- ja ACTION_SEND-tuen MIME-tyypeille
`application/json` ja `text/json`. VIEW käyttää content-URIa ja SEND EXTRA_STREAMia.
Sekä kylmä käynnistys että onNewIntent avaavat saman valintanäkymän; singleTop
mahdollistaa uuden tiedoston vastaanottamisen jo avoimeen aktiviteettiin.
Lukeminen tapahtuu I/O-dispatcherilla, enintään 4 MiB, UTF-8 ja valinnainen BOM.
Uusi avaus peruuttaa aiemman keskeneräisen lukemisen. Dokumenttia ei kopioida
yksityiseen tallennukseen eikä URI-oikeutta säilytetä pysyvästi.

Vastaanotin hyväksyy vain content-URIa; file- ja verkkolinkit eivät anna ulkoiselle
kutsujalle reittiä sovelluksen yksityisten tiedostojen lukemiseen. Lukeminen tai
virheellinen JSON ei aktivoi ohjelmaa. OuraCallbackActivityn state-tarkistus säilyy.
Tallennustilan laajaa käyttöoikeutta ei lisätty.

Android tarjoaa sovellusvalinnan lähettävän sovelluksen MIME-tyypin ja aiempien
oletusvalintojen perusteella. Jos tiedostonhallinta ilmoittaa JSONin text/plain- tai
octet-stream-tyyppisenä, käytä Treenivalmentajan asetusten tiedostonvalitsinta.
Sovellus ei rekisteröidy kaikkien tekstien tai binääritiedostojen käsittelijäksi.

Lähde: [Androidin intent-suodattimet](https://developer.android.com/guide/components/intents-filters).

# Changelog

All notable changes to this project will be documented in this file.

Entries below a date describe what was true when they were written; they are history and are not
rewritten when the code moves on. For the current state, see [PROJECT_STATUS.md](PROJECT_STATUS.md).

## [Unreleased] - 2026-08-24

### Added
- Ohjatun treenin seuraava liike on oma korttinsa nykyisen alla, ei rivi sen sisällä.
- Liikeohje on sama pieni painike samalla nimellä sekä valmistautumis- että suoritusruudulla,
  oikeassa reunassa pois sen painikkeen tieltä joka vie treeniä eteenpäin. Se oli "Näytä liikeohje"
  täysleveänä toisessa ja "Liikeohje" pienenä toisessa.
- **Kalenterin päivää täppäämällä alla oleva lista hyppää siihen päivään.** Ruudut ovat
  klikattavia, valittu päivä saa renkaan, ja lista animoituu riville. Kalenteri tarjoaa kuukauden
  jokaisen päivän mutta listassa on vain päivät jotka ovat ansainneet rivin, joten päivä jolla ei
  ole riviä vie lähimpään sellaiseen — tapahtumaton täppäys lukisi rikkinäisenä kontrollina.
- Tulevan treenin kortissa kolme statussaraketta: Kesto, Liikkeet ja Kierrokset. Vain
  voimaharjoituksille: `parseStrengthDescription` päättelee liikkeet pilkkuja laskemalla, joten
  juoksun kuvaus tuotti "Liikkeet 2".
- Viikkolistan päiväotsikko erottaa menneen, tämän päivän ja tulevan: tehty mennyt päivä saa
  kuittausmerkin, tänään saa täytetyn korostuksen, tulevat ovat neutraaleja. Aiemmin jokainen
  päivänimi oli samaa korostusväriä, eli korostus ei erottanut mitään.

### Changed
- Tänään-kortin täppälista poistui voimaharjoituksilta, joiden liikkeet ovat suunnitelmassa: ohjattu
  tila omistaa etenemisen, eikä samoja liikkeitä tarvitse kuitata kahdessa paikassa. Kortti näyttää
  liikkeet luettavana listana, liikeohjeineen. Lista jää niille sessioille, joiden liikkeet on
  jäsennetty kuvauksesta — ohjattu tila ei osaa ottaa niitä, joten se on niille ainoa tapa pitää lukua.

### Fixed
- **Kolmen pisteen valikko katosi kokonaan kesken jääneestä treenistä, eikä "AI-analyysi: miten
  meni?" ollut silloin tavoitettavissa millekään tehdylle osalle.** `Ohitettu` tarkoitti tähän asti
  kahta eri asiaa — treeniä ei koskaan aloitettu, ja treeni aloitettiin mutta jätettiin kesken —
  eikä `AiAnalysisAvailability.kindFor` tarjonnut kummallekaan mitään analyysiä, vaikka promptin
  rakentaja osasi jo kuvata kesken jääneen ohjatun treenin rehellisesti ("3/5 liikettä tehty").
  Uusi tila `Keskeytetty` erottaa nämä kaksi: `Aloitettu`-treenin valikossa on nyt "Keskeytä
  treeni" "Ohitan" sijaan (joka ei siinä tilassa koskaan onnistunutkaan — Room hylkäsi sen hiljaa),
  ja sekä `Aloitettu` että `Keskeytetty` tarjoavat "AI-analyysi: miten meni?" samalla 7 päivän
  ikkunalla kuin Valmis-tila.
- **Ohitettu liike ei ole enää mikään mihin voi palata.** "Edellinen vaihe" siirtyi yhden askeleen
  taaksepäin ehdoitta, eli suoraan ohitetun liikkeen päälle — ja tallensi sen jatkamiskohdaksi.
  Ohitettu liike omistaa nyt kolme vaihetta: valmistautumisruutunsa, itsensä ja sitä seuraavan levon.
  Kaikki kolme ovat näkymättömiä eteenpäin, taaksepäin ja jatkettaessa. Kierrostauko ei ole niiden
  joukossa: se kuuluu kierrosten väliin eikä yhteenkään liikkeeseen.
- **Ohjattu treeni aloitti alusta, jos näytöltä poistui ja palasi.** Eteneminen oli
  `rememberSaveable`issa, joka säilyy prosessin tappamisen yli mutta ei navigaatiokohteen
  poistamista — takaisin-nuoli vie mukanaan sen tilan jota se piti. Sijainti ja ohitetut liikkeet
  kirjoitetaan nyt `settings`-DataStoreen jokaisella askeleella, ja näyttö odottaa lukemista
  piirtämättä mitään: ensimmäisen liikkeen näyttäminen ja hetken päästä korjaaminen kertoisi kesken
  treenin olevalle että hän aloittaa alusta. Sessiotunnus tallennetaan mukana, jottei toinen treeni
  peri vanhaa askelnumeroa.
- Edistymispalkki piirtyi täytenä kun mitään ei ollut vielä tehty: Material 3 ottaa
  `LinearProgressIndicator`in taustaraidan `secondaryContainer`-roolista, joka tässä paletissa on
  valmistumisen vihreä. Raidan väri nimetään nyt eksplisiittisesti, myös ajastinrenkaassa.
- "Seuraavaksi"-kortti listasi liikkeen jota oltiin parhaillaan tekemässä, koska horisontti ylitti
  kierrosrajan ja seuraava kierros alkaa samalla liikkeellä. Rajattu kuluvaan kierrokseen.
- **`secondaryContainer`, `onSecondaryContainer`, `errorContainer` ja `onErrorContainer` puuttuivat
  molemmista väriskeemoista.** Material 3 johtaa puuttuvan roolin omasta perusparetistaan, joten
  ensimmäinen `FilledTonalButton` piirtyi liilana sovelluksessa jonka paletissa ei ole liilaa.
  Roolit on nyt määritelty samoista väriperheistä kuin `GreenAccent` ja `RedAccent`.
- Virhekortin painikkeet piirtyivät sinisinä punaisella pohjalla, koska `TextButton` käyttää
  oletuksena `primary`-väriä.



### Added
- Material 3 Electric Blue -uudistus: yhtenäinen vaalea/tumma paletti, kolme toimivaa
  navigaatiokohdetta, Oura-pisteiden yhteenvetorenkaat ja `java.time`-kalenterista laskettu
  kuukausiruudukko. Material You -dynaamiset värit poistettiin tietoisena käyttäytymismuutoksena;
  harjoitusten semanttiset statusvärit säilyivät.
- Active Workout Mode: koko näytön ohjattu treeni, valmistautuminen ennen jokaista liikettä,
  deadline-pohjaiset lepoajat, ruudun hereilläpito, ääni/värinä, ohitetut liikkeet sekä RPE/tuntuma.
  Lopputulos tallentuu completion-eventin `activeWorkout`-payloadiin. Plan Schema v1 sai valinnaiset
  `equipment`- ja `roundRestSec`-kentät; jälkimmäinen nosti Roomin skeemaan 13.
- AI Phase C: tarkentava kysymys tai validoitu MOVE/LIGHTEN-ehdotus, pysyvät käyttäjärajoitteet,
  read-only-esikatselu ja erillinen hyväksyntä. Hyväksytty lista toteutetaan atomisesti ja merkitään
  tapahtumalokiin lähteellä `AI_ADVISOR`; virheellinen lista ei muuta mitään.

### Added
- **Ilmoitus siitä, että sovellus on päivitetty**, omalla kanavallaan ("Sovelluksen päivitykset").
  Päivitys tappaa korvattavan prosessin — se on päivityksen määritelmä — eikä Android anna uuden
  käynnistää activityä `ACTION_MY_PACKAGE_REPLACED`-vastaanottimesta: taustalta käynnistäminen on
  estetty Android 10:stä asti eikä tämä vastaanotin ole millään poikkeuslistalla. `startActivity`
  epäonnistuisi hiljaa jokaisessa puhelimessa ja koodi näyttäisi siltä kuin sovellus käynnistäisi
  itsensä uudelleen. Sen sijaan ilmoitus kertoo asennetun version ja avaa sovelluksen yhdellä
  painalluksella. Automaattista uudelleenkäynnistystä ei siis ole, koska sellaista ei ole
  tarjolla — ei siksi, ettei sitä olisi yritetty.

### Fixed
- **AI:n muutosehdotus kaatui aina kun muutosta ei tarvittu.** Neuvojan protokollassa oli vain
  kaksi vastausta — tarkentava kysymys tai ehdotus operaatioineen — joten kun malli päätyi siihen
  ettei suunnitelmaa kannata muuttaa, se palautti `proposal`in tyhjällä operaatiolistalla ja
  `AdvisorResponseParser` hylkäsi sen virheenä *"Muutosehdotuksessa ei ollut operaatioita"*. Yleisin
  oikea lopputulos näkyi siis punaisena virheenä. Protokollaan lisättiin kolmas vastaus `no_change`,
  kehote kertoo nyt sen olevan hyväksyttävä ("älä keksi muutosta vain vastataksesi jotain"), ja
  tyhjä operaatiolista luetaan samaksi asiaksi, koska vanhempi kehote tuottaa sitä joka tapauksessa.
  Näytöllä se on oma rauhallinen korttinsa, ei virhelaatikko.
- Epäonnistunut jäsennys näyttää nyt AI:n raakavastauksen "Näytä AI:n vastaus" -painikkeen takaa.
  Vastausta ei aiemmin säilytetty missään, joten yllä oleva vika piti päätellä virheilmoituksen
  sanamuodosta — pyyntö oli katsottavissa, vastaus ei.
- Ohitettu liike ei enää näy tehtynä kesken treenin. Edistymispalkki laski `Perform`-askelia
  katsomatta ohituslistaa, joten "Ohita" siirsi mittaria eteenpäin samalla tavalla kuin "Valmis" —
  vaikka tallennettu `GuidedProgress` on koko ajan vähentänyt ohitetut oikein. Näytöllä näkyvä luku
  ja tapahtumalokiin kirjoitettu luku ovat nyt sama luku.

### Changed
- **Sideload-päivitys asennetaan nyt sovelluksen sisällä, ei selaimen kautta.** Versiokortin
  painike avasi ennen APK:n osoitteen `ACTION_VIEW`-intentillä: selain latasi tiedoston
  Lataukset-kansioon, käyttäjä etsi sen sieltä ja avasi sen. Jokainen päivitys jätti puhelimeen
  asennuskelpoisen APK:n, eikä kukaan tarkistanut mitä oli latautunut — julkaisu kertoi koon, jota
  ei verrattu mihinkään, eikä tarkistussummaa ollut lainkaan.

  Nyt APK ladataan HTTPS:n yli suoraan Androidin `PackageInstaller`-sessioon
  (`MODE_FULL_INSTALL`, `setAppPackageName`, `setSize`, Android 12+ `USER_ACTION_REQUIRED`).
  Tiedostoa ei kirjoiteta mihinkään, mistä käyttäjä tai muu sovellus näkisi sen, joten
  tallennustilan käyttöoikeutta ei pyydetä. SHA-256 lasketaan `MessageDigest`illä samalla kun
  tavut kirjoitetaan sessioon, ja jos koko tai tarkistussumma ei täsmää julkaisuun, sessio
  hylätään `abandonSession()`-kutsulla eikä mitään asenneta. `latest.json` sai pakollisen
  `apkSha256`-kentän; ilman sitä jäsennys epäonnistuu, koska "ei tarkistussummaa" ei ole heikompi
  tarkistus vaan ei tarkistusta lainkaan.

  Asennusta ei tehdä hiljaa: Android näyttää saman "Päivitetäänkö tämä sovellus?" -vahvistuksen
  kuin ennenkin, ja allekirjoitusvarmenne ratkaisee edelleen mitä sovelluksen päälle voi asentaa.
  Ensimmäisellä kerralla avataan `ACTION_MANAGE_UNKNOWN_APP_SOURCES`, ja lataus jatkuu itsestään
  kun käyttäjä palaa — asetusnäyttö ei palauta käyttökelpoista tuloskoodia, joten lupa luetaan
  uudelleen. Versiokortti sai tilat `Downloading(versionName, progressPercent)` ja
  `AwaitingInstallConfirmation`, ja peruttu tai epäonnistunut asennus palauttaa
  "Lataa ja asenna päivitys" -painikkeen samalle julkaisulle ilman uutta tarkistusta.
  [ADR-013](docs/DECISIONS.md#adr-013-the-app-installs-its-own-update-through-packageinstaller).
- **Asennuksen tuloskutsu menee ei-exportattuun `UpdateInstallReceiver`iin, ei `MainActivity`n
  kautta.** Kutsu kantaa `Intent.EXTRA_INTENT`-arvoa, jonka sovellus *käynnistää*; exportatun
  komponentin kautta reititettynä mikä tahansa laitteen sovellus voisi lähettää sellaisen ja tämä
  sovellus avaisi sen luullen sitä Androidin asennusvahvistukseksi. `PendingIntent` on Android
  12+:ssa mutable, jotta alusta saa lisätä status-extrat — turvallista nimenomaan siksi, että se
  nimeää käynnistettävän komponentin. Ks. `docs/SECURITY.md`.
- Julkaisun release notes kertoo APK:n SHA-256:n ja ohjaa päivittämään sovelluksen sisältä; ennen
  se tunsi vain selainpolun ("open the APK below to install or update"), joka ei enää ole se tapa
  jolla päivitys tehdään. Digest tulee samasta muuttujasta kuin `latest.json`in `apkSha256`, ei
  toisesta laskennasta.
- CI laskee APK:n SHA-256:n samassa vaiheessa jossa tiedosto kopioidaan julkaistavaksi, kirjoittaa
  sen `latest.json`iin, ja julkaisun jälkeen lataa molemmat assetit takaisin ja tarkistaa että
  julkaistun APK:n digest ja koko vastaavat julkaistua `latest.json`ia. Puolittain onnistunut
  `--clobber`-lataus jättäisi muuten tarkistussumman kuvaamaan edellistä buildia, ja jokainen
  asennus hylättäisiin puhelimessa ilman että syy näkyisi täältä.
- Ohjatun treenin askellogiikka on siirretty domainiin funktioina, joita näyttö kutsuu:
  `Perform.key()`, `completedMovements(index, skippedKeys)` ja `skippedMovements(skippedKeys)`.
  `ActiveWorkoutProgress` poistettiin — se oli kirjoitettu tähän tarkoitukseen mutta jäi
  kytkemättä, joten `ActiveWorkoutScreen` rakensi samat `"$round:$position"`-avaimet käsin
  kahdessa paikassa ja luokan testit todistivat koodista, jota sovellus ei ajanut. Ei
  käyttäytymismuutosta yllä mainitun korjauksen lisäksi; kuvakaappausperustasot eivät muuttuneet.

## [Unreleased] - 2026-08-23

### Added
- **Vaalea / tumma / järjestelmä** under a new "Ulkoasu" card in Settings. The app drew itself by
  whatever the phone's dark-mode setting said and offered no way to disagree with it; now it can be
  pinned light or dark, and "Järjestelmä" keeps the old behaviour — which is also the default, so
  an install that is updated and never opens the card looks exactly as it did.
  The choice is kept in the `settings` DataStore under `theme_preference`, beside the notification
  times, the AI model and the missed-session refusal — a colour scheme is a preference, not a
  secret, and nothing here goes near the Keystore.
  Three radio options rather than a switch, deliberately: a two-state "tumma tila" toggle has no
  way to say "follow the system", so adding one would have silently dropped the behaviour every
  existing install has.

### Notes
- **The ViewModel now lives in `MainActivity`** rather than in `TreenivalmentajaApp`. The theme has
  to wrap everything the app draws, splash included, so the preference must be read above the
  navigation graph. It is the same activity-scoped instance either way — `viewModel()` resolved to
  it from both places — and it is passed down so there is one obvious place it comes from.
- **The system bars follow the choice too.** `enableEdgeToEdge` is re-applied whenever the resolved
  scheme changes, because it otherwise keeps deciding by the phone's dark-mode setting: "Vaalea"
  picked on a dark phone would leave white status-bar icons on a white background, and the clock
  with them. The navigation-bar scrims are the ones `enableEdgeToEdge()` uses by default, copied so
  that passing a style does not quietly change the bar on the API levels that still draw one.
- "Järjestelmä" resolves through `isSystemInDarkTheme()` at composition time, so a phone that turns
  dark at sunset still takes the app with it, without a restart. The two pinned options ignore that
  setting entirely, which is the whole point of choosing one.
- The stored value is the enum constant name, the same as the AI model's, and anything unrecognised
  reads as "Järjestelmä" rather than throwing: a preference written by a build with an option this
  one does not have must not stop Settings from drawing.
- Dynamic colour on Android 12+ is untouched. The preference decides light or dark; the phone still
  decides the hues.

## [Unreleased] - 2026-08-22

### Added
- **"Merkitse tehdyiksi"** on the "Väliin jääneet harjoitukset" card. It closes the missed
  sessions where they stand — `COMPLETED`, on the dates they were planned for, nothing moved — and
  it is the only one of the card's three buttons that ends the question for good.
  The card had two answers and both assumed the training was still ahead of you: "Hyväksy siirto"
  moves the whole programme forward and keeps every session, "Hylkää" writes nothing and expires at
  midnight. Neither fits a plan carrying sessions that will never be trained — the rows left behind
  while the app itself was being written — so a backlog of 35 of them asked the same question every
  single morning, and there was no way in the app to say "those are done with".
  The event log stays honest about it: each session gets `Merkitty tehdyksi jälkikäteen` under
  `EventSource.USER`, so a row that reached `COMPLETED` by being ticked off can be told from one
  that was trained. A session paused by illness gets there via `PLANNED` — the transition table's
  own route out of the pause — and that detour is an event too.

### Fixed
- **"Hylkää" now survives a restart.** The refusal lived in a `MutableStateFlow` in the ViewModel
  and nowhere else, so it lasted exactly as long as the process: installing a new APK over the app
  re-asked about the same old sessions, which is how the nag was first noticed. It is written to
  the `settings` DataStore under `missed_proposal_dismissed_for` and read back before the card is
  drawn, so a slow read cannot flash the card up on a launch where it had already been answered.
  Still keyed by the plan-zone date and still expiring at midnight: this makes "ei nyt" survive a
  restart, not become "never".
## [Unreleased] - 2026-08-21

### Added
- **The guided workout now tells the AI coach what was actually done.** Tick every movement and
  press "Valmis" and the analysis is told the session was carried out in full; stop half way and
  press it anyway and the analysis is told which movements were left. The count is written to the
  session's `COMPLETED` event, under a `guided` key in `payloadJson` — no schema change; that column
  already existed for the reschedule payload. See
  [ADR-012](docs/DECISIONS.md#adr-012-what-the-guided-workout-recorded-travels-on-the-completion-event).
- **The plan's own movements now reach the prompt** — names, sets, reps, loads, rep ranges, ramps
  and rounds, each number written with its unit. They were in the database, structured, all along;
  nothing read them for the analysis.

### Fixed
- Asked about a completed strength session, the model used to answer *"Toteutuneista liikkeistä,
  toistoista, kuormista tai kierroksista ei ole tietoa"* — and it was right. It had been sent a
  duration, an intensity and a paragraph of prose. Both halves of that gap are closed above: the
  plan is now sent, and so is what was ticked off against it.

### Notes
- **A tick says a movement was performed, not at what load.** The plan's prescription is the only
  account of that, so a set done at 45 kg where the plan asked for 55 reaches the model as 55.
  Recording real loads means an entry field per set — a larger and different feature.
- The counter stays in the card's `rememberSaveable` rather than moving to the ViewModel, because
  that is what carries it through the process being killed mid-set. It is mirrored up on every
  change so the completion, which outlives the composition, has something to record. One direction
  only.
- A session completed before this shipped carries no payload and renders **no** guided section
  rather than zero movements done. Nothing recorded and nothing done are different facts — the same
  rule the whole Oura layer follows.
## [Unreleased] - 2026-08-21

### Added
- **"Kevyet lenkit ovat kiristyneet"** — a card on the morning of an easy session, when the last
  three comparable easy sessions were each run harder than this athlete's own easy sessions usually
  are. `EasyRunDriftUseCase` and `EasyRunDriftCard`, the Phase A′ rule
  [ROADMAP.md](docs/ROADMAP.md) has carried as designed-but-unbuilt since 2026-08-16, now built to
  the specification written then.

  **It is the first card in the app with no action button under it**, and that is the design rather
  than an omission. The three sessions it reports on are already run, and lightening a session that
  is *meant* to be light is incoherent — so the rule needs no engine operation at all, the first one
  here that changes nothing about the plan. The single control, "Selvä", puts the card away for the
  day the way the readiness card's "Ei nyt" does.

  The measure is `icu_intensity` rather than `icu_training_load`: load grows with duration, so a
  long calm run scores high without being hard. Intensity is a percentage of threshold and therefore
  comparable across runs of different lengths. The baseline is the median of the athlete's own
  comparable sessions — same `WorkoutType`, same planned `intensity` — rather than a fixed band,
  because "easy means under 75 % of threshold" would be invented physiology, and this project has
  put none of that in the code anywhere else.

  Every number the claim rests on is on the card: the three measurements, the median, and how many
  sessions that median was taken over.

### Notes
- **It cost the database nothing.** No new column, no migration, no new field fetched — the first
  step of this milestone that needed neither. The plan's `intensity` has been in Plan Schema v1
  since the beginning and `CompletedRunMetrics.intensityPercent` has been stored since schema v9.
  Set against the standing rule that "adding a column does not fill it", a rule built entirely from
  columns that are already full is the cheap kind.
- **Two things the design left open, decided while building.** The comparison population is what the
  app can *classify* — completed sessions of the active plan that a stored activity was matched to —
  because a planned intensity is what makes a session comparable and only a session carries one; an
  unmatched activity is a run with no stated intention behind it. And the median is taken over that
  population **including the three under judgement**: excluding them would quietly change the claim
  from "harder than usual" to "harder than it used to be", and keeping them in is the conservative
  direction, since three drifting runs pull the median towards themselves and make the rule harder
  to satisfy rather than easier.
- **Silence is most of what it does.** Fewer than six comparable sessions produces nothing, three
  judged plus three of baseline; a session with no matched activity, or one synced before schema v9
  and so carrying no intensity, is excluded rather than counted as calm; equal to the median is not
  above it; and an easy session already completed today gets no card, because this is a word before
  a session rather than a verdict on one. Twenty of the twenty-five new tests are about those cases.
- **`EasyRunDriftWiringTest` exists for the reason the AI prompt taught.** That bug was correct
  logic reading a `StateFlow` nothing was subscribed to, and every test of the logic passed
  throughout. So the rule is proved twice: once as a pure function, and once from a real Room
  database through the ViewModel flow the Today screen collects.

## [Unreleased] - 2026-08-16 (second entry)

### Added
- **"Täydennä koko historia"** in Settings → Intervals.icu. Re-reads everything a year at a time
  and stores what comes back, stopping after two consecutive empty years — one empty year is a
  season off, two is the end of the history.
  It exists because **adding a column does not fill it**, which has now bitten three versions in a
  row: the ordinary sync looks back a fortnight, so when `avgSpeedMps` arrived at v9 every activity
  older than that kept a null forever. The field was there and nothing would ever go and get its
  value. Press this after an update that adds fields.
  Safe to repeat. Rows are keyed on intervals.icu's own activity id, so a backfill over an
  already-synced range rewrites rather than duplicates — the same property the deliberately
  overlapping sync window relies on. A failure part-way keeps what already arrived and says how far
  it got: this is a top-up, not a transaction.
- `icu_atl` and `icu_ctl` — acute and chronic training load, i.e. fatigue and fitness — stored at
  schema version 10. **Nothing reads them yet**, and they are here because a use is *named*: the
  fatigue rule sketched in [ROADMAP.md](docs/ROADMAP.md), which asks whether total load has outrun
  what the plan assumed. The accessor arrives with that rule.

### Notes
- This settles a question worth recording: **should the app store the raw JSON of every activity,
  just in case?** No. intervals.icu is the system of record and this app is a cache, so when a
  field turns out to be wanted the answer is a column and a backfill rather than a hoard. Storing
  all 183 fields would also put `icu_weight`, `icu_resting_hr` and `lthr` in the database with
  nothing reading them — precisely what PRIVACY.md's minimalism exists to prevent, and "just in
  case" is the exact argument it was written against. The rule the project now follows: **store a
  field once a use for it can be named.**
- The backfill's progress is a count, not a percentage. The walk does not know how many years it
  will take until it meets the end of the history, so a bar would be inventing a denominator.

## [Unreleased] - 2026-08-16

### Changed
- **The run line now shows the numbers the runner recognises.** The raw-data screen answered the
  question it was built for, and the answer was that all three durations are real:

  ```
  Kello: 5:23 /km (max 4:30 /km) · 9,52 km · nousu 77 m
  aktiivinen 51:15 · liikkeessä 53:46 · yhteensä 1:02:31
  syke 148 (max 174) · askeltiheys 162
  842 kcal · kuormitus 62 · intensiteetti 77 %
  ```

  The watch's own figures lead, intervals.icu's sit beside them, and none is hidden. Showing only
  `moving_time` — the one number the runner had never seen — is what made the app disagree with the
  wrist for no visible reason.
- **The watch's own duration needs no FIT file.** `average_speed` is anchored to it, so
  `distance / average_speed` gives it back: 9520 / 3.096 = 3074.9 s = 51:14.9, against a Suunto
  that reported 51:14.8. `pace` is a *different* speed for the same run — distance over
  `moving_time` — and the two disagree by the 151 s intervals.icu adds when it recomputes moving
  time from the stream. Both are m/s despite one being called `pace`.
- **`icu_recording_time` is the total that matches the watch**: 3751 against 1:02:31 exactly.
  `elapsed_time` was 3752 and the interval row said 3753, so of the three near-identical totals
  only this one is the watch's.

### Fixed
- **Cadence was half.** `average_cadence` is cycles per minute for one leg, so a run at about 162
  steps per minute was displayed as 81 — a figure no runner would recognise. Proved rather than
  assumed: `distance / (cadence × 2 × minutes)` reproduces the response's own `average_stride` of
  1.0899 m exactly, where `× 1` does not. Doubled at display, so the stored value stays the one the
  service sent.
- **Pace was truncated where it should round.** 338.87 s/km printed as 5:38 where both the watch and
  intervals.icu said 5:39 — a one-second gap that looked like a different measurement rather than a
  different rounding.

### Added
- Five more fields: `average_speed`, `max_speed`, `icu_recording_time`, `hr_load` and `trimp`,
  bringing the request to twenty-three of the 183 the schema declares. The first three are what the
  durations above are built from; the last two are intervals.icu's other load figures, kept because
  the analytics are the reason to be on this service at all.
- Room schema version 9 for those columns, by auto migration. The instrumented test proves an
  activity synced before them gets nulls — `avgSpeedMps` in particular, because without it that
  activity cannot show the watch's duration until it is fetched again.

### Notes
- `icu_intensity` came back as **77.13892** — already a percentage, which settles for this account
  the question the previous entry left open. The fraction branch stays, because one account's data
  is not the specification and the bound costs nothing.
- One sub-second disagreement remains and is left alone: 322.98 s/km is shown as 5:23 where the
  Suunto shows 5:22, because the watch truncates and this app rounds. Rounding is the correct
  reading of 322.98 and matching both conventions at once is impossible.
- The screenshot baselines now use that real run's numbers rather than invented ones, since what
  they pin is precisely the layout those three disagreeing durations forced.

## [Unreleased] - 2026-08-15 (fourth entry)

### Added
- **A raw-data screen for intervals.icu** — *Asetukset → Intervals.icu → Kehitystyökalu → Näytä
  raakadata*. It shows the response body as the server sent it, with a button to copy the JSON.
  It exists to answer a specific question: the watch reports a 51:14.8 run with 11:16.5 of pause
  and 1:02:31 total, intervals.icu shows 53:46 moving time, and the app shows a third figure again.
  Guessing which field is which would be the wrong way to settle that.
- Two things make it a diagnostics tool rather than a second way to read training:
  **it sends no `fields` parameter**, so all 183 fields arrive instead of the eighteen the sync
  asks for — a duration field the app does not currently read is therefore *visible* — and
  **nothing is parsed**. The bytes are kept as text from the socket to the screen; no DTO is
  involved, so no field can be dropped, renamed, converted or rounded on the way.
- The documented `GET /api/v1/activity/{id}` is offered too, with `intervals=true`, so one activity
  can be inspected in full including its lap breakdown. Pick it from a list of what has been synced.

### Notes
- **Pretty-printing only inserts whitespace.** The obvious implementation,
  `JSONObject(body).toString(2)`, is the wrong one here: re-parsing reorders keys, can turn `1.0`
  into `1` or lose the last digits of a large integer, and silently drops a duplicate key. On this
  screen every one of those would look like a finding about intervals.icu rather than a bug in the
  printer. `prettyPrintJson` walks the text and inserts newlines and indentation between tokens,
  copying string contents through untouched — including escaped quotes, which is why it tracks
  them. A body that is not JSON at all comes back unchanged rather than mangled.
- **The API key cannot reach the screen or the clipboard.** The displayed request line is built
  from the URL's path and query, neither of which can carry a credential — it travels in an
  `Authorization` header attached inside the client and recorded nowhere. The copy button takes the
  response body alone, so the endpoint line and status are not pasted along with it and what lands
  on the clipboard is JSON that parses. Both properties are asserted by tests rather than assumed.
- The body is drawn one line per row in a `LazyColumn` rather than as one enormous `Text`, so a
  response of any size renders without laying out every glyph at once.
- **Nothing about the training logic changed.** Which duration the app uses, which pace it shows,
  how a session is matched — all untouched. This step is only about being able to see the data.

## [Unreleased] - 2026-08-15 (third entry, after the first real sync)

### Added
- **Everything the watch can say about a run, grouped so it can be read.** Three more fields come
  down now — `average_cadence`, `icu_intensity`, and `icu_distance` alongside the plain `distance`
  — bringing the request to eighteen fields of the 183 the schema declares.
  The session line is no longer one long string. Nine numbers joined by `·` stop being read at
  about the fourth, so they are split by the question each answers:

  ```
  Kello: 6:07 /km · 38 min · 6,2 km · nousu 42 m
  syke 148 (max 171) · askeltiheys 168
  540 kcal · kuormitus 78 · intensiteetti 78 %
  ```

  A line with nothing in it is not drawn at all — a treadmill run with no strap has no middle line
  rather than an empty label. The week list keeps a one-line compact form, because scanning a
  fortnight wants pace and distance and nothing else.
- That last line is the point of this change. Calories, training load and intensity are what make
  an **easy** 5 km distinguishable from a hard one of the same distance and duration — the app can
  now see that a session planned as easy was run harder than usual. Nothing acts on it yet; it is
  captured so that something can.
- Room schema version 8: `avgCadence` and `intensity` on `intervals_activities`, by auto migration,
  with an instrumented test proving an activity stored before them keeps its values and gets nulls
  — not a zero cadence, which would read as a runner who never took a step.
- Four screenshot baselines for the new layout: the full three-line form, the compact one, a
  sparse run where two of the three groups are empty, and the whole Today screen with Oura's line
  and the watch's line under the same session.

### Notes
- **Two of these fields are documented nowhere** — not in the schema, which carries no description
  for either, nor in intervals.icu's cookbook or forum.
  `distance` vs `icu_distance`: both are fetched and `icu_distance` is preferred with a fallback,
  which is a stated preference rather than a choice dressed up as fact.
  `icu_intensity`'s **scale** is likewise unstated — a service of this kind reports intensity
  either as a fraction (`0.78`) or a percentage (`78`). It is therefore stored **raw** and read as
  a percentage only where it is displayed: at or below 3.0 it is scaled, above that it is already
  one. The bound is what makes that safe — a session at 300 % of threshold and a fraction above 3.0
  are both impossible, so no real value is ambiguous. Keeping the raw value means that if this
  reading is ever proved wrong it is one function to fix and no stored data to migrate.
- **A real intervals.icu account is now connected and syncing**, confirmed today. The caveat in the
  entry below is therefore obsolete. What remains unchecked is whether every displayed number
  matches intervals.icu's own interface for the same activity, field by field.

## [Unreleased] - 2026-08-15 (later the same day)

### Changed
- **Strava is gone; the watch's runs now come from intervals.icu.** Strava paywalled its API in
  June 2026 — developer access requires an active subscription — so the integration that shipped
  hours earlier was removed entirely rather than left to rot. Nothing about it survives: no
  package, no card, no callback activity, no exported component, no table.
- Suunto's **own** API was ruled out before intervals.icu was chosen. Its developer FAQ says
  access is for "companies/organizations" and that "we do not provide this for personal use", so a
  one-person training app does not qualify however good the data is.

### Added
- **Intervals.icu, with a personal API key rather than OAuth.** Paste the key from intervals.icu's
  Developer Settings into **Asetukset → Intervals.icu** and press *Tallenna avain*; the app tests
  it immediately and there is a **Testaa yhteys** button underneath for checking again later. See
  [INTERVALS_SETUP.md](docs/INTERVALS_SETUP.md).
  This is a *smaller* thing than the OAuth flow it replaces, not merely a different one: no browser
  round trip, no `state` to validate, no refresh token that must not be spent twice, and **no
  exported activity at all** — `OuraCallbackActivity` is once again the app's only exported
  component. The key is held under its own Android Keystore alias, excluded from backup, never
  logged, and never redisplayed once saved.
- **Two measurements Strava never gave us**: `calories`, which Strava's summary endpoint omitted
  entirely, and `icu_training_load` — intervals.icu's own load figure for the activity. The session
  line now reads `Kello: 6:07 /km · 38 min · 6,2 km · syke 148 (max 171) · 540 kcal · kuormitus 78`.
  Labelled "Kello" rather than by the service, because the reader cares that it is the watch's own
  recording; which pipe it travelled down is plumbing.
- Room schema version 7: `intervals_activities` in, `strava_activities` out, by an auto migration
  with a `@DeleteTable` spec. An instrumented test runs it against a version-6 database holding a
  plan, an Oura summary **and** a Strava row, and checks that the neighbours survive untouched, the
  new table takes a string id, and the old table is really gone.

### Notes
- The activity id is a **string** here (`i84461234`) where Strava's was a number, and that id is
  what makes the sync idempotent: rows are upserted on it, so the deliberately overlapping fetch
  window rewrites rows instead of duplicating them. Nothing compares start times or distances to
  guess whether two records are the same activity.
- The request names the fifteen fields the app reads, of the **183** the `Activity` schema
  declares, so the rest are never sent.
- `pace` exists in the API and is deliberately **not** read: its unit is undocumented, and a number
  whose unit is a guess is worse than one computed from two that are known.
- `source` is stored (`SUUNTO`, `MANUAL`, `UPLOAD`, …) and never filtered on. A run uploaded by
  hand is still that run.
- Measured against the real service before writing the error handling: both an unauthenticated
  request and one with a wrong key answer **401**, so the app's message for that case covers both.
- **No real intervals.icu account has been connected yet.** The tests run against a local
  `com.sun.net.httpserver`, so what is verified is this app's behaviour rather than that
  intervals.icu's answers match the shapes it expects — the same position the Oura client was in
  before its first real login.
- The specification is vendored at
  [`docs/api/intervals-icu-openapi.json`](docs/api/intervals-icu-openapi.json), fetched from the
  service's own `/api/v1/docs`, so every field name and type above was read rather than remembered.

## [Unreleased] - 2026-08-15

### Added
- **Strava, connected the same way Oura is.** Settings takes the Client ID and Secret of an API
  application you register once at strava.com/settings/api, stores them under their own Android
  Keystore key, and the login happens in a browser — see [STRAVA_SETUP.md](docs/STRAVA_SETUP.md)
  for the steps on Strava's side. The one field that must be exact is **Authorization Callback
  Domain: `localhost`**, because Strava validates the redirect's host and the app's redirect is
  `treenivalmentaja://localhost/strava`.
- **Pace, which Oura cannot supply.** A matched run now shows a Strava line — `5:32 /km · 38 min ·
  6,2 km · syke 148 (max 171) · nousu 42 m` — under Oura's own. Two lines rather than one merged
  number: the ring and the watch recorded the same run, and averaging them would hide which device
  said what. Pace comes from moving time, not elapsed, so a pause at a crossing does not slow the
  run on paper.
- Runs match to planned sessions through the **same** use case Oura's workouts go through — same
  day, nearest in time, one-to-one, and the sport has to fit. Strava's `SportType` is a closed
  documented enum where Oura's `activity` is free-form, so `Run`, `TrailRun` and `VirtualRun` were
  added to the running vocabulary and the ski variants to theirs.
- Room schema version 6: the `strava_activities` table, by auto migration, with an instrumented
  test that runs it against a populated version-5 database and then writes to the new table.

- **The readiness number finally reaches the plan** — as a question, never as an action. A session
  left undone on a day whose readiness was below 70 raises a card the next morning: *"Eilen jäi
  treeni tekemättä ja palautuminen oli 57. Siirretäänkö ohjelmaa eteenpäin, vai aloitetaanko tämä
  päivä kevyemmin?"* A poor reading on a day that has a session offers lightening alone — moving a
  whole programme on one morning's number would be a bigger claim than one measurement supports.
  Both buttons call operations that already existed, so nothing new can happen to the plan and
  every change lands in the event log with an author beside it.
  This is deliberately the opposite of the readiness indicator deleted back in the Oura milestone.
  That one showed the same verdict daily because nothing ever produced a different one; this one
  cannot speak without a measurement *and* a session to speak about. A day the ring was not worn
  produces no card at all — 15 unit tests, most of them about mornings that must stay quiet.

### Notes
- **No real Strava account has been connected yet.** The tests run against a local
  `com.sun.net.httpserver`, exactly as the Oura client was built, so what is verified is this
  app's behaviour rather than that Strava's answers match the shapes it expects. This is the same
  position the Oura client was in before a real login, and it is recorded rather than glossed.
- Calories are deliberately not read from Strava: the summary endpoint carries none, and fetching
  each activity's detail to add a number nothing decides on would spend the rate budget for
  nothing.
- The Strava flow has **no PKCE**, because Strava's token endpoint accepts no `code_verifier` and
  authenticates the exchange with the client secret. `state` therefore carries the whole burden of
  tying a redirect to a request this device made, and it is checked before the code is read.
- [PRIVACY.md](docs/PRIVACY.md) is revised: `www.strava.com` is a fifth destination data goes to,
  the scope requested is `activity:read_all` and nothing else, and there is no write scope at all.

## [Unreleased] - 2026-08-13

### Added
- **Readiness next to each day in the week list**, colour-banded the same way the Today card's
  readiness label already is (green ≥85, yellow ≥70, red below). Reads the same
  `oura_daily_summaries` table over the same 28-day span the week already scrolls back through, via
  a new `observeRecoveryRange` query — no new fetch, no new permission. A day Oura has never
  answered about shows no badge at all, same as a badge-free rest day; this is not a verdict drawn
  from a missing measurement.
- `docs/INSPIRATION.md`: ideas from a friend's training app worth a look if the AI advisor in
  [ROADMAP.md](docs/ROADMAP.md) is ever designed — not scheduled work, just written down so it
  is not lost.

## [Unreleased] - 2026-08-10

### Added
- **What actually happened, under what was planned** — on both the Today card and the week list. A
  session Oura recorded now shows its real duration, distance, calories and heart rate beneath the
  plan's own line. In the week the numbers sit in the **collapsed** header, because scanning for
  what was actually done is the reason to be on that screen and it should not cost a tap per day.
  Opening the week also refreshes from Oura, which previously only the Today screen did — "38 min · 6,2 km ·
  431 kcal · syke 142 (max 168)". Only the measurements that exist are drawn: a strength session has
  no distance, and a ring that was charging has no heart rate.
- Completed Oura workouts are tied to planned sessions automatically: **same day, nearest in time**,
  one-to-one. Deliberately not by Oura's `activity` word, which is a free-form string this app has
  never seen real values of — a wrong pairing is visible and correctable, while a missing one looks
  like Oura never recorded the session at all.
- **Heart rate**, which took a new permission. Oura puts none on a workout object, so the average
  and maximum are reduced from the `heartrate` time series over the workout's own window. That
  required adding the `heartrate` scope, which means **reconnecting Oura** — an authorization keeps
  the permissions it was granted with — and a revision to the published privacy policy, which
  previously said the app did not request heart-rate data.
- Room schema version 5: `distanceMeters`, `avgHeartRate` and `maxHeartRate` on `oura_workouts`, by
  auto migration, with an instrumented test that runs it against a version-4 database with a row in
  it. A workout stored before the columns existed keeps its values and gets nulls, not a reset.
- **"Mitä Oura palauttaa" in Settings.** Runs the same requests a sync runs, stores nothing, and
  reports how many rows each collection returned plus one line per workout — day, activity, start,
  calories and whether Oura auto-detected it or someone entered it. It exists because of a dead end
  this app actually hit: a session visible in Oura's own app and absent here, with no way from the
  outside to tell whether the API had not returned it, whether parsing had dropped it, or whether it
  had been stored and not drawn. The phone makes the requests, so the phone answers — and nobody has
  to hand their Oura credentials to anyone to find out.
- **Oura workouts that belong to no planned session are listed too**, under "Muu Ourassa kirjattu
  liikunta". A spontaneous walk has no session, and one the matcher could not place would otherwise
  vanish — which from the outside looks identical to never having fetched it.
- **A recovery reading on the Today screen, with a measurement behind it.** The indicator that was
  removed for being a constant is back, now showing today's Oura readiness score and a word for it.
  It tells four situations apart, because what they mean differs: Oura not connected shows no
  indicator at all, a day nothing has been fetched for says so, a day Oura answered about with no
  score says "ei tietoa", and a reading shows the number with sleep and activity beside it. The
  third one is the whole point — the ring was not worn, and a zero there would read as a verdict.
  The word describes the score and never what to do about it; advice without a measurement is what
  the old card was stripped for.
- **A daily background sync** (WorkManager), and a fetch when the Today screen opens. Both reach
  back several days rather than one, because Oura revises a day once the night has been processed
  and a phone that was offline over a weekend would otherwise keep a permanent hole. The worker is
  scheduled only while Oura is connected — one that woke daily to find no token would be a battery
  cost with no possible result.
- The Oura tables finally have a writer. `OuraRepository` is the only thing that writes them, and
  the screens observe Room rather than the network, so a failed sync leaves the last known reading
  on screen instead of an error.
- **The week list is a calendar you can scroll.** It opens on today, goes four weeks back and four
  forward — further when the plan reaches further — so a session done last week can be looked up
  with the numbers Oura recorded for it. Days outside the current week earn a row by having
  something on them, which keeps scrolling useful rather than a month of "Lepo".
- Day headings now carry the real weekday and date. They were positional before — the third row was
  always called "Keskiviikko" — which was right only in a week starting on a Monday, and silently
  wrong every other day.
- The Oura sync window went from four days to fourteen, so scrolling back finds data rather than
  blanks. Heart rate is now fetched per workout instead of one request spanning the whole window:
  over a fortnight that span would have downloaded every night in between to find the twenty samples
  belonging to a run.
- **Oura is set up entirely on the phone.** Settings asks for the Client ID and Client Secret of an
  application registered in Oura's developer portal, stores them encrypted beside the tokens, and
  connects from there. Nothing needs a PC, a checkout, an `.env` file or a file copied from a
  computer — which matters because this app is installed by opening a GitHub release link on the
  phone, and a build from CI has no `.env`. As written before, the feature could never have
  connected on the only build its owner actually runs. A side effect worth naming: the published
  APK now carries **no Oura secret at all**.
  See [ADR-009](docs/DECISIONS.md#adr-009-the-oura-client-credentials-are-entered-in-the-app-not-compiled-into-it).
- **"Yhdistä Oura" in Settings**, and the whole OAuth2 flow behind it: the authorization request
  with PKCE (S256), `state` validation, the code exchange, encrypted token storage, and renewal on
  `401`. The card tells four situations apart, because what to do about them differs: no credentials
  yet (the two fields, with instructions), a disconnected one, a login waiting on a browser, and a
  connected one, which offers only the way out.
- Tokens and the pending PKCE verifier are encrypted with AES-256-GCM under a key generated inside
  the Android Keystore, which cannot be extracted from the device. **Not**
  `EncryptedSharedPreferences`, which the documents specified: that library was deprecated in April
  2025 and receives no fixes, including for the Keystore crash reported against it. See
  [ADR-008](docs/DECISIONS.md#adr-008-android-keystore-directly-rather-than-encryptedsharedpreferences).
  They are excluded from cloud backup and device transfer, because the key does not travel with a
  backup and restored ciphertext would be unreadable.
- The verifier survives the browser round trip on disk rather than in memory. Android may kill the
  process while a browser is in front of it, and a verifier lost that way turns a completed login
  into a failed exchange.
- Token renewal as an OkHttp `Authenticator`: a `401` refreshes once and retries the request. Two
  requests failing at the same moment produce **one** refresh — Oura rotates refresh tokens, so the
  second would otherwise spend an already-invalidated one and log the user out for being busy.
- An Oura API V2 client (`data/oura`) — readiness, sleep, activity and workouts between two dates,
  paged to the end and mapped onto the two Oura tables that have sat empty since they were created.
  All four collections are the same request and the same `{data, next_token}` envelope in the
  specification, so they are one generic paged fetch rather than four endpoints.
- Every documented Oura status code has a type of its own, carrying a Finnish message and a
  `canRetry` flag. `403` is the one worth naming: it is not a service failure but the user's Oura
  subscription having expired, so it is a state to show rather than something to retry.
- A day the ring was not worn survives as a day without a score. Oura answers with a document whose
  `score` is `null`, not with no document, and it is stored as a row with `null` in it — the
  recovery card's whole design turns on being able to say "ei tietoa" about a day that exists.
- 30 unit tests against a local `com.sun.net.httpserver`, covering the bearer header, the date
  parameters, paging, every error code, an unreachable host and a body that is not JSON. **The
  fixtures they stand on are derived from the vendored specification, not captured from Oura** —
  unlike the exercise-guide fixtures next to them, which are real responses. Nothing here has met
  the live service yet, and nothing in the app calls the client.

### Fixed
- **A walk was being shown as strength training.** Matching used time alone, on the grounds that
  Oura's `activity` was a free-form string nobody had seen real values of. Two weeks of real data
  settled that: eleven `walking` entries against five `strengthTraining` ones, so the workout
  nearest a 09:00 strength session was almost always a walk — a 1.8 km stroll appeared as that
  morning's strength training, and a day whose only Oura entry was a walk claimed a session that
  never happened. A workout now has to be the right *kind* of thing, compared with case and
  punctuation stripped so `strengthTraining` and `strength_training` mean the same. `houseWork`,
  which Oura really does return, matches nothing.
- The other half of that: workouts no session claims are now listed under their own day in the
  calendar, with Oura's own word for them in Finnish. Stricter matching without this would simply
  have made every walk vanish.
- A heart rate was shown for a session that had none. Oura samples continuously, so a workout's
  window always holds *something*: a strength session Oura itself marked "heart rate data
  unavailable" came out as "syke 75 (max 81)" from a couple of background readings. Fewer than five
  samples in the window is now treated as no reading at all.
- **Today's Oura data was never requested.** The collections do not agree on whether `end_date`
  includes that day: asking 08-06..08-10 against a real account returned five days of readiness and
  sleep but no workouts after 08-09, and four days of activity. The client now asks one day beyond
  the range it was given, which is the only request that means "up to and including this day" for
  all of them. Found through the diagnostics screen, not by reading the specification — which says
  nothing about it.
- The app only fetched from Oura when a screen first entered composition, so one left open in the
  background since morning never asked again — a workout recorded at 07:38 was simply not there when
  the screen was composed, and nothing looked afterwards. Both screens now refresh on **resume**,
  which is exactly when the answer is likely to have changed. Found from a real morning session that
  Oura had and the app did not.
- Disconnecting Oura also deleted the client credentials, so reconnecting would have meant pasting
  the Client ID and Secret again. The token store emptied its whole preferences file rather than the
  keys it meant to. Caught by an instrumented test on a device: the in-memory fake the unit tests
  use kept the credentials, so the unit test asserting they survive a disconnect passed while the
  real store wiped them.
- A stray `treenivalmentaja://oauth2callback` deep link aimed at a build with no Oura credentials
  left Settings offering "Yritä uudelleen" for a connection that cannot be attempted at all. Found
  by firing a forged redirect at the exported activity on the emulator, not by reading the code.
  Such a redirect is now ignored, and the card keeps asking for the credentials it still needs.
- A third correction to the documents, from measurement: `API_INTEGRATIONS.md` said workouts synced
  into Oura from elsewhere appear in `/workout` with `source` naming the origin. They do not. A run
  imported from Strava was visible in Oura's own app and absent from a request covering that day,
  while a walk from the same day came back. The document now records the evidence instead of the
  claim, and what is still unknown about it.
- Two things the documents promised that turned out not to exist. `AUTHENTICATION.md` said
  disconnecting calls Oura's revoke endpoint; the vendored specification declares **no `/oauth`
  paths at all**, so there is nothing to call and inventing a URL would have been worse than saying
  so. Disconnecting now deletes everything locally and the card says where to revoke the
  application itself. And the token storage those documents named has been deprecated since April
  2025 — see above.

### Changed
- OkHttp is now a declared dependency instead of one inherited from Coil, and the Oura client is
  built on it rather than on the Retrofit the roadmap had promised since before this app had any
  networking at all — see
  [ADR-007](docs/DECISIONS.md#adr-007-okhttp-not-retrofit-for-the-oura-client). It costs no APK
  bytes, because Coil already put 4.12.0 inside the APK; what it buys is the `Authenticator` that
  token renewal is specified in terms of. Measured cost of the whole Oura milestone so far:
  148,544 B over the last build before the milestone, of which WorkManager — its only new
  dependency — is most. Measured with `clean` on both sides: an incremental build of the same source
  came out 260,082 B larger, so per-stage figures taken that way earlier were noise.

## [Unreleased] - 2026-08-09

### Added
- Exercise guides. Tapping a movement opens a sheet with an animation or picture, numbered
  instructions, target muscles and equipment, so a name you do not recognise no longer sends you
  to a search engine. Fetched when the sheet opens and **never stored**: the image loader is given
  no disk cache at all, nothing goes in Room, and the only cache is a map that dies with the
  process. Credit is shown wherever guide data appears, as the sources require.
- A second guide source, **wger** (<https://wger.de>, CC-BY-SA), alongside ExerciseDB — because
  neither has everything. ExerciseDB carries an animation for all 1500 of its movements but has no
  plank, side plank, plain squat, bird dog or cat-cow *at all*; wger has every one of them, though
  only a third of its movements carry a picture and those are stills. A plan pins each movement to
  whichever source has it, and a reference is resolved by the provider it names — never quietly by
  the other. Without a reference both are searched at once, and one being down no longer hides the
  other's answer.
- `guide` on an exercise — `{ "provider": "exercisedb" | "wger", "id": "…" }`, the plan author's
  pointer into a catalogue. Optional and backwards compatible; an unknown `provider` is an import
  error rather than something ignored. No Room migration.
- An exercise without `guide` is searched by name, and the result is offered as a suggestion that
  has to be picked. The service's fuzzy matching does not miss when there is nothing to find —
  `cat cow` comes back as "cable squat row" — so every result is filtered down to names that
  contain each word of the query. A Finnish name matches nothing, which is the honest answer, and
  the sheet says to add a `guide` instead.
- Exercises are shown as the plan wrote them: the prescription under each name
  (`3 × 12 · 17,5 kg`, `10 / puoli`, `30 s`), and a clock for timed movements that runs once per
  side or per set. Previously the screens printed only the name, and decided a movement was timed
  by looking for "lankku" in it.
- `setPlan` on an exercise, for sets that differ from each other — a ramp such as 25/35/45/55 kg,
  or reps that fall as the load climbs. Optional and backwards compatible; no Room migration.
- Import asks where a plan should land: on the file's own dates, or shifted so day one is today.
- Settings says whether the installed build is the one GitHub Actions last published, and offers
  the download when it is not.
- [docs/EXERCISE_GUIDE.md](docs/EXERCISE_GUIDE.md) — the plan for per-movement animations and
  instructions, including the ExerciseDB terms that constrain it.

### Changed
- Each screen is now a stateless `…Content` taking plain values and callbacks, plus a thin wrapper
  that reads the ViewModel and owns the parts only a real screen can do — the file picker, the
  clipboard, the notification permission. No behaviour changed and no baseline moved; what changed
  is that a test can render a whole screen at all. Five now do, including two states that are
  awkward to reach by hand: a rest day, and Settings without the notification permission.
- `WorkoutViewModel` has its first tests, ten of them, over the guide sheet's states and the import
  confirmation — the one place in the app where saying yes destroys data. `RescheduleAlarmsUseCase`
  became `open` for the same reason `ReminderScheduler` already was: so a test can hand it a no-op
  instead of driving DataStore from a virtual clock.

### Fixed
- Correcting the programme you are running cost you the record of running it. Re-importing the
  same `plan.id` with any change was refused outright — "poista vanha suunnitelma ensin", for which
  there was no button — so the only way through was "Palauta esimerkkidata", which deletes every
  session status and the whole event log. Meanwhile importing a plan with a *different* id deleted
  all of that without asking at all: strict about the harmless case, silent about the destructive
  one. Now a corrected document updates the sessions in place and keeps everything recorded against
  them, a genuine replacement asks first and says how many marked sessions it would destroy, and
  neither happens without a yes.
- The Week view offered to start a hold for a session days away. `WorkoutDetails` is the read-only
  rendering shared by the expanded Week row and the Today card, and its own description says it
  shows "what it is, not what to do about it" — a running clock was never that. The clocks are now
  only in the started workout, where they are sequenced and where finishing one means something.
  The hold's duration still shows on every list, and the guide is still one tap away from both.
- The countdown lost its face. Moving timed movements onto the plan's own fields replaced the
  full-screen clock — a 240dp ring emptying around a 72pt number — with a line of small text, and
  dropped the notification sound at zero. A hold is done with your eyes shut or your face at the
  floor, so both are back, now for every timed movement rather than only the ones with "lankku"
  in their name.
- A started workout is a sequence again, and behaves like one. You could tick movements off in
  any order, including skipping ahead, and a finished clock left a "Valmis / Alusta" line to read
  and dismiss. Now the last round of a movement's clock ticks it off by itself, only the movement
  you are on can be ticked, and only the last one ticked can be unticked — which walks the
  session back one step at a time and resets that movement's clock. Everything below the current
  movement is visibly locked.
- A started workout lost half of what the plan knows about it. "Aloita ohjattu treeni" rendered
  its checklist from the session's free-text description rather than the plan's `exercises`
  array, so mid-session there were no guide links, no prescriptions, and the movement names came
  back with their numbers glued on ("sivulankku 20 s/puoli"). Worse, the checklist used a
  single-shot timer that decided a movement was timed by looking for "lankku" in its name: a
  per-side hold offered one clock for two sides, and there was no way to time the second. It now
  draws from the same movements the read-only list does, so a side plank asks for Vasen and then
  Oikea, and every movement is still tappable for its guide while the workout is running. Plans
  with no `exercises` array keep the old description-parsing path.
- App startup rewrote the training calendar. With a plan whose dates had passed, the engine
  counted every past session as missed and shifted the whole programme so week 1 landed on today,
  restarting an eight-week plan from the beginning on every launch — including the one after an
  app update.
- A replaced plan kept sending its own reminders: alarm scheduling had no active-plan filter, so a
  superseded programme notified beside the current one.
- Importing now deletes the plan it replaces instead of deactivating it, so the database stops
  growing and dead sessions cannot hold alarms.
- The week row's press ripple grew to the height of the expanded card, sweeping a grey circle
  across the exercise list.
- `tools/backup-db.ps1` did not work in either direction; every step of the copy path was wrong.

## [Unreleased] - 2026-08-08

### Added
- Week rows expand on tap to show the session's content, animated with `expandVertically` so the
  rest of the week is pushed down rather than jumping. State survives scrolling and process death.
- `WorkoutDetails`, one read-only rendering of a session's content, shared by the Today card and
  the expanded Week row instead of being written twice.
- Roborazzi screenshot tests reinstated: 10 baselines over the Today and Week cards, the recovery
  card, every status badge and the expanded row.
- `BootReceiverTest`, `MigrationGuardTest`, and `PlanValidatorTest` cases for exercises that carry
  neither reps nor a duration.
- GitHub Actions builds and publishes a signed test APK to one rolling prerelease on every push
  to `main` that touches code, and on demand from the Actions page. Installing the next test
  build needs only a phone; the permanent link is in the README. The APK is signed with the same
  debug key as local builds, restored from a secret, so it updates in place instead of demanding
  an uninstall — verified against the published binary, not assumed.
- `tools/generate_icons.py`, which rebuilds every launcher and splash raster from the master
  artwork, and `tools/backup-db.ps1`, which copies the database off a device and reports its
  schema version.

### Fixed
- **The app could not start.** Every image in the repository had been destroyed by being written
  through a text encoding — each byte `>= 0x80` replaced by U+FFFD — so `splash_logo` threw on
  launch. No intact copy existed in any commit. All assets regenerated from the master artwork.
- A missing Room migration silently emptied the database; `fallbackToDestructiveMigration` is gone
  and a missing migration now fails loudly with the data intact.
- `BootReceiver` re-armed alarms for any intent it was handed, without reading `intent.action`.
- The ICS parsers split a running session's description on its commas and emitted the sentence
  fragments as exercises, failing the import 16 times in an eight-week plan.
- The week row's press ripple was unbounded and drew a grey circle over the content; Material3 1.3
  no longer supplies a bounded ripple through `LocalIndication`.
- `TrainingEngineTest` did not compile: it built entities with fields that do not exist.

### Changed
- `minSdk` raised 24 → 26. Core library desugaring dropped with it, worth 1.2 MB of APK, along
  with both `InlinedApi` warnings and a dead `SDK_INT` guard.
- Documentation moved from `app/applet/` to the repository root and `docs/`, where the paths cited
  from the source actually point. `GEMINI.md` merged into `AGENTS.md`.
- `AGENTS.md` gained two rules learned the hard way: never write a binary through a text tool, and
  verify images by rendering them — `aapt2` compiled the corrupted icons without complaint.

## [Unreleased] - 2026-08-05

### Added (Room persistence)
- Room database: `TrainingPlan`, `WorkoutSession`, `SessionEvent`, `OuraDailySummary` and
  `OuraWorkout` entities, their DAOs, type converters, and `AppDatabase` (schema version 1).
- `TrainingRepository` — the single entry point to training data. Enforces the session state
  machine and writes an immutable `SessionEvent` in the same transaction as every accepted
  status change.
- Rescheduling creates a new session row linked by `originalSessionId` instead of rewriting a
  date in place.
- Training plan JSON import from a file (Storage Access Framework) and from the clipboard, in the
  Settings screen. Validated against `docs/PLAN_SCHEMA.md` before anything is written, with
  per-field Finnish error messages and duplicate/conflict detection.
- First-launch seeding with a starter week, routed through the real importer.
- 41 unit tests: state transitions, event-history accumulation, JSON validation (valid, broken,
  duplicate), import conflicts, reschedule chain, and cascade delete. Room tests run in memory
  under Robolectric.
- Core library desugaring so `java.time` is usable on the declared `minSdk` 24.

### Changed (Room persistence)
- `WorkoutViewModel` observes a Room `Flow` instead of `MockData`; `MockData` removed.
- `WorkoutStatus` replaced by `domain.SessionStatus`; `WorkoutType` moved to `domain`.
- Today screen gained a "Merkitse tehdyksi" action and hides actions on closed sessions.

### Added
- Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) pinned to Gradle 9.6.1 with a
  distribution checksum. All documented commands now use `./gradlew`.
- `docs/PLAN_SCHEMA.md` — Treenivalmentaja Training Plan Schema v1 (JSON import format).
- ADR-006 "No separate backend in the MVP"; ADR-004 marked Superseded.
- `SessionEvent` entity (immutable, append-only session history) in the data model.
- `OURA_CLIENT_ID` / `OURA_CLIENT_SECRET` documented in `.env.example`.

### Changed
- Package renamed `com.example` → `fi.merilainen.treenivalmentaja`; `applicationId` changed from
  `com.aistudio.treenivalmentaja.bvcxw` to `fi.merilainen.treenivalmentaja`.
- Session state model expanded to `PLANNED`, `NOTIFIED`, `STARTED`, `COMPLETED`, `SKIPPED`,
  `RESCHEDULED`, `REPLACED_WITH_LIGHTER_VERSION`, `PAUSED_DUE_TO_ILLNESS`, `CANCELLED`
  (replacing `LIGHTER` and `MOVED`), with a normative transition table.
- Rescheduling no longer rewrites a session's date in place: the old row closes as `RESCHEDULED`
  and a new row references it via `originalSessionId`.
- `AUTHENTICATION.md`, `ARCHITECTURE.md`, `SECURITY.md`, `SETUP.md`, `README.md` updated for the
  no-backend design (in-app OAuth exchange with PKCE, secret via `BuildConfig`, tokens in
  `EncryptedSharedPreferences`).

### Added (initial scaffolding)
- Initial project scaffolding and Gradle setup.
- Basic MVVM structure with Jetpack Compose.
- Splash screen with logo and animations.
- Bottom navigation with "Tänään", "Viikko", and "Asetukset" tabs.
- `WorkoutViewModel` with mock data for workouts.
- Static UI for viewing mock training sessions.
- Icons and basic styling for different workout types (Running, Strength, Skiing).
- Comprehensive documentation skeleton in `/docs`.

### Changed
- Replaced the default app icon with a custom adaptive icon using the user-provided `Icon.png`.
- Replaced the Material Design gradient background on the Splash screen with a custom background image (`Splash_notext.png`).
- App theme and colors configured to match the requested dark blue and vibrant green aesthetic.

### Planned
- Room database integration.
- Oura API V2 data fetching.
- Notification engine via AlarmManager.
- Background sync via WorkManager.

## [Unreleased]
- **Changed**: Erotettiin treenin suoritusaika ja muistutusaika toisistaan.
- **Added**: `timeIsFixed` ja valinnainen `time` JSON-skeemaan v1.
- **Added**: Room-migraatio versioon 2, jossa lisättiin `remindAtUtc`, `timeIsFixed`, `reminderOverride`.
- **Added**: `NotificationSettingsStore` (Datastore) lajikohtaisille hälytysasetuksille.

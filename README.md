# SafeGuard (ZeroBet)

App Android nativa (Kotlin, Jetpack Compose, Room) che aiuta le persone con problemi di gioco
compulsivo bloccando l'accesso a siti di **scommesse/casinò**, **trading/crypto** e
**prestiti/finanziarie**. La configurazione è protetta da un **PIN a 6 cifre** custodito da un
familiare o tutore.

Il blocco avviene interamente sul dispositivo tramite una VPN locale che filtra le sole richieste
DNS: nessun dato di navigazione viene inviato a server di terzi (a parte le normali query DNS verso
`1.1.1.1` / `8.8.8.8` e il download quotidiano delle blocklist pubbliche).

La blacklist si alimenta da sola: liste esterne aggiornate ogni 24 ore, scoperta di nuovi domini
dai log di Certificate Transparency, punteggio euristico sui nomi a dominio e riconoscimento dei
contenuti a schermo.

## Requisiti e build

- JDK 17, Android SDK 34 (minSdk 26 / Android 8.0)
- `local.properties` con `sdk.dir=...` (creato automaticamente da Android Studio)

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

### Build di release firmata (APK e App Bundle)

La release ha R8 attivo (`isMinifyEnabled` e `isShrinkResources`, regole in `app/proguard-rules.pro`) ed è
firmata con il keystore indicato in `keystore.properties` nella radice del progetto:

```properties
storeFile=keystore/safeguard-release.jks
storePassword=...
keyAlias=safeguard
keyPassword=...
```

`keystore.properties` e la cartella `keystore/` sono esclusi da git: **conservane una copia sicura**, perché
senza quel keystore non si possono più pubblicare aggiornamenti della stessa app. In alternativa (es. CI)
si possono usare le variabili d'ambiente `SAFEGUARD_STORE_FILE`, `SAFEGUARD_STORE_PASSWORD`,
`SAFEGUARD_KEY_ALIAS`, `SAFEGUARD_KEY_PASSWORD`; senza nessuna delle due la release esce non firmata.

```bash
./gradlew assembleRelease
```

```bash
./gradlew bundleRelease
```

Uscite: `app/build/outputs/apk/release/app-release.apk` (installazione diretta, circa 1,5 MB) e
`app/build/outputs/bundle/release/app-release.aab` (Play Store). La build di release usa una firma diversa
da quella di debug: per passare dall'una all'altra sullo stesso telefono va prima disinstallata l'app
("Sblocca Disinstallazione" dalla dashboard).

## Struttura del progetto

```
app/src/main/
├── AndroidManifest.xml
├── res/xml/                       device_admin.xml, accessibility_service_config.xml
└── java/com/zerobet/safeguard/
    ├── SafeGuardApp.kt            Application + service locator
    ├── security/PinManager.kt     Hash PIN (SHA-256 + salt) in EncryptedSharedPreferences, lockout
    ├── data/
    │   ├── Entities.kt            BlockedDomain, WhitelistedBank
    │   ├── DomainDao.kt
    │   ├── AppDatabase.kt         Room + pre-popolamento blacklist
    │   ├── DefaultData.kt         Liste predefinite (domini, parole chiave, banche)
    │   └── DomainRepository.kt    Normalizzazione e validazione dei domini
    ├── vpn/
    │   ├── DomainBlockerVpnService.kt   VPN locale DNS-only
    │   ├── DnsPackets.kt                Parsing IPv4/UDP/DNS e risposta sinkhole
    │   ├── DomainFilter.kt              Whitelist → liste → parole chiave → euristica
    │   ├── DomainHeuristics.kt          Punteggio di rischio sul nome + domini "sicuri"
    │   └── FilterStore.kt               Filtro in memoria sincronizzato con Room
    ├── sync/
    │   ├── BlacklistSyncWorker.kt       WorkManager: fonti esterne, ADM, CONSOB, crt.sh
    │   ├── BlocklistParser.kt           Parser hosts/domains/adblock e estrazione da HTML
    │   └── CtLogDiscovery.kt            Certificate Transparency (parser JSON in streaming)
    ├── admin/SafeGuardDeviceAdminReceiver.kt
    ├── guard/
    │   ├── SettingsGuardAccessibilityService.kt   Guardiano Impostazioni + contenuti a schermo
    │   ├── ContentClassifier.kt                   Riconoscimento di scommesse/trading/prestiti nei testi
    │   ├── SearchQueryClassifier.kt               Ricerche a rischio nei motori di ricerca
    │   └── GuardState.kt                          Finestra di grazia dopo il PIN
    ├── security/DeviceStorage.kt  Storage "device protected" per Direct Boot
    ├── security/SecurityLog.kt    Registro Allarmi (scrittura in background, raggruppamento)
    ├── guard/IntegrityChecker.kt  Controllo dei servizi obbligatori
    ├── guard/InAppContentAndAdFilter.kt  Pubblicità e contenuti a rischio nelle app
    ├── guard/BlockedPackages.kt   App dedicate chiuse all'apertura
    ├── guard/AppContextRules.kt   Telegram (chat private / canali) e app della banca autorizzata
    ├── vpn/HashedDomainSet.kt     Liste esterne compatte (hash a 64 bit + ricerca binaria)
    ├── receiver/SimStateReceiver.kt  Rimozione/inserimento SIM
    ├── receiver/BootReceiver.kt   Riavvio al boot (LOCKED_BOOT_COMPLETED)
    ├── notify/
    │   ├── TelegramNotifier.kt    Avvisi al tutore via bot Telegram, collegamento automatico
    │   ├── TelegramApi.kt         Bot API con HttpsURLConnection (sendMessage, getUpdates)
    │   ├── TelegramConfig.kt      Token e chat cifrati nello storage Direct Boot
    │   ├── AlertQueue.kt          Coda offline persistente e invio in ordine cronologico
    │   ├── AlertPolicy.kt         Livelli di dettaglio, testi, aggregazione dei blocchi
    │   ├── TelegramCommands.kt    Parser dei comandi remoti, filtro per chat autorizzata, testi
    │   └── TelegramCommandPoller.kt  getUpdates in long polling ed esecuzione dei comandi
    ├── security/ShutdownRecorder.kt  Registrazione sincrona dello spegnimento (goAsync)
    └── ui/
        ├── MainActivity.kt        Onboarding → PIN → Dashboard
        ├── OnboardingScreen.kt    Wizard: PIN, banca, Telegram (facoltativo), permessi
        ├── TelegramSetup.kt       Guida e configurazione degli avvisi Telegram
        ├── DashboardScreen.kt     Whitelist/blacklist, pausa, sblocco disinstallazione
        ├── LockScreenActivity.kt  Richiesta PIN sopra le Impostazioni
        └── components/PinPad.kt
```

## Come funziona il filtro

1. La VPN dichiara come DNS un indirizzo fittizio (`10.111.222.2`) e instrada nella TUN solo quello.
2. Per ogni query DNS (UDP/53) viene estratto il dominio richiesto:
   - **Whitelist banche** → inoltrata al resolver pubblico (ha sempre la precedenza);
   - **Blacklist** (dominio o sottodominio) o **parola chiave** contenuta nel nome → risposta
     `0.0.0.0` / `::` (sinkhole);
   - altrimenti → inoltrata.
3. Le banche note sono in blacklist di default: solo quella scelta nel wizard resta raggiungibile.

Le regole del tutore sono in `HashSet`; le liste esterne in un array ordinato di hash a 64 bit cercato
con ricerca binaria: match esatto, poi match sui domini padre (una ricerca per label), senza allocazioni.

## Threat intelligence e auto-alimentazione

| Modulo | Cosa fa |
|---|---|
| **Liste esterne** (`BlacklistSyncWorker`) | Ogni 24 ore, con rete disponibile, scarica in streaming StevenBlack (gambling), Block List Project (gambling), Peter Lowe (pubblicità/tracker), l'elenco ADM dei siti di gioco inibiti e gli oscuramenti CONSOB. Dalla dashboard: "Forza aggiornamento ora". |
| **Certificate Transparency** (`CtLogDiscovery`) | Interroga `crt.sh` per 6 radici lessicali al giorno (a rotazione su ~45) e salva i domini con certificati emessi negli ultimi 45 giorni. |
| **Euristica DNS** (`DomainHeuristics`) | Per i domini non in lista calcola un punteggio da TLD (`.bet`, `.casino`, `.forex`, `.loan`…) e token nel nome (`22bet`, `casino`, `trading`, `prestito`, `bonus`…). Sopra soglia: sinkhole e salvataggio tra gli auto-appresi. |
| **Anti-DoH** | I resolver DoH noti (`dns.google`, `cloudflare-dns.com`, `dns.quad9.net`…) sono in blacklist fissa; il canary di Firefox riceve NXDOMAIN. |
| **Contenuti a schermo** (`ContentClassifier`) | Il servizio di accessibilità legge i testi visibili in browser e app: con una combinazione ad alta confidenza di espressioni (es. "bonus benvenuto" + "gioca ora", "calcola la tua rata" + "TAEG") torna indietro e, se è un sito esterno, ne aggiunge il dominio agli auto-appresi. Vale anche dentro il sito della banca in whitelist (senza però bloccarne il dominio). |
| **Ricerche** (`SearchQueryClassifier`) | Se in un motore di ricerca (Google, Bing, DuckDuckGo, Yahoo, Brave, Ecosia, Startpage, Qwant… e ricerca di YouTube) viene scritta una ricerca su scommesse, trading/investimenti o prestiti, l'app esce dal motore di ricerca e torna alla Home. Qui basta una parola ("trading", "investimenti", "mutuo", un marchio…), ma il confronto è per parola intera: "investigatore", "betulla", "giocatore in prestito", "borsa di studio" non scattano. Le ricerche di aiuto ("come smettere di giocare", "ludopatia numero verde") non sono mai bloccate. |
| **App: ricerche** | Il blocco delle ricerche vale in qualsiasi app (YouTube, Instagram, Facebook, TikTok, Telegram, X, Reddit, Play Store, Google News…): le caselle di ricerca sono riconosciute dall'id della vista o dal suggerimento ("Cerca"). Azione: Indietro e, se dopo 400 ms la ricerca è ancora a schermo, Home. Nel registro: *"Ricerca bloccata su YouTube: trading online"*. |
| **App: pubblicità e contenuti** (`InAppContentAndAdFilter`) | Sui testi visibili, un elemento alla volta. **Pubblicità**: etichetta di annuncio ("Sponsorizzato", "Annuncio", "Ad"…) o invito all'azione ("Installa", "Scopri di più") vicino a un termine a rischio. **Contenuti**: un titolo/post/articolo con un'espressione inequivocabile ("come investire", "trading online", "pronostici serie A", "prestito veloce") o almeno due termini a rischio; "manovra finanziaria", "inflazione ISTAT", "stipendi pubblici" non scattano. Azione a scala: scorrimento in avanti nei feed, poi Indietro, poi Home. |
| **App dedicate** (`BlockedPackages`) | Le app di trading, borsa, crypto, scommesse, poker, casinò e finanziarie vengono chiuse all'apertura con una schermata di avviso: elenco di package noti, euristica sul nome del package e sul nome visibile. Resta libera l'app della banca in whitelist. Nel Play Store la scheda di un'app di questo tipo viene chiusa prima che si possa premere "Installa". |
| **Banca autorizzata: sito + app** | Il catalogo delle banche (`DefaultData.bankPresets`) associa a ogni istituto domini web e package delle app ufficiali. L'app della banca in whitelist resta utilizzabile (saldo, movimenti, bonifici), ma il Guardiano chiude le sezioni il cui titolo in alto è *Richiedi prestito*, *Prestito istantaneo*, *Cessione del quinto*, *Finanziamento*, *Investimenti*, *Trading*, *Compra azioni*, *Crypto*. Le app delle altre banche del catalogo e delle finanziarie (Findomestic, Compass, Agos, Cofidis, IBL…) sono bloccate all'apertura. |
| **Telegram** | Ricerca globale sempre controllata. Canali, gruppi e bot (sottotitolo "iscritti", "membri", "bot", pulsanti "Unisciti", "Silenzia"): filtro standard. Chat private ("online", "ultimo accesso", "sta scrivendo") ed elenco delle chat: le parole discorsive non contano, si blocca solo per link o domini a rischio (filtro DNS) e inviti a bot o canali con nome a rischio (`@CasinoBonusBot`, `t.me/segnalitrading`). |
| **Bypass di sistema** | PIN del tutore anche per: installazione manuale di APK (dialogo del package installer e pagina "Installa app sconosciute"), utenti multipli / ospite / secondo spazio / spazio privato / app clonate, opzioni sviluppatore e debug wireless (compresa la loro attivazione con i tocchi su "Numero build"), opzioni di ripristino (rete, preferenze app, dati di fabbrica, "Cancella tutti i dati"), **Data e ora**, app clonate / gemelle / parallele / Dual Messenger. Per le pagine delle Impostazioni conta solo il titolo: la pagina "Sistema", che elenca queste voci, resta libera. |
| **Schermo diviso e finestre flottanti** | `MainActivity` e `LockScreenActivity` hanno `resizeableActivity="false"`. Se un'Impostazione protetta viene aperta in schermo diviso, finestra flottante o PiP, il Guardiano torna prima alla Home (chiude lo schermo diviso), poi apre il PIN a tutto schermo e registra l'evento come critico. |
| **Manomissione di data/ora** | `TimeChangeReceiver` (`TIME_SET`, `TIMEZONE_CHANGED`) confronta la nuova ora con quella attesa dal tempo monotòno (`elapsedRealtime`): uno scarto oltre 5 minuti è un evento CRITICAL *"Manomissione data/ora di sistema rilevata"* (anche a telefono spento, se al riavvio l'ora è precedente all'ultima ora attendibile). Registro e avvisi usano l'ora attendibile; il blocco per PIN errati e la finestra anti-429 usano il tempo monotòno, quindi spostare l'orologio non li accorcia. |
| **Modalità Discreta** | Nella dashboard, "Aspetto App nella Home": tre `<activity-alias>` della stessa Activity, uno solo abilitato con `setComponentEnabledSetting(..., DONT_KILL_APP)`: *SafeGuard* (scudo), *Servizi di Rete* (ingranaggio), *Gestione Batteria* (batteria). La protezione non si interrompe. |
| **DNS privato** | Il Guardiano chiede il PIN sul dialogo "DNS privato" delle Impostazioni; la dashboard avvisa se è impostato un provider. |

Le voci delle liste esterne già coperte da parole chiave o euristica non vengono salvate (su circa
445.000 righe scaricate ne restano circa 210.000). Nella scheda **Blacklist** della dashboard compaiono
sia le regole dell'app e del tutore sia i domini delle liste esterne (caricati a pagine di 200, con
ricerca per nome e "Consenti" per i falsi positivi). I domini auto-appresi sono visibili nella scheda
**Appresi** della dashboard, dove il tutore può rimuoverli o consentirli in modo permanente.

**Privacy.** I testi letti a schermo sono analizzati in memoria sul dispositivo e scartati subito (tranne la ricerca bloccata, salvata nel Registro Allarmi):
non vengono salvati né trasmessi. L'unico dato registrato è il dominio bloccato con il motivo.
Sono escluse dall'analisi le app di sistema, telefono, SMS, WhatsApp, Telegram, Signal, Gmail e Outlook.

## Avvio al boot e controllo di integrità

- **Direct Boot.** Servizio VPN, Guardiano, `BootReceiver`, `SimStateReceiver` e Device Admin sono
  `directBootAware`. Hash del PIN, stato della protezione, database e copia compatta delle liste stanno
  nello storage *device protected* (`DeviceStorage`), quindi il filtro riparte su
  `LOCKED_BOOT_COMPLETED`, prima che venga inserito il codice di sblocco. I dati delle versioni
  precedenti vengono spostati al primo avvio.
- **Controllo di integrità** (`IntegrityChecker`): allo sblocco dello schermo (`USER_PRESENT`), all'avvio,
  dal Guardiano e ogni 15 minuti. VPN spenta con consenso valido → riavviata subito. VPN senza consenso o
  Guardiano disattivato senza PIN → evento critico, notifica ad alta priorità e schermata di blocco che
  chiede di riattivare il servizio.
- **Risparmio energetico**: wizard e dashboard chiedono l'esenzione dall'ottimizzazione della batteria.

## Registro Allarmi

Scheda della dashboard (quindi dietro PIN) alimentata dalla tabella Room `security_event_log`, con
gravità INFO / WARNING / CRITICAL e filtri *Tutti*, *Manomissione / SIM*, *Ricerche bloccate*, *Domini bloccati*.
Un badge mostra gli eventi critici avvenuti dall'ultimo accesso del tutore. Registra:

- SIM rimossa, inserita o con operatore cambiato (CRITICAL, anche offline e a schermo bloccato);
- tentativi di aprire le Impostazioni protette (VPN, Accessibilità, Device Admin, DNS privato, disinstallazione);
- PIN errati con il numero del tentativo; spegnimenti e riavvii; servizi obbligatori trovati spenti;
- ricerche bloccate (con il testo cercato) e pagine chiuse per i contenuti;
- domini bloccati dal filtro DNS, raggruppati: più richieste allo stesso dominio in un minuto sono una riga con contatore.

Il registro tiene le ultime 5.000 righe; si svuota solo dalla dashboard, dopo una conferma, e la pulizia
lascia a sua volta una riga.

## Notifiche remote su Telegram

Il tutore può ricevere gli avvisi sul proprio telefono tramite un bot Telegram personale. Si configura
nell'onboarding (passo facoltativo) o in qualsiasi momento dalla scheda **Notifiche Telegram** della
dashboard, dietro PIN, con una guida in tre passi:

1. sul telefono del tutore, in Telegram, cercare **@BotFather** (badge blu), premere Avvia e inviare `/newbot`;
2. scegliere un nome e uno username che finisca per `bot`, copiare il **Token HTTP API** e incollarlo in SafeGuard;
3. aprire la chat del nuovo bot, premere **AVVIA** (`/start`) e toccare **Collega Automaticamente e Invia Test**.

Il collegamento automatico chiama `getUpdates`, prende la chat dell'ultimo `/start`, invia
*"✅ SafeGuard collegato correttamente! Riceverai qui gli avvisi di sicurezza."* e mostra
"Collegato a: Nome (ID: …)". Chi conosce già il proprio Chat ID può inserirlo a mano (sezione avanzata).

| Livello (interruttore) | Eventi | Invio |
|---|---|---|
| **Avvisi critici** (attivo) | SIM rimossa o cambiata, telefono spento/riavviato, arresto forzato, Impostazioni protette (VPN, Accessibilità, Device Admin, Opzioni sviluppatore, Numero build, disinstallazione), PIN errati, servizi trovati spenti | Subito, un messaggio per evento |
| **Ricerche e app vietate** (attivo) | Ricerche bloccate, app dedicate aperte, pagine/video/post bloccati | Aggregati: al massimo un messaggio al minuto |
| **Domini e pubblicità** (spento) | Domini bloccati dal filtro DNS, pubblicità scavalcate | Aggregati come sopra |

- **Nessuna libreria di rete**: `HttpsURLConnection` della piattaforma, in `Dispatchers.IO`. `api.telegram.org`
  è tra i domini interni sempre consentiti dal filtro DNS (`DomainFilter.ALWAYS_ALLOWED`).
- **Direct Boot**: token, chat e preferenze sono in EncryptedSharedPreferences nello storage protetto dal
  dispositivo; la coda è un file nello stesso storage. Gli avvisi di un riavvio partono anche a schermo bloccato.
- **Coda offline**: ogni avviso viene scritto su disco con l'orario esatto del fatto prima di tentare l'invio.
  Se il telefono è offline (SIM appena rimossa, modalità aereo) resta in coda; al ritorno di una rete
  (`ConnectivityManager.NetworkCallback` sulle reti reali, più un lavoro WorkManager con vincolo di rete come
  riserva) gli arretrati partono in ordine cronologico, raggruppati, con la nota "inviato in ritardo".
- **Rate limit**: al massimo un messaggio al secondo; con HTTP 429 si rispetta `retry_after`. Token revocato o
  chat inesistente non svuotano la coda (massimo 300 avvisi, poi si tengono i più recenti).
- Il token non compare mai nei log. Per sospendere: interruttore "Avvisi Telegram attivi" oppure "Scollega".
- **Spegnimento** (`ACTION_SHUTDOWN`, `QUICKBOOT_POWEROFF`): il receiver resta aperto con `goAsync()` e lavora
  in modo sincrono: flag e orario con `commit()` nello storage Direct Boot, avviso nella coda su disco con
  `fsync`, poi in parallelo invio lampo a Telegram e riga nel Registro Allarmi, entro 1,5 secondi. Se il
  telefono è offline l'avviso parte al riavvio; se la riga non è stata scritta in tempo, viene aggiunta al
  `LOCKED_BOOT_COMPLETED` con l'orario originale e la durata dello spegnimento.

### Comandi remoti del tutore

Scrivendo nella chat del proprio bot il tutore può comandare SafeGuard a distanza. Sono accettati **solo** i
messaggi della chat collegata (Chat ID salvato nelle preferenze cifrate): chi scrive al bot da un'altra chat
viene ignorato e il suo Chat ID finisce nel Registro Allarmi. I messaggi modificati non vengono eseguiti.

| Comando | Effetto |
|---|---|
| `/stato` (o `/report`) | Filtro VPN, Guardiano, tempo dall'ultimo riavvio, batteria, blocchi delle ultime 24 ore, liste, avvisi in coda, orologio manomesso |
| `/ultimi` | Ultimi 10 eventi del Registro Allarmi |
| `/blocca <dominio o parola>` | Con un punto è un dominio (e i suoi sottodomini), altrimenti una parola chiave: *"🚫 Bloccato: ..."* |
| `/consenti <dominio>` | Aggiunge il dominio alla whitelist: *"✅ Consentito: ..."* |
| `/aiuto` | Elenco dei comandi (inviato anche al collegamento del bot) |

I comandi arrivano con `getUpdates` in long polling (una richiesta aperta fino a 50 secondi, risposta appena
arriva un messaggio). L'`offset` dell'ultimo `update_id` è salvato su disco prima di eseguire il comando, quindi
nessun comando viene eseguito due volte, nemmeno dopo un riavvio; al collegamento del bot l'offset parte dopo il
`/start`. Ogni comando eseguito resta nel Registro Allarmi (filtro *Manomissione / SIM*).
- **Report giornaliero (heartbeat)**, attivo di default: ogni 24 ore *"💓 SafeGuard Attivo · Protezione VPN e
  Guardiano: OK · Blocchi ultime 24h: X domini, Y ricerche, Z tentativi impostazioni · Liste aggiornate."*
  (pulsante "Report ora" per provarlo). **Se per più di 24 ore il messaggio 💓 non arriva, il telefono può essere
  spento, in Modalità Provvisoria (dove nessuna app di terze parti gira) o disconnesso.**
- **Durata dello spegnimento**: al riavvio il messaggio dice per quanto la protezione è mancata, calcolato
  dall'ultimo segnale di vita salvato su disco (controllo periodico, report, spegnimento regolare):
  *"Telefono riavviato (era spento o in Modalità Provvisoria da 3 ore e 20 minuti)"*.

### Prova con un server Telegram finto

L'URL dell'API è `BuildConfig.TELEGRAM_API_BASE` (di norma `https://api.telegram.org`). Solo per le prove si
compila una release che punta a un server locale, raggiunto dall'emulatore con `adb reverse`:

```bash
./gradlew assembleRelease -PtelegramApiBase=http://127.0.0.1:8081
```

```bash
adb reverse tcp:8081 tcp:8081
```

Con questa proprietà il manifest abilita anche l'HTTP in chiaro; senza (la build normale) resta disabilitato.
Il server finto deve rispondere a `getUpdates` e `sendMessage` come la Bot API (anche con 429 e `retry_after`).
Dopo le prove va ricompilata la release senza la proprietà.

## Consumi

| Misura | Valore |
|---|---|
| Liste esterne in memoria (211.000 domini, array ordinato di hash a 64 bit) | 1,6 MB (circa 22 MB con `HashSet<String>`) |
| Query DNS, media su 10.000 (unit test, JVM desktop) | circa 2 µs |
| Ricerca, media su 1.000 (unit test, JVM desktop) | circa 60 µs |
| Heap Java del processo sull'emulatore, a riposo | circa 12 MB (prima circa 28 MB) |
| CPU a riposo sull'emulatore | 0% |

Il Guardiano segue tutte le app tranne quelle scartate in O(1) (interfaccia di sistema, launcher,
tastiere, telefono, fotocamera, messaggi privati). Cambio di finestra e testo digitato si valutano
subito; scorrimento e aggiornamenti del contenuto dopo 350 ms di quiete. La schermata è letta in un
solo passaggio e, se il suo hash è uguale a quello precedente, non viene analizzata; i testi già
valutati restano in una cache. Misurato sull'emulatore con i tick di CPU del processo: circa 2% di un core durante lo scorrimento continuo
del feed di YouTube (la stessa cifra della versione precedente: è la lettura dell'albero dei nodi tramite il
sistema), 0% a riposo. Il valore "0,0–0,2%" riportato in precedenza era sottostimato dal campionamento di `top`. Il thread della VPN dorme in una lettura bloccante quando non c'è traffico DNS.
`./gradlew testDebugUnitTest` rigenera i numeri in `app/build/reports/safeguard-benchmark.txt`.

## Prove strumentate sull'emulatore (solo build di debug)

La build di debug contiene `DebugTestReceiver` (cartella `app/src/debug`, assente nella build di rilascio),
protetto dal permesso `DUMP`: lo può usare solo la shell adb.

```bash
adb shell am broadcast -a com.zerobet.safeguard.debug.SET_PIN --es pin 123456 -n com.zerobet.safeguard/.debug.DebugTestReceiver
```

```bash
adb shell am broadcast -a com.zerobet.safeguard.debug.SIM --es state ABSENT --ei slot 0 -n com.zerobet.safeguard/.debug.DebugTestReceiver
```

Il primo imposta un PIN (sostituisce quello esistente), il secondo simula la SIM rimossa (`PRESENT` la reinserisce)
passando per la stessa logica del receiver, perché la SIM dell'emulatore non si può togliere. Sull'emulatore di
sviluppo è impostato un PIN di prova, salvato in `emulator-test-pin.txt` (escluso da git).

## Configurazione consigliata sul telefono

- **Impostazioni → Rete → VPN → SafeGuard → "VPN sempre attiva"**: il sistema riavvia da solo il filtro.
- **DNS privato** su *Disattivato* o *Automatico*: con un provider DoT impostato a mano le query non
  passano dalla porta 53 e il filtro viene aggirato.
- Escludere SafeGuard dall'ottimizzazione batteria sui dispositivi con gestione aggressiva (Xiaomi, Huawei…).

## Limiti noti

SafeGuard è un deterrente efficace, non una barriera assoluta. In particolare:

- un browser con **DNS-over-HTTPS** configurato per indirizzo IP (o verso un resolver non in lista), un'altra app VPN
  o un proxy aggirano il filtro DNS;
- `crt.sh` è un servizio pubblico gratuito che risponde spesso con errori 502 o timeout: la scoperta via
  Certificate Transparency va considerata "best effort";
- la lista StevenBlack indicata comprende anche adware/malware generici e, insieme a taboola/outbrain e ai
  comparatori (facile.it, segugio.it), può rompere annunci o funzioni di siti e app legittimi;
- euristica e riconoscimento dei contenuti possono dare **falsi positivi** (es. un articolo di giornale sui prestiti):
  il tutore li corregge dalla scheda "Appresi" con "Consenti";
- l'auto-apprendimento da schermo legge la barra degli indirizzi solo dei browser supportati (Chrome, Firefox,
  Samsung Internet, Brave, Edge, Opera, Vivaldi, DuckDuckGo); negli altri il contenuto viene bloccato ma il dominio non viene appreso;
- il blocco delle ricerche legge la barra degli indirizzi dei browser supportati e le caselle di ricerca delle
  pagine dei motori noti: un motore non in elenco o un browser non supportato non vengono controllati (restano
  attivi il riconoscimento dei contenuti e il filtro DNS);
- dalle pagine CONSOB i domini sono estratti con regex: i link di servizio noti sono esclusi, ma un sito legittimo
  citato in un comunicato potrebbe finire in lista;
- WhatsApp, Signal, SMS, Gmail, Outlook, telefono e contatti non vengono mai letti; Telegram sì (canali e ricerca),
  quindi una chat privata con due termini a rischio nello stesso messaggio può essere chiusa;
- il filtro in-app legge solo i testi: un video il cui titolo non dice nulla, o un annuncio fatto di sola immagine,
  non viene riconosciuto; un'app che non espone i testi all'accessibilità non è controllabile;
- un titolo che usa i termini a rischio in un altro senso può essere chiuso (falso positivo): la scala di azioni
  parte dallo scorrimento proprio per limitarne l'effetto;
- nelle app bancarie si guarda solo il titolo della schermata (i primi tre testi in alto): un'app che mette il
  titolo della sezione altrove non viene riconosciuta; la sezione "Mutuo" resta consultabile;
- su Telegram il tipo di chat è dedotto dai testi dell'intestazione: verificato solo con un'app fittizia che li
  riproduce, non con il client reale;
- gli avvisi Telegram sono verificati dal vivo sulla release R8 con un server Bot API finto (collegamento
  automatico, aggregazione, coda offline con Wi-Fi e dati spenti, 429, report 💓, riavvio); con i server veri
  di Telegram solo fino alla risposta (token di prova rifiutato con 401): la consegna a un bot reale va
  provata con un token vero;
- lo spegnimento è registrato solo quando passa dalla procedura di Android (tasto di accensione, menu, riavvio,
  `svc power shutdown`), che invia `ACTION_SHUTDOWN`. Batteria tolta, batteria scarica improvvisa o spegnimento
  forzato tenendo premuto il tasto non inviano nulla: in quel caso resta la durata calcolata al riavvio;
- se il processo viene chiuso subito dopo un invio riuscito ma prima di aggiornare la coda, al riavvio lo stesso
  avviso può arrivare due volte;
- i comandi remoti tengono aperta una connessione verso Telegram (long polling): un consumo di dati e batteria
  piccolo ma continuo, solo quando Telegram è collegato;
- la Modalità Provvisoria non è rilevabile mentre è attiva (nessuna app di terze parti gira): la si deduce dal
  report 💓 mancante e dalla durata riportata al riavvio successivo;
- le funzioni di schermo diviso e finestra flottante variano tra i produttori: verificato sull'emulatore
  (Android 16, schermo diviso dal menu delle app recenti), non su Samsung, Xiaomi o OnePlus;
- le Opzioni sviluppatore chiedono il PIN (verificato sull'emulatore: da Sistema, con l'intent diretto e aprendo
  "Debug wireless" dalla ricerca delle Impostazioni). Anche la loro attivazione è protetta: finché sono spente,
  il primo tocco su "Numero build" (o "Versione MIUI/HyperOS") chiede il PIN e interrompe il conteggio dei 7
  tocchi; la pagina "Info telefono" resta consultabile. Disattivarle spegne anche il debug USB (adb);
- su Android recenti un "arresto forzato" di SafeGuard disattiva il servizio di accessibilità e fa ripartire l'app
  con i broadcast di avvio: viene registrato come manomissione critica ("SafeGuard è stato arrestato
  forzatamente") e il controllo di integrità chiede di riattivare il Guardiano;
- l'elenco delle app dedicate non può essere completo: quelle non in elenco sono riconosciute solo se il nome del
  package o dell'app contiene una parola a rischio;
- nelle pagine dei risultati dei motori di ricerca decide solo la ricerca scritta, non il testo dei risultati;
- Android limita l'apertura di schermate dallo sfondo: se il Guardiano è spento, la schermata di blocco del
  controllo di integrità può non aprirsi da sola e resta la notifica; se un task killer chiude tutto il
  processo, il controllo riparte entro 15 minuti;
- la rimozione della SIM è riconosciuta dagli eventi di sistema: un telefono acceso senza SIM dopo essere
  stato spento viene segnalato solo al primo evento SIM successivo all'avvio;
- un "arresto forzato" dell'app dalle Impostazioni (protette dal Guardiano) disattiva il servizio di accessibilità;
- il blocco agisce sui **siti/domini**; le app già installate che contattano domini non in lista non vengono fermate;
- il Device Admin impedisce la disinstallazione standard, ma non la **modalità provvisoria**, `adb` o il ripristino di fabbrica;
- il Guardiano riconosce le schermate delle Impostazioni dai testi (italiano/inglese) e dai package AOSP/Samsung/Xiaomi:
  su altre personalizzazioni OEM potrebbe servire estendere le liste in `SettingsGuardAccessibilityService`;
- le parole chiave (es. `trading`, `crypto`, `casino`) possono bloccare anche siti legittimi che le contengono:
  si possono rimuovere dalla dashboard;
- le liste esterne occupano circa 20 MB di database e alcune decine di MB di RAM;
- Google Play applica regole restrittive ad `AccessibilityService` e `VpnService`: per la pubblicazione sullo
  store servono le relative dichiarazioni e un'informativa chiara all'utente.

L'app va installata **con il consenso informato** della persona da proteggere e affiancata a un percorso di
supporto (in Italia: Telefono Verde Nazionale per le problematiche legate al Gioco d'Azzardo, 800 558822).

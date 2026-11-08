# Implementeringsplan: betald fakturering i Tidrapporten

Datum: 2026-11-02. Status: etapp 1 påbörjad; ingen betalningsfunktion implementerad.

## Mål och avgränsning

Tidrapportering förblir gratis. Fakturering erbjuds som ett abonnemang med månads-
eller årsbetalning i SEK. Tjänsteägarens företag tar emot abonnemangsbetalningarna
via sitt Stripe-konto. Kundernas egna fakturor i Tidrapporten behåller sitt nuvarande flöde.

Första versionen riktas till svenska företagskunder och använder kortbetalning
genom en omdirigering till Stripe Checkout, samt Stripe Billing och Customer Portal.
Bankgiro/OCR och e-faktura för abonnemangsavgiften utreds senare enligt
[INFRA_TODO.md](INFRA_TODO.md). Separata mikrotjänster behövs inte.

Nuvarande datamodell har `account_id` och en företagsprofil per konto, men saknar
fristående företag och användarmedlemskap. Abonnemanget kopplas därför till kontot
i första versionen. Gemensamt företagsabonnemang för flera användare kräver en senare
migrering till företagsägarskap; organisationsnummer eller e-postdomän ska inte
automatiskt ge andra konton åtkomst.

## Pris och föreslagna produktregler

| Val | Debitering | Förslag |
| --- | --- | --- |
| Månad | X kr varje månad, i förskott | Förnyas tills kunden säger upp |
| År | Y kr en gång per år, i förskott | Exempel: Y = 10 × X, cirka 16,7 % lägre årskostnad |

Beloppen är ännu inte beslutade. Exempel: 99 kr/månad och 990 kr/år innebär
198 kr lägre årskostnad jämfört med tolv månadsbetalningar, med samma momsgrund.
Visa årsbeloppet tydligt även om ett jämförelsepris per månad visas.
Bestäm momsbehandling och visa totalbelopp före beställning. Kundens inställning
för moms på sina egna fakturor ska inte styra moms på Tidrapportens abonnemang.

- En produkt i Stripe med två återkommande priser: månad och år. Backend tillåter
  bara dessa priser; webbläsaren skickar planval, aldrig ett fritt belopp.
- Ingen provperiod i första versionen. Åtkomst öppnas efter verifierad betalning.
- Uppsägning stoppar nästa förnyelse; åtkomst gäller till den betalda periodens slut.
- Beslutat: kunden får ett påminnelsemejl sju dagar före månadsförnyelse respektive
  en kalendermånad före årsförnyelse, med möjlighet att logga in och säga upp.
  Syftet är att kunden ska kunna avsluta även ett bortglömt, oanvänt abonnemang.
- Föreslagen respit: sju dagar vid misslyckad förnyelse för en tidigare betalande
  kund, räknat från den betalda periodens slut. Första misslyckade betalningen ger
  ingen åtkomst. Upprepade betalningsförsök får inte flytta fram respitens slut.
- Beslutat: om förnyelsen fortfarande är obetald när respiten löper ut avslutas
  det gamla abonnemanget och dess automatiska betalningsförsök. En kund som
  återkommer startar ett nytt abonnemang och debiteras från den nya perioden,
  utan automatisk retroaktiv debitering för den missade månaden. Hantera den
  gamla obetalda fakturan uttryckligen hos Stripe så att den inte dras senare.
  Betalning under respiten kan rädda det befintliga abonnemanget.
- Byte mellan månad och år sker vid nästa periodgräns. Planera detta med Stripe
  subscription schedules och tydlig bekräftelse av nästa pris/datum; öppna inte
  omedelbara planbyten i portalen innan reglerna är implementerade och testade.
- Ditt konto får en separat, spårbar ägaråtkomst utan betalning. Den får inte
  kunna aktiveras genom profilfält, e-postadress eller en vanlig kundadministratörsroll.
- Dokumentera villkor för uppsägning, prisändringar, återbetalning och tvist innan
  skarpa köp. Återbetalning avslutar inte automatiskt ett abonnemang; båda åtgärderna
  och deras påverkan på åtkomst behöver hanteras uttryckligen.

## Kortuppgifter och ansvar

PCI DSS är kortbranschens säkerhetsstandard. Stripe-hosted Checkout minskar vår
PCI-omfattning eftersom kortuppgifterna skickas direkt till Stripe. Den innebär
inte att allt ansvar försvinner: följ den årliga verifiering som Stripe begär,
normalt SAQ A för en behörig mindre verksamhet med denna integration.
Se [Stripes PCI-guide](https://stripe.com/guides/pci-compliance) och
[integrationssäkerhet](https://docs.stripe.com/security/guide).

Tidrapporten ska inte ta emot fullständiga kortnummer eller CVC/CVV i egna formulär,
API:er, loggar, databaser eller webbläsarcache. CVC/CVV får inte lagras efter
auktorisering, inte heller krypterat eller för återkommande betalningar.
Se [PCI SSC:s regel om säkerhetskoder](https://www.pcisecuritystandards.org/faqs/1280/).

Spara Stripe-kund-/abonnemangsreferenser och de uppgifter som behövs för åtkomst och
support. Kortbyte sker i Stripe-portalen. Maskerade kortuppgifter behövs inte lokalt
i första versionen. Kunden ska godkänna återkommande betalning, belopp eller
beräkningsgrund samt intervall. Stripe hanterar autentisering när den krävs,
exempelvis 3D Secure; senare debiteringar kan också behöva kundens medverkan.
Se [godkännande och framtida debiteringar](https://docs.stripe.com/payments/setup-intents).

GDPR gäller fortfarande för våra kund- och abonnemangsuppgifter. Dokumentera ändamål,
rättslig grund, lagringstid, leverantörernas roller och tillämpliga avtal/överföringar.
Stripe kan ha olika dataskyddsroller beroende på behandlingen; utgå inte från att
leverantören alltid enbart är biträde. Se
[IMY om ansvar och biträden](https://www.imy.se/verksamhet/dataskydd/det-har-galler-enligt-gdpr/personuppgiftsansvariga-och-personuppgiftsbitraden/).

## Etapp 1: åtkomstmodell och abonnemangsgrund

- Inför kapabiliteten `com.timereport.subscription` enligt ports-and-adapters.
  Använd immutable records och Lombok-builders i domän och REST DTO:er.
- Modellera abonnemangsreferenser, plan, status, betald-till-datum, eventuell respit,
  uppsägning och separat ägarundantag. Lägg till nya Flyway-migreringar; ändra inga gamla.
- Separera tabeller för abonnemang, betalade perioder, checkout-försök, behandlade
  Stripe-händelser och manuella åtkomstbeslut. Unika Stripe-id:n och kontonycklar
  ska förhindra dubbla betalperioder och koppling till fel konto.
- Bevara abonnemangshistorik per konto så att ett nytt Stripe-abonnemang kan skapas
  efter en missad period utan att gamla betalningar eller händelser skrivs över.
- Definiera inkommande portar för abonnemangsstatus, Checkout, portal och behandling
  av betalningshändelser. Utgående portar hanterar lagring, betaltjänst och klocka.
  Stripe-SDK och JSON stannar i adaptrarna.
- Faktureringsmodulen använder en utgående port för åtkomstkontroll, kopplad till
  abonnemangsmodulens användningsfall genom en adapter. Ingen korsläsning av tabeller.
- Kontrollera åtkomst på servern när en skyddad åtgärd utförs. Beräkna den med lokal
  lagrad betalperiod/undantag och aktuell tid; ring inte Stripe för varje faktura
  och lita inte på ett gammalt JWT-fält eller på att en knapp är dold.

| Funktion | Utan aktivt abonnemang, föreslaget beteende |
| --- | --- |
| Tider, semester, månadsrapport, synkning och kundkoder | Tillgängliga som tidigare, även offline där det stöds |
| Ditt företag, kunder och koppling av kundkoder | Tillgängliga för grunduppgifter och förberedelse |
| Nya fakturautkast, PDF-förhandsgranskning, utfärdande och mejlutskick | Kräver abonnemang eller ägaråtkomst |
| Befintliga fakturor och deras lagrade PDF:er | Läsning/nedladdning fortsatt tillgänglig |
| Betald-/Skickad-markering på befintliga fakturor | Fortsatt tillgänglig |

**Klart när:** konto A inte kan använda konto B:s rättighet, spärren gäller direkta
API-anrop, och det går att ge/återkalla ditt ägarundantag med ett avgränsat driftkommando
som sparar vem, när och varför. Aktivera inte produktionsspärren innan utrullningsplanen i etapp 6.

## Etapp 2: månads- och årsköp i Stripes testmiljö

- Skapa testprodukt/priser och lagra deras id:n i serverkonfiguration.
- Föreslagna API:er: `GET /api/subscription`, `POST /api/subscription/checkout`
  och `POST /api/subscription/portal`. Kontot härleds från den verifierade sessionen.
- Skapa Stripe Customer från kontots abonnemangsuppgifter. Tillåt högst ett
  pågående köp/abonnemang per konto med databasregler och Stripes idempotensnycklar.
  Enbart idempotens på ett enskilt knapptryck skyddar inte mot köp från två flikar.
- Skapa Checkout i subscription-läge med `MONTHLY` eller `YEARLY`, SEK, fastställd
  momshantering och tjänsteägarens säljardata. Spara en kontobunden köpreferens.
- Lås returadresser till `PUBLIC_URL`. Visa ”Betalningen behandlas” efter återkomst
  och läs status från backend. En success-URL eller ett klientpåstående får aldrig
  aktivera faktureringen.

**Klart när:** båda köpflödena fungerar med testkort, avbrutet köp skapar ingen
åtkomst, och dubbelklick/flikar/återförsök inte skapar dubbla abonnemang.

## Etapp 3: betalstatus, förnyelse och tillförlitliga händelser

- Inför `POST /api/subscription/webhooks/stripe`: ett smalt undantag från JWT-kravet,
  med verifierad Stripe-signatur på oförändrad request body, tidskontroll och storleksgräns.
  Kontrollera test/live-läge och konto-/Customer-/Subscription-kopplingen.
- Hantera bland annat `invoice.paid`, `invoice.payment_failed`,
  `invoice.payment_action_required` och `customer.subscription.created/updated/deleted`.
  `checkout.session.completed` används för koppling och avstämning; bekräftad
  betald abonnemangsperiod styr åtkomst. Läs betald tjänsteperiod från rätt fakturarad,
  inte från händelsens ankomsttid eller en godtycklig prenumerationsperiod.
- Registrera och behandla händelser beständigt och idempotent. Svara 2xx först efter
  säker lagring/behandling. Om en kö används måste en hållbar worker och återförsök
  finnas; förlita dig inte på bakgrundstrådar efter Cloud Run-svaret.
- Tåla dubbletter, fördröjning och fel ordning. Lås per abonnemang, hämta aktuellt
  Stripe-tillstånd vid avstämning och förhindra att gamla händelser återaktiverar
  avslutad åtkomst eller förlänger respit.
- När respiten tar slut utan betalning: stoppa det gamla abonnemanget och
  betalningsförsöken, stäng den obetalda förnyelsefakturan enligt produktregeln
  och tillåt ett nytt Checkout-köp. Ett sent `invoice.paid` för det avslutade
  abonnemanget får inte ge ny åtkomst utan avstämning.
- Hantera återbetalnings-/tvistnotiser med spårbart beslut och larm. Inför en
  avstämningsrutin för missade händelser, samt säker schemalagd körning i molnet.
- Spara planerade förnyelsepåminnelser med abonnemang, förnyelsedatum, påminnelsetyp
  och utskicksstatus. En unik nyckel och låsning förhindrar parallella utskick av samma
  påminnelse. Anpassa planeringen när förnyelsedatum eller abonnemangsintervall ändras.
- Kör en autentiserad intern påminnelserutin dagligen via Cloud Scheduler, även när
  ingen öppnar appen och Cloud Run har skalat till noll. Föreslagen sändningstid är
  kl. 09 i Europe/Stockholm. Årspåminnelsen beräknas med kalendermånader och klampning
  till månadens sista dag vid behov. Kontrollera aktuell förnyelse och uppsägning före utskick.
- Använd Brevo genom en separat mejlport för dessa påminnelser om Stripes inställningar
  inte ger de två tidsintervallen. Välj en utskickskälla för att undvika dubbla påminnelser.
  Hantera försenad körning genom att skicka en missad påminnelse före förnyelsen;
  logga och larma vid fel och definiera hantering av osäkra SMTP-resultat.

**Klart när:** förnyelse, nekad betalning, extra autentisering, uppsägning och
periodslut följer produktreglerna även efter omstart. Dubbel webhook ger ingen
dubbel period; missad webhook kan återhämtas utan ny debitering.

## Etapp 4: abonnemangssida och kundportal

- Lägg ”Abonnemang” under Konto: prisval, status, nästa debitering eller sista
  giltighetsdag och knappen ”Hantera abonnemang”. Använd mobilanpassade komponenter.
- Stripe Customer Portal används för kortbyte, betalningsunderlag och uppsägning.
  Portalens Customer-id väljs på servern och får aldrig komma från en godtycklig klientparameter.
- Lägg en tydlig uppgraderingsmöjlighet vid de skyddade fakturafunktionerna.
  Kundkoder ligger idag under Fakturering → Kunder; spärra därför inte hela menyn.
- Visa begripligt fel vid avvisad åtgärd, exempelvis `SUBSCRIPTION_REQUIRED`, med
  länk till Abonnemang. Bevara formulärinnehåll. Förnya status efter retur/fokus.
- Betalning kräver nät. Störningar i Stripe eller abonnemangskontroll får inte
  blockera lokal tidsregistrering, kundkoder eller synkning av tidsdata.
- Påminnelsemejlet anger Tidrapporten, aktuellt abonnemang, nästa debiteringsdatum,
  belopp inklusive eventuell moms samt att förnyelsen sker automatiskt om kunden
  inte säger upp. Knappen heter ”Logga in och hantera abonnemang”.
- Länken leder till Tidrapportens inloggning och därefter Konto → Abonnemang.
  Om kunden redan är inloggad visas sidan direkt. Returvägen begränsas till en intern
  tillåten adress. Enbart ett klick i mejlet får aldrig säga upp eller autentisera kontot.
- Visa en tydlig väg till ”Säg upp abonnemang” via kundportalen, utan krav på
  kontakt med support. Efter bekräftad uppsägning visas slutdatum, ett bekräftelsemejl
  skickas och kommande förnyelsepåminnelser stoppas. Kunden behåller den redan betalda åtkomsten.
- Påminnelser skickas till abonnemangets kontaktadress; ägaråtkomst utan betalning
  och abonnemang som redan sagts upp ska inte få förnyelsepåminnelser.
- Betalningsproblem får separata mejl. Visa pris, moms, automatisk förnyelse och villkor före köp.

**Klart när:** kunden kan köpa, byta kort och säga upp från mobilen, utan att
gratisflödet eller åtkomsten till befintliga fakturor försvinner.

## Etapp 5: tester och driftsäkerhet

- Domäntester med fast klocka för periodgränser, års-/månadsbetalning, respit och ägarundantag.
- Backendtester för kontoisolering, manipulerade pris-/kund-id:n, signaturfel,
  dubbletter, samtidiga köp, omstart, webhook-ordning och idempotens.
- Stripe-testmiljö och testklockor för lyckad/nekad förnyelse, extra autentisering,
  kortbyte, planbyte vid periodslut, uppsägning, återbetalning och tvist.
- Testa både betalning under respiten och en helt missad månad följd av nytt köp.
  Bekräfta att det gamla abonnemanget inte debiteras igen, att gamla händelser
  inte återaktiverar det och att det nya köpet inte tar bort historiken.
- Webbläsartester för månads-/årsval, avbruten Checkout, väntande betalning,
  returnavigering, spärrade API-anrop och fortsatt gratis/offline tidrapportering.
- Testa påminnelser sju dagar/en kalendermånad före förnyelse, månadsslut, skottår,
  tidszon, ändrat förnyelsedatum, missad körning, parallell körning och mejlfel.
  Verifiera att redan uppsagda abonnemang inte får påminnelser och att mejllänken
  efter inloggning leder till rätt kontos abonnemang, där uppsägningen stoppar nästa debitering.
- Verifiera att kortnummer/CVC, API-nycklar och portal-/Checkout-länkar inte hamnar
  i loggar eller appens cache. Betalstatus-API:er använder `Cache-Control: no-store`.
- Visa i driftloggar endast nödvändiga referenser/statusar; larma på misslyckad
  behandling och växande avstämningsbehov. Dokumentera återställning och supportflöde.

## Etapp 6: kontrollerad produktionssättning

- Före skarpa köp: fastställ pris/moms, avtalsvillkor, uppsägning/återbetalning,
  integritetstext, Stripe-verifiering av ditt företag och kraven i PCI Dashboard.
- Lägg `STRIPE_SECRET_KEY` och `STRIPE_WEBHOOK_SECRET` i Secret Manager. Konfigurera
  `STRIPE_PRICE_MONTHLY`, `STRIPE_PRICE_YEARLY`, `PUBLIC_URL` och vald respit på servern.
  Test- och produktionsreferenser hålls åtskilda; lås en stödd Stripe-API/SDK-version.
- Testa med sandbox först. Välj sedan ett begränsat, uttryckligen godkänt skarpt köp
  för verifiering av moms, underlag, åtkomst, uppsägning och eventuell återbetalning.
- Ge ditt befintliga konto ägaråtkomst och inventera andra befintliga konton före
  aktivering. Bestäm övergångsperiod så användare inte spärras oväntat.
- Använd separata kontroller för att stoppa nya köp respektive aktivera betalspärren.
  Vid problem ska redan betalda perioder och gamla fakturor fortsätta fungera.
  En kodåterställning får inte göra nödvändiga webhook-händelser obehandlade.
- Dokumentera alla produktionssteg i CLOUD_RUN.md och användarflödet i README.md.

**Klart när:** betalning och åtkomst fungerar i produktion, driftlarm/avstämning är
aktiva och support kan hantera avbrutna köp och uppsägningar utan manuella kortuppgifter.

## Beslut som återstår före lansering

Exakt månadspris och årsavgift; moms/marknad och juridiska säljaruppgifter;
sju dagars respit eller annat intervall; villkor för prisbyte/återbetalning;
övergång för befintliga användare. Förslagen ovan gör att etapp 1 kan påbörjas
utan skarpa betalningar medan dessa beslut förbereds.

## Tekniska källor

- [Stripe Checkout och abonnemang](https://docs.stripe.com/billing/subscriptions/build-subscriptions)
- [Abonnemangshändelser](https://docs.stripe.com/billing/subscriptions/webhooks)
- [Webhook-signaturer, återförsök och ordning](https://docs.stripe.com/webhooks)
- [Stripe Customer Portal](https://support.stripe.com/questions/billing-customer-portal?locale=en-GB)

Källor kontrollerade 2026-11-02. Pris- och åtkomstreglerna är projektförslag;
exakta integrationsdetaljer kontrolleras mot vald Stripe-version när varje etapp byggs.

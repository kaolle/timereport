# Abonnemangsavstämning och förnyelsepåminnelser

Etapp 3 använder Stripe-webhooks för snabba uppdateringar och en schemalagd
avstämning för missade händelser, obetald respit och förnyelsepåminnelser.
Körningen arbetar färdigt inom HTTP-anropet; den förutsätter ingen bakgrundstråd
efter att Cloud Run skickat svaret.

## Schemalägg i Cloud Run

1. Skapa en separat slumpmässig hemlighet i Secret Manager och ge runtime-kontot
   åtkomst till den. Koppla den som `SUBSCRIPTION_MAINTENANCE_TOKEN` i Cloud Run.
2. Skapa ett Cloud Scheduler-jobb med HTTP `POST` till
   `https://<din-cloud-run-adress>/internal/subscription/maintain`.
3. Ange headern `X-Subscription-Maintenance-Token` med samma hemlighet.
   Den är separat från både Stripe webhook-signaturen och mejldispatcherns token.
4. Valt körintervall: `0 9 * * *`, tidszon `Europe/Stockholm`, och aktivera
   Scheduler-återförsök. Detta kör dagligen kl. 09. Avslut av obetalda abonnemang
   kan normalt dröja upp till ett dygn efter respiten. Åtkomstgränsen gäller ändå direkt.
5. Låt `subscription.maintenance-cron` vara `off` i produktion. Quarkus-timern
   kör endast lokalt; en nedskalad Cloud Run-instans kan inte väcka sig själv.

Scheduler-headern lagras i jobbets konfiguration. Begränsa åtkomsten till jobbet;
planerad övergång till OIDC i [INFRA_TODO.md](INFRA_TODO.md) gäller även detta jobb.
OIDC-stödet är ännu inte implementerat. Det kräver validering i applikationen
eftersom samma Cloud Run-tjänst är publik för webbsidan; enbart valet OIDC i
Scheduler ersätter inte dagens tokenkontroll.

Endpointen är dold om token saknas och svarar `403` vid fel token. Svaret innehåller
`checked`, `failed`, `remindersQueued` och `terminated`, med `Cache-Control: no-store`.
Om en eller flera konton inte kunde behandlas returneras samma summering med
HTTP `503`, så att Scheduler kan försöka igen.
Loggen visar samma summering och identifierare för avstämningsfel, utan
kundadresser, SMTP-feltexter, Stripe-nycklar eller betalningslänkar.

Lokal utveckling kör som standard var trettionde minut. Testprofilen stänger av
timern. En lokal manuell körning använder samma endpoint och token om den har
konfigurerats; ställ inga skarpa nycklar i en HTTP-fil som checkas in.

## När påminnelser skickas

- Månadsabonnemang: sju kalenderdagar före nästa debiteringsdatum, kl. 09 i
  `Europe/Stockholm`.
- Årsabonnemang: en kalendermånad före debiteringsdatumet, samma klockslag.
  Exempel: 31 mars ger 28 februari, eller 29 februari under skottår.
- En försenad körning skickar den saknade påminnelsen om förnyelsen fortfarande
  ligger i framtiden. Ingen förnyelsepåminnelse skickas vid eller efter förnyelsen.
- Ägaråtkomst utan betalning, uppsagda abonnemang och avslutade förnyelser får
  inga nya förnyelsepåminnelser.

Mejlet anger Tidrapporten, månads-/årsintervall, debiteringsdatum och belopp
inklusive moms. Det förklarar automatisk förnyelse och länkar till
`PUBLIC_URL/?subscription=manage`, där kunden får logga in och hantera sitt
abonnemang. Länken varken loggar in kunden eller säger upp abonnemanget.

Stäng av Stripes egna förnyelsepåminnelser för samma intervall så att kunden inte
får dubbla utskick. Stripes betalningskvitton och meddelanden om betalningsproblem
är separata inställningar.

## Beständighet och återförsök

V18 skapar `subscription_renewal_reminders`. En unik abonnemangs-/datum-/plannyckel
och kontolåsning skyddar mot parallella körningar. Påminnelsen och dess krypterade
`mail_outbox`-rad sparas i samma databastransaktion. Ett fel före commit lämnar
ingen halvfärdig köpost. En ny körning återanvänder den sparade planen.

Påminnelsens status följer `PLANNED`, `QUEUED` och mejlköns slutstatus, exempelvis
`SENT` eller `FAILED`. SMTP-fel hanteras av den befintliga mejlkön med tre omförsök
efter första försöket. Mejlet löper ut vid förnyelsen och skickas inte därefter.

Ändrat förnyelsedatum eller uppsägning avbryter berörda väntande mejl per
meddelandereferens, utan att påverka kontoverifiering, fakturor eller andra konton.
Ett mejl som redan överlämnas till SMTP kan inte återkallas. Vid osäkert
SMTP-resultat kan en omsändning ge en dubblett; detta är samma begränsning som
för övriga SMTP-utskick. Slutliga fel loggas och kan följas i båda tabellerna.

Åtkomst till fakturering beräknas från lokala betalda perioder och respitens
slutdatum. Avslutet hos Stripe sker vid nästa lyckade avstämning efter respiten;
med trettio minuters körintervall finns normalt upp till trettio minuters
fördröjning, längre vid driftfel. Kontrollera avstämningsfelen i loggen och skapa
larm innan skarpa betalningar aktiveras.

# Stripe-betalningar: etapp 3

V17–V18 inför verifierade betalperioder, abonnemangshistorik, avstämning och
förnyelsepåminnelser. Integrationen accepterar fortfarande endast sandbox.
Din separata ägaråtkomst påverkas inte. Behåll produktionsspärrens flaggor under
testningen; en testbetalning ska inte öppna faktureringen för vanliga användare.

## Lokal konfiguration

Använd din lokala sandbox och lokala databas. Ange i den privata konfigurationen:

```dotenv
SUBSCRIPTION_WEBHOOKS_ENABLED=true
STRIPE_WEBHOOK_SECRET=whsec_ersatt_med_din_signeringshemlighet
SUBSCRIPTION_GRACE_DAYS=7
SUBSCRIPTION_MAINTENANCE_TOKEN=ersatt_med_en_separat_slumpmassig_hemlighet
SUBSCRIPTION_INVOICING_OWNER_ONLY=false
SUBSCRIPTION_ENFORCE_INVOICE_ACCESS=true
```

`STRIPE_SECRET_KEY` och prismodellerna ska höra till samma sandbox. Webhookens
`whsec_` är en separat hemlighet, inte API-nyckeln eller portalens `bpc_`.
HTTP-klientens privata miljöfil konfigurerar inte Java-tjänsten automatiskt.

Logga in i rätt sandbox med Stripe CLI och starta en lokal lyssnare:

```bash
stripe login
stripe listen --all-snapshot --forward-to http://localhost:8080/api/subscription/webhooks/stripe
```

Kopiera lyssnarens `whsec_` till tjänstens lokala konfiguration och starta om
tjänsten. Genomför sedan ett köp från Tidrapporten. CLI-lyssnarens hemlighet
skiljer sig från en endpoint skapad i Dashboard.

## Cloud Run

1. Driftsätt versionen med V17–V18, fortfarande med webhook-flaggan avstängd.
2. I rätt sandbox, skapa en webhookdestination för **Your account**, med
   **Snapshot**-händelser och API-version `2026-10-28.endive` (SDK 34.1.0).
3. Ange URL `https://<din-tjanst>/api/subscription/webhooks/stripe`.
4. Lägg signeringshemligheten i Secret Manager, ge runtime-kontot åtkomst och
   koppla den som `STRIPE_WEBHOOK_SECRET`. Aktivera `SUBSCRIPTION_WEBHOOKS_ENABLED`.
5. Konfigurera avstämningen enligt
   [SUBSCRIPTION_MAINTENANCE.md](SUBSCRIPTION_MAINTENANCE.md).

Välj `checkout.session.completed`, `checkout.session.expired`,
`invoice.paid`, `invoice.payment_failed`, `invoice.payment_action_required`,
`invoice.voided`, `invoice.marked_uncollectible`, `invoice.finalized`,
`customer.subscription.created`, `customer.subscription.updated`,
`customer.subscription.deleted`, samt `charge.refunded` och
`charge.dispute.created/updated/closed` (välj varje händelsetyp separat).

## Betalning och återställning

- Checkout-returen ger ingen rättighet. Perioden hämtas från rätt återkommande
  fakturarad efter verifierad betalning hos Stripe.
- Dubbletter behandlas atomiskt med kontolås. Missade händelser återhämtas av
  avstämningen, även för tidigare genomförda köp med giltig lokal priskoppling.
- Första nekade betalningen ger ingen åtkomst. Misslyckad förnyelse ger normalt
  sju dagars respit från den tidigare betalda periodens slut; återförsök flyttar
  inte slutdatumet. Betalning måste ske före respitens slut.
- Efter obetald respit avslutas det gamla abonnemanget och öppna obetalda
  fakturor annulleras hos Stripe. Ett nytt köp får en egen historik.
- Sena händelser återöppnar inte ett avslutat abonnemang. Återbetalning, tvist
  och betalning efter avslut registreras i `subscription_alerts` för manuell
  avstämning; ingen automatisk återbetalning eller återaktivering görs.
- Radera inga köpförsök för att återställa betalstatus. Använd avstämningen och
  kontrollera det riktiga läget i Stripe. Äldre köp utan verifierbar priskoppling
  kräver manuell granskning.

Avstämningen tar högst 50 konton per körning och påbörjar inget nytt konto efter
40 sekunder. En pågående kontokontroll slutförs inom anropet. Beständig ordning
efter senaste kontroll förhindrar att felande konton blockerar senare konton.
Använd tillräcklig Cloud Run/Scheduler-timeout och övervaka kapaciteten när antalet
kunder växer. HTTP 503 signalerar behandlingsfel och ska ge återförsök/larm.

## Testa

Testa lyckat köp, upprepade webhookar, kortfel, extra autentisering, uppsägning,
missad webhook och bevarad ägaråtkomst. Under Konto visas betald period och en
knapp till kundportalen. Påminnelselänken öppnar Konto efter inloggning.

Stripe Test Clocks flyttar Stripes tid, medan appen och PostgreSQL använder verklig
tid. Framtida perioder kan lagras men ger inte åtkomst i förtid. Testerna med fast
applikationsklocka verifierar respit, periodslut och påminnelser; Test Clocks ensamt
simulerar inte att tid passerat i appen eller databasen.

Köpbekräftelsemejl från Tidrapporten ingår ännu inte. Förnyelsepåminnelser använder
den befintliga mejlkön; kvitton från Stripe konfigureras separat.

Teknisk referens: [Stripe-webhookar](https://docs.stripe.com/webhooks) och
[abonnemangshändelser](https://docs.stripe.com/billing/subscriptions/webhooks).

Verifierat lokalt 2026-11-22: 261 befintliga och två nya Java-tester för betalspärren,
JVM-paketering, 13 JavaScript-enhetstester samt 80 webbläsartester. Java-körningen använder
separata testprocesser och en isolerad byggkopia för att undvika påverkan från
den lokala utvecklingsserverns byggfiler. Inga riktiga Stripe- eller SMTP-anrop
kördes av testerna.

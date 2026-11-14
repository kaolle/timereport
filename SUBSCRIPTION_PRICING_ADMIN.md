# Prisadministration och administratörsroll

V15 lägger till `account_roles`. V16 lägger till en gemensam priskatalog för
Tidrapportens abonnemang. Varje pris har namn, månad/år, belopp i SEK och egen
momssats med val om moms ingår eller tillkommer. En modell per intervall väljs
som aktuellt pris för nya köp. Fakturorna som användarna skapar påverkas inte.

## Ge ditt konto rollen admin

Efter migreringarna: kontrollera först vilket registrerat konto som ska användas.
Byt exempeladressen mot den riktiga adressen; ingen adress ger automatiskt behörighet.

```sql
SELECT id, email, verified FROM accounts WHERE email = 'din-adress@example.com';

INSERT INTO account_roles (account_id, role)
SELECT id, 'admin' FROM accounts
WHERE email = 'din-adress@example.com' AND verified = true
ON CONFLICT DO NOTHING
RETURNING account_id, role;
```

Logga in igen efter tilldelningen. En automatisk tokenförnyelse hämtar också nya
roller, men en gammal access-token saknar fortfarande `admin` tills den förnyas.
Rollen finns i JWT-fältet `groups` och i `account.roles` i inloggningssvaret.
Alla konton har dessutom rollen `user`.

Administrations-API:t kräver både JWT-rollen och att databasrollen fortfarande
finns. Rollåterkallning slår därför igenom direkt, även med en äldre admin-token:

```sql
DELETE FROM account_roles
WHERE account_id = 'kontots-uuid' AND role = 'admin';
```

`admin` ger prisadministration. Den separata ägaråtkomsten till fakturering ändras
inte av rollen. En annan användare kan inte tilldela sig rollen genom registrering,
profilfält eller webbläsarens lokala lagring.

## Skapa och välj priser

1. Behåll `STRIPE_SECRET_KEY` från samma sandbox i Secret Manager. Tjänsten accepterar
   fortfarande bara `sk_test_...`. Prisadministration fungerar även med
   `SUBSCRIPTION_CHECKOUT_ENABLED=false`, så priser kan förberedas före köp.
2. Öppna **Administration** och ange prisnamn, intervall, belopp och momssats.
   Kontrollera förhandsberäkningen inklusive moms.
3. Klicka **Skapa pris i Stripe**. Tjänsten skapar produkt, momssats och återkommande
   pris. Stripe-id:n visas under **Stripe-referenser** och lagras i databasen.
4. Klicka **Använd för nya månadsköp** respektive **Använd för nya årsköp** när priset
   ska gälla. Aktuella priser markeras i listan.
5. Aktivera Checkout och testa med ett separat tillåtet köparkonto som tidigare.

`STRIPE_PRICE_MONTHLY`, `STRIPE_PRICE_YEARLY` och `STRIPE_TAX_RATE` används inte
längre. De kan tas bort från Cloud Run när den nya versionen är driftsatt.
`STRIPE_PORTAL_CONFIGURATION`, `PUBLIC_URL`, testköparlistan och Checkout-flaggan
används fortfarande. Befintliga miljövariabler kan behållas för en planerad
återgång till äldre kod, men den nya koden läser dem inte.

## Historik och återförsök

Prisdefinitioner ändras genom att skapa en ny modell och välja den som aktuell.
Gamla modeller behålls. Befintliga Stripe-abonnemang, öppna sessioner och redan
prisbundna köpförsök behåller sina priser. Ett månadspris kan därför förberedas
utan att ändra årspriset eller pågående köp.

Ett nytt pris får ett beständigt begäran-id innan Stripe kontaktas. Samma begäran
och samma Stripe-idempotensnycklar används vid återförsök. Vid ett osäkert svar:
använd **Försök skapa pris igen** eller hämta listan och slutför den väntande modellen.
Byt inte id genom att rensa tabeller. Ett påbörjat skapande som är äldre än 23 timmar
kräver avstämning, eftersom Stripe-idempotens inte ska antas gälla obegränsat.

Äldre Checkout-försök från före V16 kan redan vara knutna till en Stripe-session
skapad från miljövariabler. Öppna/färdiga sessioner återanvänds och spärras som tidigare.
Äldre försök i `CREATING` utan prisbindning stoppas för manuell avstämning i stället
för att riskera en ny session med andra parametrar. Radera inte sådana rader utan
att först kontrollera vad som skapats hos Stripe.

## API

- `GET /api/admin/subscription/prices`: lista modeller och aktuella val.
- `POST /api/admin/subscription/prices`: skapa/slutför `{id,name,plan,amount,vatPercent,vatInclusive}`.
- `POST /api/admin/subscription/prices/{id}/activate`: välj aktuell modell för dess intervall.

Alla svar har `Cache-Control: no-store`. API-nycklar och kortuppgifter lagras inte
i priskatalogen. Administrationen är tillgänglig online; normal tidrapportering
behåller sitt offlineflöde.

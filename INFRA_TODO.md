# Framtida infrastrukturförändringar

## Begränsa Brevo SMTP till godkända IP-adresser

- [ ] Utvärdera fast utgående IP för Cloud Run och IP-begränsning för Brevos SMTP-nycklar.

**Syfte:** Begränsa varifrån SMTP-nyckeln kan användas om den skulle läcka.

**Nuvarande beslut:** Låt Brevos blockering av otillåtna IP-adresser vara avstängd
tills fasta utgående IP-adresser finns. Cloud Runs vanliga utgående IP-adresser kan
variera. För tidig aktivering kan stoppa registrerings- och lösenordsåterställningsmejl.

**Genomförande:**

1. Bedöm kostnad och behov av VPC, Cloud NAT och reserverad extern IP.
2. Konfigurera Cloud Run så att utgående SMTP-trafik går genom den fasta IP-adressen.
3. Lägg till samtliga legitima utgående IP-adresser under **Security → Authorized IPs**
   i Brevo. Beakta även lokal testning och eventuella andra tjänster på samma konto.
4. Aktivera **Blocking unauthorized IP addresses → SMTP keys** först när listan är komplett.
5. Verifiera registrering och lösenordsåterställning, inklusive efter en ny Cloud Run-revision.
6. Kontrollera att SMTP-försök från en otillåten IP blockeras och dokumentera återställning
   genom att tillfälligt stänga av blockeringen om legitima utskick stoppas.

Använd under tiden en separat SMTP-nyckel för Tidrapporten och lagra den i Secret Manager
vid molndrift.

**Referenser:**

- [Brevo: godkänna och blockera IP-adresser](https://help.brevo.com/hc/en-us/articles/5740111683858-Authorize-and-block-IP-addresses-for-API-and-SMTP-security)
- [Cloud Run: fast utgående IP-adress](https://docs.cloud.google.com/run/docs/configuring/static-outbound-ip)

## Backup och återställning för Neon

- [ ] Välj och konfigurera schemalagd backup utanför databasen eller en betald backupplan.
- [ ] Dokumentera lagringstid, åtkomst och kostnad; provåterställ till en separat databas.

Ordna detta innan skarp tidsrapportering. Gratisplanens begränsade återställningshistorik
ersätter inte långsiktig backup. En eventuell flytt till Cloud SQL kräver dataexport/import
samt verifiering av konton, tider och rapporter.

## Klient-IP och rate limiting bakom Cloud Run

- [ ] Verifiera vilken direkt peer-IP appen ser i Cloud Run och hur den påverkar IP-kvoten.
- [ ] Välj en betrodd proxy-/klient-IP-strategi innan bred publik användning.

Appen ignorerar vidarebefordrade IP-headers och kan därför dela IP-kvot mellan användare
bakom samma proxy. Aktivera inte generell tillit till godtyckliga `X-Forwarded-For`-värden.

## Skicka utfärdade fakturor via e-post

Implementerat med lagrade utskicksförsök och separata Skickad-/Betald-markeringar.
SENT betyder att SMTP-servern accepterat mejlet. Automatisk hantering av studsar och
leveranskvitton återstår. `INVOICE_MAIL_FROM` kan anges för separat fakturaavsändare;
standard är befintlig `MAIL_FROM`. Ingen automatisk SMTP-omsändning sker vid osäkert
resultat; användaren bekräftar ett nytt försök efter kontroll i Brevo.

- [x] Utforma och implementera utskick av utfärdade fakturor som PDF-bilaga.
- [ ] Använd en avsändare på den autentiserade domänen, till exempel
  `fakturor@potatisblomman.se`, med visningsnamnet **Företagsnamn via Tidrapporten**.
- [x] Sätt kundens faktura-e-post som mottagare och företagets egen fakturaadress som
  `Reply-To`, så svar inte går till en obevakad no-reply-adress.
- [x] Behåll en gemensam avsändaradress för systemmejl, exempelvis
  `Tidrapporten <no-reply@potatisblomman.se>`. Undvik att presentera tenantföretag som
  om de skickar från sin egen domän när From-adressen i själva verket tillhör
  `potatisblomman.se`.
- [x] Hämta PDF:en från den utfärdade, oföränderliga fakturan. Skicka aldrig ett
  redigerbart utkast som faktura.
- [x] Lägg till explicita återförsök och idempotens för SMTP-fel; fakturans utfärdande ska inte
  skapa en ny faktura vid ett misslyckat eller upprepat mejlutskick.
- [x] Spara leveransstatus och fel utan att logga SMTP-nyckel, fakturans känsliga
  innehåll eller autentiseringslänkar.
- [ ] Verifiera SPF, DKIM och DMARC för avsändardomänen samt testa leverans, bilaga,
  Reply-To, studs och återförsök.

**Avsändarprincip:** SMTP tillåter visningsnamn och adress per meddelande, men ett
tenantföretagsnamn tillsammans med en adress på `potatisblomman.se` kan ge mottagaren
intrycket att företaget självt äger den adressen. Visa därför hellre
**Företagsnamn via Tidrapporten** och använd företagets adress som `Reply-To`. Om framtida
företagskunder ska skicka från egna domäner krävs verifiering och autentisering av
respektive domän innan den används som From-adress.

Brevos domänautentisering kan verifiera avsändare på en autentiserad domän. Registrera
SMTP-nyckeln som Secret Manager-hemlighet vid molndrift; återanvänd inte API-nyckeln.

**Referenser:**

- [Brevo: skapa avsändare och ange visningsnamn](https://help.brevo.com/hc/en-us/articles/208836149-Create-a-new-sender-From-name-and-From-email)
- [Brevo: avsändarfält för transaktionsmejl](https://developers.brevo.com/docs/send-a-transactional-email)
- [RFC 5322: format för visningsnamn och e-postadress](https://www.rfc-editor.org/rfc/rfc5322.html)

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

Använd under tiden en separat SMTP-nyckel för Tidrapport och lagra den i Secret Manager
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

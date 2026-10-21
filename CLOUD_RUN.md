# Tidrapport i Cloud Run via GitHub

Samma byggupplägg som WeatherService: Cloud Build kör tester, Google Buildpacks
bygger en Java-container och Cloud Run uppdateras med den nya bilden. Här används
GitHub, Java 21 och Maven. Databasen ligger i Neon och mejlen skickas via Brevo.
Ingen GitHub Actions-workflow eller Dockerfile behövs för detta flöde.

## 1. Förbered Google Cloud

Välj projektet där Tidrapport ska köras. Aktivera **Cloud Run Admin API**,
**Cloud Build API**, **Artifact Registry API** och **Secret Manager API**.

Skapa ett **Docker-repository** i Artifact Registry:

| Inställning | Värde                                                   |
|-------------|---------------------------------------------------------|
| Namn        | `cloud-run-source-deploy` (eller återanvänd befintligt) |
| Region      | `europe-west3` (Frankfurt, nära Neon i `eu-central-1`)  |
| Format      | Docker                                                  |

Skapa två tjänstekonton under **IAM & Admin → Service Accounts**:

| Konto                | Behörigheter                                                                                                                                                    |
|----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `timereport-build`   | Artifact Registry Writer på repositoryt; Logs Writer i projektet; Cloud Run Developer på tjänsten när den skapats; Service Account User på `timereport-runtime` |
| `timereport-runtime` | Secret Manager Secret Accessor på de fyra secrets nedan                                                                                                         |

Den användare som skapar/kör triggern behöver Cloud Build Editor och rätt att använda
byggkontot (`iam.serviceAccounts.actAs`, ingår i Service Account User). Den som skapar
Cloud Run-tjänsten behöver även rätt att driftsätta och använda runtime-kontot.
Byggkontot behöver inte kunna läsa produktionshemligheter. Använd inga nedladdade
service account-nycklar i GitHub.

## 2. Lägg upp secrets och förbered Neon

Skapa följande i **Secret Manager**. Värdena anges där, aldrig i Git eller byggvariabler.

| Secret                     | Innehåll                                      |
|----------------------------|-----------------------------------------------|
| `timereport-db-password`   | Lösenordet till Neon-rollen                   |
| `timereport-smtp-password` | Brevos SMTP-nyckel                            |
| `timereport-jwt-private`   | Hela `private.pem`, inklusive BEGIN/END-rader |
| `timereport-jwt-public`    | Hela `public.pem`, inklusive BEGIN/END-rader  |

Välj explicita versionsnummer när secrets kopplas till tjänsten, exempelvis `1`.
Det gör att en ny secret-version inte byter nycklar under en redan körande revision.

I Neon: öppna **Connect**, välj databasen `timereport` och rollen som äger databasen,
och stäng av **Connection pooling**. Kopiera det direkta hostnamnet utan `-pooler`.
Flyway skapar tabellerna vid första starten; rollen behöver kunna skapa dem.
Neon kräver TLS. JDBC-adressen nedan använder Javas certifikatlager och kontrollerar
både servercertifikat och hostnamn. Använd inte en full `postgresql://user:password@...`-sträng
som `DB_URL`: använd JDBC-format och separata inloggningsuppgifter.

## 3. Koppla GitHub och bygg första bilden

1. Se till att ändringarna finns i GitHub-repot `kaolle/timereport`.
2. Öppna **Cloud Build → Triggers → Connect repository**. Anslut GitHub och ge
   Google Cloud Build GitHub-appen åtkomst till detta repo. Om konsolen visar
   **Repositories → 2nd gen**, skapa GitHub-anslutningen där först och länka repot.
3. Skapa triggern `timereport-main` i `europe-west3`. Välj **Push to a branch**,
   repo `kaolle/timereport` och branch-regex `^main$`.
4. Välj **Cloud Build configuration file (YAML or JSON)**, plats **Repository**,
   fil **`cloudbuild.yaml`** och tjänstekonto **`timereport-build`**.
5. Ange substitutionerna nedan. Sätt **`_DEPLOY=false` för första bygget** och kör
   triggern manuellt via **Run**. Tester och bildbygge körs, men ingen app startas ännu.

| Substitution  | Värde                       |
|---------------|-----------------------------|
| `_REGION`     | `europe-west3`              |
| `_REPOSITORY` | `cloud-run-source-deploy`   |
| `_SERVICE`    | `timereport`                |
| `_DEPLOY`     | `false` först, sedan `true` |

Cloud Build sätter `PROJECT_ID` och `BUILD_ID` automatiskt. Bilden finns efter bygget på:

```text
europe-west3-docker.pkg.dev/PROJECT_ID/cloud-run-source-deploy/timereport:BUILD_ID
```

Backendtester använder en tillfällig PostgreSQL-container. JavaScript- och
Playwright-tester körs innan bilden byggs. Bygget använder inga Neon-/Brevo-uppgifter
och kontaktar inte produktionsdatabasen. Buildpacks får en separat källkatalog med
enbart Maven-filer och `src`; lokala env-filer och JWT-nycklar följer inte med.

## 4. Skapa Cloud Run-tjänsten

Välj **Cloud Run → Create service → Deploy one revision from an existing container image**.
Välj bilden från första bygget. GitHub-kopplingen hanteras redan av Cloud Build-triggern.

| Inställning                   | Startvärde                                              |
|-------------------------------|---------------------------------------------------------|
| Service name / region         | `timereport` / `europe-west3`                           |
| Authentication                | Require authentication under den första konfigurationen |
| Service account               | `timereport-runtime`                                    |
| Container port                | `8080`                                                  |
| Container command / arguments | Lämna tomma; Buildpacks anger startkommandot            |
| CPU / minne                   | 1 vCPU / 512 MiB                                        |
| Billing                       | Request-based                                           |
| Min/max instances             | 0 / 2                                                   |
| Concurrency                   | 20                                                      |
| Ingress                       | All                                                     |

Appen använder Quarkus standardport **8080**. Ange ingen egen `PORT`-miljövariabel.
Cloud Run injicerar `PORT` automatiskt, men appen behöver inte läsa den eftersom
containerporten också är 8080.

Under **Variables & Secrets**, lägg in följande vanliga miljövariabler:

| Variabel               | Värde                                                                                                                             |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `PUBLIC_URL`           | Tillfälligt `https://setup.invalid`; ersätt i nästa steg                                                                          |
| `DB_URL`               | `jdbc:postgresql://DIN_DIREKTA_NEON_HOST:5432/timereport?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` |
| `DB_USERNAME`          | Neon-rollen, exempelvis `timereport-role`                                                                                         |
| `DB_POOL_MAX_SIZE`     | `4`                                                                                                                               |
| `SMTP_HOST`            | `smtp-relay.brevo.com`                                                                                                            |
| `SMTP_PORT`            | `587`                                                                                                                             |
| `SMTP_USERNAME`        | Login-värdet från Brevo                                                                                                           |
| `MAIL_FROM`            | `noreply@potatisblomman.se`, verifierad i Brevo                                                                                   |

Under **Variables & Secrets → Reference a secret**, koppla följande
**miljövariabler från Secret Manager**:

| Miljövariabel     | Secret                     |
|-------------------|----------------------------|
| `DB_PASSWORD`     | `timereport-db-password`   |
| `SMTP_PASSWORD`   | `timereport-smtp-password` |
| `JWT_PRIVATE_KEY` | `timereport-jwt-private`   |
| `JWT_PUBLIC_KEY`  | `timereport-jwt-public`    |

Välj ett explicit versionsnummer för varje secret. JWT-värdena ska vara hela PEM-texter
med BEGIN/END-rader och riktiga radbrytningar, utan extra citattecken eller bokstavliga `\n`.
Appen kontrollerar vid uppstart att nycklarna är ett matchande RSA-par med minst 2048 bitar.

Inga JWT-volymer behövs. Om tjänsten redan använder JWT-secrets med miljövariabelnamnen
`JWT_PRIVATE_KEY_PATH` och `JWT_PUBLIC_KEY_PATH`, byt namnen till `JWT_PRIVATE_KEY` och
`JWT_PUBLIC_KEY` när den nya appversionen deployas. Behåll samma secrets och nyckelvärden.
Ta bort gamla JWT-sökvägsvariabler och eventuella JWT-volymmonteringar när du väljer värden;
appen avvisar blandad konfiguration. `_PATH`-variablerna finns kvar för lokala PEM-filer,
se README.

Valfri HTTP-startup probe: port `8080`, path `/q/health/started`, period `10` sekunder,
timeout `5` sekunder, failure threshold `24`. Använd `/q/health/live` för eventuell
liveness probe, inte databasberoende `/q/health/ready` som kan påverkas när Neon vaknar.

Skapa tjänsten. Kopiera dess HTTPS-URL och välj **Edit & deploy new revision**:
ändra `PUBLIC_URL` till exakt denna URL utan avslutande `/`. När revisionen är klar,
välj **Allow public access** i tjänstens säkerhetsinställningar. Webbsidan och
inloggnings-API:t behöver vara publika; affärs-API:erna kräver fortfarande appens JWT.
`setup.invalid` används bara för uppstarten: testa inga mejlflöden innan adressen ändrats.

## 5. Slå på automatisk deployment och verifiera

Ge byggkontot Cloud Run Developer på den skapade tjänsten. Ändra triggerns
`_DEPLOY` till `true`. Nästa push till `main` kör tester, bygger en unik image och
uppdaterar tjänsten. Deploymentsteget kräver en befintlig tjänst och bevarar dess
miljövariabler, secrets, runtime-konto, resurser, skalning och åtkomstinställningar.

Kontrollera först `/q/health/ready` och sedan registrering, bekräftelsemejl, inloggning,
sidomladdning/refresh-token, lösenordsåterställning, tidregistrering, rapport och offline-synk.
Kontrollera att flytten till ett nytt ursprung inte lämnar osynkade tider i localhost;
webbläsarlagring delas inte mellan ursprung. Lokala databasposter flyttas inte automatiskt.

Cloud Run kan starta extra instanser under en utrullning. Anpassa poolstorlek och maxantal
instanser till Neons anslutningsgräns; 4 anslutningar per instans är startvärdet här.
En första begäran kan ta längre tid när både Cloud Run och Neon har gått i vila.
Schemalagda hälsopingar till databasen kan motverka Neons vila.

Flyway kör oföränderliga migreringar vid start. Ändra aldrig redan använda SQL-filer.
En återgång till föregående Cloud Run-revision återställer bara programmet, inte databasen.
Håll därför migreringar kompatibla med föregående revision och ordna backup före skarp drift.
Kvarvarande infrastrukturarbete finns i [INFRA_TODO.md](INFRA_TODO.md), inklusive
backuper och klient-IP bakom Cloud Runs proxy.

## Referenser

- [Cloud Build och kontinuerlig Cloud Run-deployment](https://docs.cloud.google.com/run/docs/manually-set-up-continuous-deployment)
- [Google Buildpacks för Java](https://docs.cloud.google.com/docs/buildpacks/java)
- [Byggvariabler](https://docs.cloud.google.com/docs/buildpacks/service-specific-configs)
- [Secret Manager i Cloud Run](https://docs.cloud.google.com/run/docs/configuring/services/secrets)
- [Neon-anslutningar](https://neon.com/docs/get-started/connect-neon)

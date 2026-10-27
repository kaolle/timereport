# Implementeringsplan: fakturering

## Mål

Skapa fakturor från tidrapporter med en konfiguration per användarkonto. Första versionen ska skapa en faktura i taget för en vald kund och månad. Datamodellen ska från början stödja flera kunder och flera kundkoder per kund, så att användaren senare kan skapa separata fakturor för flera kunder under samma period.

## Nuläge och fakturamall

Tjänsten har kontoisolerade tidposter, kundregister, fakturaprofil och månadsrapport.
Kundkoder kan kopplas till fakturakunder med tjänstebeskrivning, timpris och moms.
Månadsutkast kan justeras, förhandsgranskas som PDF och utfärdas med ett permanent
fakturanummer. Historikvyn har grupperna Förfallna, Obetalda och Betalda, med manuella statusmarkeringar.
Utfärdade PDF:er kan laddas ned eller skickas via SMTP; driftuppföljning finns i
[INFRA_TODO.md](INFRA_TODO.md). Fakturamallen finns i [mallar/faktura-1061.pdf](mallar/faktura-1061.pdf).

Mallen är en ensidig faktura med fakturametadata och referenser överst, faktureringsadress, tabell med tjänst/antal/timpris/belopp, nettobelopp, moms, avrundning och totalsumma samt säljarens adress- och betalningsuppgifter i sidfoten. PDF:ens tredjepartslogotyp och attribution ska inte återanvändas utan uttryckligt önskemål och rätt att använda dem.

En fullständig svensk faktura behöver bland annat datum, unikt löpnummer, säljar- och köparuppgifter, tjänstens omfattning och momsuppgifter. Exakt krav beror på försäljningens omständigheter; kontrollera slutlig utformning med redovisningskunnig. [Skatteverkets fakturakrav](https://www.skatteverket.se/foretag/moms/saljavarorochtjanster/fakturering.4.58d555751259e4d66168000403.html)

## Föreslagen ordning

### Etapp 1: kundregister och fakturaprofil

- Lägg till fakturakund som separat begrepp från kundkod. En kund tillhör ett `account_id` och har namn, fakturaadress, organisations-/momsnummer, kundnummer, referens och betalningsvillkor.
- Lägg till säljarens fakturaprofil per konto: juridiskt namn, adress, organisations-/momsnummer, betalningsuppgifter, standardvillkor och eventuellt logotyp.
- Lägg till en faktureringskoppling för varje kundkod: vilken fakturakund koden tillhör, tjänstebeskrivning, timpris och eventuell momshantering. En kund ska kunna ha flera koder.
- Befintliga koder ska kunna kopplas till kunder manuellt. Ändra inte historiska tidsregistreringar automatiskt.

### Etapp 2: månadsutkast för en vald kund

- Användaren väljer månad och en registrerad kund.
- Hämta synkroniserade tidsregistreringar vars kundkoder är kopplade till kunden. Sammanställ per vecka och kundkod; skapa en rad per kod när pris eller tjänst skiljer sig.
- Beräkna timmar från registrerad tid efter avdrag. Semester och andra dagar utan fakturerbar tid ska inte ingå.
- Tider utan kod, utan faktureringskoppling eller kopplade till en annan kund visas separat och lämnas utanför kundens underlag.
- Visa en varning och blockera förhandsgranskning/utfärdande när lokala tider i månaden inte hunnit synkroniseras.
  Utkastet räknar från serverns synkroniserade poster.
- Veckosammanställningen är en läsbar referens till tidsunderlaget. Före utfärdande kan användaren justera fakturaradens
  beskrivning, timmar, timpris och moms utan att ändra tidsregistreringarna.

### Etapp 3: PDF och utfärdande

- Återskapa mallens en-sideslayout med kontots säljaruppgifter, vald kund, fakturadatum, förfallodatum, referenser, tjänsteperiod, timmar, timpris, netto, moms, avrundning och att betala.
- Använd decimalexakta belopp och definierade avrundningsregler. Stöd standardmoms men lagra momssats per fakturarad så undantag kan hanteras senare.
- Tilldela ett unikt löpnummer i en transaktion vid utfärdandet, inte när ett redigerbart utkast skapas. Utfärdade fakturor får inte skrivas över.
- Visa en PDF-förhandsgranskning märkt UTKAST med preliminärt nummer; kräv en ny förhandsgranskning efter varje ändring
  innan användaren kan utfärda fakturan.
- Låt användaren ange nästa fakturanummer innan första utfärdandet, så en befintlig serie kan fortsätta (för Kaolle
  börjar den på 1061). Löpnumret får bara höjas, aldrig sänkas eller återanvändas.
- Spara en ögonblicksbild av kund, säljare, rader, belopp, datum och villkor så att framtida ändringar i inställningar eller tidposter inte ändrar fakturan.

### Etapp 4: historik, leverans och betalstatus (implementerat)

- Lista utfärdade fakturor i grupperna Förfallna, Obetalda och Betalda, med summa och moms per valuta. Redigerbara
  utkast sparas ännu inte mellan sessioner.
- Lagra utfärdade PDF-filer i PostgreSQL tillsammans med fakturaögonblicksbilden. Cloud Storage kan införas senare vid
  behov.
- Visa en leveransdialog efter utfärdande med separata val för nedladdning och e-post. Tillåt även leverans från
  historiklistan.
- Spara idempotenta utskicksförsök separat från fakturan. Skickad markeras efter SMTP-acceptans och kan också markeras
  manuellt.
- Hantera Betald som en manuell, reversibel status. Versionskontroll skyddar samtidiga statusändringar. Kreditfakturor
  återstår.

### Senare etapper

- Skapa fakturautkast för alla kunder med fakturerbar tid i en månad.
- Gör kundkod obligatorisk för nya tidsregistreringar när användaren aktiverar inställningen. Behåll äldre poster utan kod och kräv hantering av dem separat.
- Lägg till leveranskvitton/studshantering, kreditfakturor, bokföringsexport och eventuell företagsnivå med
  användarmedlemskap.

## Arkitektur och datagränser

Skapa en `com.timereport.invoicing`-modul enligt ports-and-adapters. Domänen äger fakturakunder, fakturaprofil, utkast, fakturarader och utfärdade ögonblicksbilder. REST-adaptern äger request/response-DTO:er; en utgående adapter använder PostgreSQL. Alla kund-, profil- och fakturaposter avgränsas med `account_id`.

Inför nya Flyway-migreringar; ändra inte redan tillämpade migreringar. Håll fakturans utfärdade data oföränderliga och separerade från levande tidposter. Senare företagsstöd bör utöka ägarskapet från konto till företag och medlemskap utan att dela fakturor mellan konton av misstag.

## Acceptanskriterier för första användbara versionen

- En användare kan registrera fakturaprofil, lägga in fakturakunder och koppla flera kundkoder till samma kund.
- Ett månadsutkast för vald kund visar tidsunderlaget per relevant kundkod, tillåter fakturajusteringar och visar den
  resulterande PDF:en innan utfärdande.
- Ofördelade eller osynkroniserade tider kan inte tyst försvinna in i faktureringen.
- PDF:en innehåller mallens centrala fält, rätt summeringar och fakturanummer; den går att ladda ned.
- Konton kan bara läsa och utfärda sina egna kunders och fakturors data.
- Utfärdad faktura ändras inte om en tidspost eller kundinställning senare ändras.

## Gränssnittets faktureringsmeny

Fakturering delas i flikarna **Ny faktura**, **Fakturor**, **Ditt företag** och **Kunder**. Flikarna
stödjer klick, vänster-/högerpil och Home/End. Ny faktura samlar
månadsurval, fakturautkast, PDF-förhandsvisning och utfärdande. Företagsdelen innehåller
säljaruppgifter och fakturanummerserie. Kundkortet äger visningen och inställningarna
för sina kundkoder. Fristående kodhantering ligger kvar under Kunder för att kunna
aktivera/inaktivera koder; nya koder kan skapas både från kundkortet och direkt vid
tidsregistrering.

Veckosammanställningen i Ny faktura är dold från början och öppnas med
**Visa tidsunderlag**. Åtgärdsknapparna använder samma typografi, höjd och mellanrum,
med gemensamma stilar för huvudåtgärder, övriga åtgärder och borttagning.

## Beslut och återstående frågor

1. Varje kundkod har eget timpris, tjänstebeskrivning och momssats.
2. En faktura har en månadsrad per kundkod. Raden kan justeras före utfärdande.
3. Utfärdade fakturor kan laddas ned eller skickas med e-post, och betalning markeras manuellt.
4. Vilka momsfall behöver stöd från start, särskilt om fakturamottagare finns utanför Sverige?

# Ägaråtkomst till fakturering

Migration V13 skapar en engångsbehörighet för det befintliga kontot `kaolle@gmail.com`,
om kontot finns när migrationen körs. Rättigheten binds till kontots UUID. Ett nytt konto
med samma e-postadress får inte rättigheten automatiskt. Faktureringsspärren är avstängd
under etapp 1 (`SUBSCRIPTION_ENFORCE_INVOICE_ACCESS=false`).

Kontrollera efter migrering i **rätt databas**:

```sql
SELECT a.id, a.email, g.granted_at, g.granted_by, g.reason, g.revoked_at
FROM accounts a
LEFT JOIN subscription_access_grants g ON g.account_id = a.id AND g.kind = 'OWNER'
WHERE a.email = 'kaolle@gmail.com';
```

En rad med `revoked_at IS NULL` bekräftar ägaråtkomst. Om kontot inte fanns vid
migreringen, registrera/verifiera det först. Ge sedan en spårbar behörighet med
kontots UUID och den faktiska operatörens namn. Kör i `psql`:

```sql
\set account_id 'KONTO_UUID_FRÅN_FRÅGAN_OVAN'
\set operator 'OPERATÖRENS_NAMN'
BEGIN;
INSERT INTO subscription_access_grants
    (id, account_id, kind, granted_by, reason)
SELECT gen_random_uuid(), id, 'OWNER', :'operator',
       'Owner access approved for the existing Tidrapporten account'
FROM accounts a
WHERE a.id = :'account_id'::uuid
  AND NOT EXISTS (SELECT 1 FROM subscription_access_grants g
                  WHERE g.account_id = a.id AND g.revoked_at IS NULL);
COMMIT;
```

Om behörigheten ska återkallas, ange operatör och skäl i samma databas:

```sql
UPDATE subscription_access_grants
SET revoked_at = now(), revoked_by = 'OPERATÖRENS_NAMN',
    revoke_reason = 'SKÄL'
WHERE account_id = 'KONTO_UUID'::uuid AND kind = 'OWNER' AND revoked_at IS NULL;
```

Kontrollera att rätt kontos UUID används innan ändringen. Aktivera inte
`SUBSCRIPTION_ENFORCE_INVOICE_ACCESS=true` förrän betalflödet och övergången för
andra befintliga konton är klara enligt etapp 6 i implementeringsplanen.

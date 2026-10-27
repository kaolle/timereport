package com.timereport.invoicing.adapter.out.persistence;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.invoicing.application.port.out.InvoicingSettingsRepository;
import com.timereport.invoicing.application.service.InvoicingConflict;
import com.timereport.invoicing.domain.BillingCustomer;
import com.timereport.invoicing.domain.CustomerCodeBilling;
import com.timereport.invoicing.domain.SellerBillingProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JdbcInvoicingSettingsRepository implements InvoicingSettingsRepository {
    private final DataSource dataSource;
    private final AccountIdentity identity;

    public JdbcInvoicingSettingsRepository(DataSource dataSource, AccountIdentity identity) {
        this.dataSource = dataSource;
        this.identity = identity;
    }

    @Override
    public Optional<SellerBillingProfile> profile() {
        String sql = "SELECT seller_name,address_line,postal_code,city,country,organization_number,vat_number,bankgiro,email,"
                + "currency_code,payment_terms_days,default_vat_percent,f_tax_registered FROM billing_profiles WHERE account_id=?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, identity.accountId());
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapProfile(result)) : Optional.empty();
            }
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override
    @Transactional
    public void saveProfile(SellerBillingProfile p) {
        String sql = "INSERT INTO billing_profiles (account_id,seller_name,address_line,postal_code,city,country,organization_number,"
                + "vat_number,bankgiro,email,currency_code,payment_terms_days,default_vat_percent,f_tax_registered) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (account_id) DO UPDATE SET seller_name=EXCLUDED.seller_name,"
                + "address_line=EXCLUDED.address_line,postal_code=EXCLUDED.postal_code,city=EXCLUDED.city,country=EXCLUDED.country,"
                + "organization_number=EXCLUDED.organization_number,vat_number=EXCLUDED.vat_number,bankgiro=EXCLUDED.bankgiro,"
                + "email=EXCLUDED.email,currency_code=EXCLUDED.currency_code,payment_terms_days=EXCLUDED.payment_terms_days,"
                + "default_vat_percent=EXCLUDED.default_vat_percent,f_tax_registered=EXCLUDED.f_tax_registered";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, identity.accountId());
            statement.setString(2, p.sellerName()); statement.setString(3, p.addressLine());
            statement.setString(4, p.postalCode()); statement.setString(5, p.city()); statement.setString(6, p.country());
            statement.setString(7, p.organizationNumber()); statement.setString(8, p.vatNumber());
            statement.setString(9, p.bankgiro()); statement.setString(10, p.email()); statement.setString(11, p.currencyCode());
            statement.setInt(12, p.paymentTermsDays()); statement.setBigDecimal(13, p.defaultVatPercent());
            statement.setBoolean(14, p.fTaxRegistered()); statement.executeUpdate();
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override
    public List<BillingCustomer> customers() {
        String sql = "SELECT id,customer_number,name,short_name,address_line,postal_code,city,country,organization_number,vat_number,"
                + "invoice_email,customer_reference,our_reference,payment_terms_days,contact_email FROM billing_customers WHERE account_id=? ORDER BY customer_number";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, identity.accountId());
            try (var result = statement.executeQuery()) {
                var rows = new ArrayList<BillingCustomer>();
                while (result.next()) rows.add(mapCustomer(result));
                return List.copyOf(rows);
            }
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override
    @Transactional
    public void createCustomer(BillingCustomer c) {
        String sql = "INSERT INTO billing_customers (id,account_id,customer_number,name,short_name,address_line,postal_code,city,country,"
                + "organization_number,vat_number,invoice_email,customer_reference,our_reference,payment_terms_days,contact_email) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            bindCustomer(statement, c);
            statement.executeUpdate();
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override
    @Transactional
    public void updateCustomer(BillingCustomer c) {
        String sql = "UPDATE billing_customers SET customer_number=?,name=?,short_name=?,address_line=?,postal_code=?,city=?,country=?,"
                + "organization_number=?,vat_number=?,invoice_email=?,customer_reference=?,our_reference=?,payment_terms_days=?,contact_email=? WHERE id=? AND account_id=?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, c.customerNumber()); statement.setString(2, c.name()); statement.setString(3, c.shortName()); statement.setString(4, c.addressLine());
            statement.setString(5, c.postalCode()); statement.setString(6, c.city()); statement.setString(7, c.country());
            statement.setString(8, c.organizationNumber()); statement.setString(9, c.vatNumber()); statement.setString(10, c.invoiceEmail());
            statement.setString(11, c.customerReference()); statement.setString(12, c.ourReference()); statement.setInt(13, c.paymentTermsDays());
            statement.setString(14, c.contactEmail());
            statement.setObject(15, c.id());
            statement.setObject(16, identity.accountId());
            if (statement.executeUpdate() != 1) throw new InvoicingConflict("Kunden finns inte.");
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override
    @Transactional
    public void deleteCustomer(BillingCustomer c) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("DELETE FROM billing_customers WHERE id=? AND account_id=?")) {
            statement.setObject(1, c.id()); statement.setObject(2, identity.accountId());
            if (statement.executeUpdate() != 1) throw new InvoicingConflict("Kunden finns inte.");
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override
    public List<CustomerCodeBilling> customerCodes() {
        String sql = "SELECT c.code,c.active,b.customer_id,b.service_description,b.hourly_rate,b.vat_percent FROM customer_codes c "
                + "LEFT JOIN billing_customer_codes b ON b.account_id=c.account_id AND b.code=c.code WHERE c.account_id=? ORDER BY c.code";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, identity.accountId());
            try (var result = statement.executeQuery()) {
                var rows = new ArrayList<CustomerCodeBilling>();
                while (result.next()) {
                    rows.add(CustomerCodeBilling.builder().code(result.getString("code")).active(result.getBoolean("active"))
                            .customerId(result.getObject("customer_id", UUID.class)).serviceDescription(result.getString("service_description"))
                            .hourlyRate(result.getBigDecimal("hourly_rate")).vatPercent(result.getBigDecimal("vat_percent")).build());
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override
    @Transactional
    public void saveCustomerCode(CustomerCodeBilling c) {
        String sql = "INSERT INTO billing_customer_codes(account_id,code,customer_id,service_description,hourly_rate,vat_percent) VALUES(?,?,?,?,?,?) "
                + "ON CONFLICT(account_id,code) DO UPDATE SET customer_id=EXCLUDED.customer_id,service_description=EXCLUDED.service_description,"
                + "hourly_rate=EXCLUDED.hourly_rate,vat_percent=EXCLUDED.vat_percent";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, identity.accountId()); statement.setString(2, c.code()); statement.setObject(3, c.customerId());
            statement.setString(4, c.serviceDescription()); statement.setBigDecimal(5, c.hourlyRate()); statement.setBigDecimal(6, c.vatPercent());
            statement.executeUpdate();
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override
    @Transactional
    public void removeCustomerCode(String code) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "DELETE FROM billing_customer_codes WHERE account_id=? AND code=?")) {
            statement.setObject(1, identity.accountId()); statement.setString(2, code.strip()); statement.executeUpdate();
        } catch (SQLException e) { throw databaseError(e); }
    }

    private void bindCustomer(java.sql.PreparedStatement s, BillingCustomer c) throws SQLException {
        s.setObject(1, c.id()); s.setObject(2, identity.accountId()); s.setString(3, c.customerNumber()); s.setString(4, c.name());
        s.setString(5, c.shortName()); s.setString(6, c.addressLine()); s.setString(7, c.postalCode()); s.setString(8, c.city()); s.setString(9, c.country());
        s.setString(10, c.organizationNumber()); s.setString(11, c.vatNumber()); s.setString(12, c.invoiceEmail());
        s.setString(13, c.customerReference()); s.setString(14, c.ourReference()); s.setInt(15, c.paymentTermsDays());
        s.setString(16, c.contactEmail());
    }

    private SellerBillingProfile mapProfile(ResultSet r) throws SQLException {
        return SellerBillingProfile.builder().sellerName(r.getString("seller_name")).addressLine(r.getString("address_line"))
                .postalCode(r.getString("postal_code")).city(r.getString("city")).country(r.getString("country"))
                .organizationNumber(r.getString("organization_number")).vatNumber(r.getString("vat_number"))
                .bankgiro(r.getString("bankgiro")).email(r.getString("email")).currencyCode(r.getString("currency_code"))
                .paymentTermsDays(r.getInt("payment_terms_days")).defaultVatPercent(r.getBigDecimal("default_vat_percent"))
                .fTaxRegistered(r.getBoolean("f_tax_registered")).build();
    }

    private BillingCustomer mapCustomer(ResultSet r) throws SQLException {
        return BillingCustomer.builder().id(r.getObject("id", UUID.class)).customerNumber(r.getString("customer_number"))
                .name(r.getString("name")).shortName(r.getString("short_name")).addressLine(r.getString("address_line")).postalCode(r.getString("postal_code"))
                .city(r.getString("city")).country(r.getString("country")).organizationNumber(r.getString("organization_number"))
                .vatNumber(r.getString("vat_number")).invoiceEmail(r.getString("invoice_email"))
                .customerReference(r.getString("customer_reference")).ourReference(r.getString("our_reference"))
                .paymentTermsDays(r.getInt("payment_terms_days")).contactEmail(r.getString("contact_email")).build();
    }

    private RuntimeException databaseError(SQLException error) {
        if ("23505".equals(error.getSQLState())) return new InvoicingConflict("Kundnumret används redan eller en post har ändrats samtidigt.");
        if ("23503".equals(error.getSQLState())) return new InvoicingConflict("Kund eller kundkod kunde inte hittas för det här kontot.");
        return new IllegalStateException("Databasåtgärden för faktureringsinställningar misslyckades.", error);
    }
}

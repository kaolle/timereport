package com.timereport.invoicing.application.service;

import com.timereport.invoicing.application.port.in.InvoicingSettings;
import com.timereport.invoicing.application.port.out.InvoicingSettingsRepository;
import com.timereport.invoicing.domain.BillingCustomer;
import com.timereport.invoicing.domain.CustomerCodeBilling;
import com.timereport.invoicing.domain.SellerBillingProfile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class InvoicingSettingsService implements InvoicingSettings {
    private final InvoicingSettingsRepository repository;

    public InvoicingSettingsService(InvoicingSettingsRepository repository) { this.repository = repository; }

    @Override public Optional<SellerBillingProfile> profile() { return repository.profile(); }

    @Override public SellerBillingProfile saveProfile(SellerBillingProfile profile) {
        repository.saveProfile(profile);
        return profile;
    }

    @Override public List<BillingCustomer> customers() { return List.copyOf(repository.customers()); }

    @Override public BillingCustomer createCustomer(BillingCustomer customer) {
        repository.createCustomer(customer);
        return customer;
    }

    @Override public BillingCustomer updateCustomer(BillingCustomer customer) {
        if (repository.customers().stream().noneMatch(existing -> existing.id().equals(customer.id()))) {
            throw new InvoicingConflict("Kunden finns inte.");
        }
        repository.updateCustomer(customer);
        return customer;
    }

    @Override public void deleteCustomer(UUID id) {
        BillingCustomer customer = repository.customers().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new InvoicingConflict("Kunden finns inte."));
        boolean assigned = repository.customerCodes().stream().anyMatch(code -> id.equals(code.customerId()));
        if (assigned) throw new InvoicingConflict("Ta bort kundkodernas koppling innan kunden tas bort.");
        repository.deleteCustomer(customer);
    }

    @Override public List<CustomerCodeBilling> customerCodes() { return List.copyOf(repository.customerCodes()); }

    @Override public CustomerCodeBilling saveCustomerCode(CustomerCodeBilling settings) {
        if (settings.customerId() == null) throw new IllegalArgumentException("Välj en kund.");
        repository.saveCustomerCode(settings);
        return settings;
    }

    @Override public void removeCustomerCode(String code) { repository.removeCustomerCode(code); }
}

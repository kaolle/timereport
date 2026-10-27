package com.timereport.invoicing.application.service;

import com.timereport.invoicing.application.port.in.InvoiceDrafts;
import com.timereport.invoicing.application.port.in.InvoiceIssuance;
import com.timereport.invoicing.application.port.in.InvoicingSettings;
import com.timereport.invoicing.application.port.out.InvoicePdfGenerator;
import com.timereport.invoicing.application.port.out.InvoiceRepository;
import com.timereport.invoicing.application.port.out.InvoiceTransaction;
import com.timereport.invoicing.domain.IssuedInvoice;
import com.timereport.invoicing.domain.IssuedInvoiceDocument;
import com.timereport.invoicing.domain.InvoiceLineAdjustment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.UUID;

public class InvoiceIssuanceService implements InvoiceIssuance {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Stockholm");
    private final InvoiceDrafts drafts;
    private final InvoicingSettings settings;
    private final InvoiceRepository invoices;
    private final InvoiceTransaction transaction;
    private final InvoicePdfGenerator pdfGenerator;

    public InvoiceIssuanceService(InvoiceDrafts drafts, InvoicingSettings settings, InvoiceRepository invoices,
                                  InvoiceTransaction transaction, InvoicePdfGenerator pdfGenerator) {
        this.drafts = drafts;
        this.settings = settings;
        this.invoices = invoices;
        this.transaction = transaction;
        this.pdfGenerator = pdfGenerator;
    }

    @Override
    public IssuedInvoiceDocument preview(String month, UUID customerId, List<InvoiceLineAdjustment> adjustments) {
        return createDocument(month, customerId, invoices.getNextNumber(), adjustments, true);
    }

    @Override
    public IssuedInvoiceDocument issue(String month, UUID customerId, List<InvoiceLineAdjustment> adjustments) {
        return transaction.execute(() -> createDocument(month, customerId, invoices.allocateNextNumber(), adjustments, false));
    }

    private IssuedInvoiceDocument createDocument(String month, UUID customerId, int invoiceNumber,
                                                 List<InvoiceLineAdjustment> adjustments, boolean preview) {
        var draft = drafts.create(month, customerId);
        if (draft.workedMinutes() <= 0 || draft.weeks().stream().allMatch(week -> week.lines().isEmpty())) {
            throw new IllegalArgumentException("Det finns inga fakturerbara timmar för kunden och månaden.");
        }
        var seller = settings.profile().orElseThrow(() -> new IllegalArgumentException(
                "Du har inte registrerat ditt företag. Fyll i och spara uppgifterna under Ditt företag först."));
        var customer = settings.customers().stream().filter(row -> row.id().equals(customerId)).findFirst()
                .orElseThrow(() -> new InvoicingConflict("Kunden finns inte."));
        LocalDate invoiceDate = LocalDate.now(BUSINESS_ZONE);
        LocalDate dueDate = invoiceDate.plusDays(customer.paymentTermsDays());
        var invoice = snapshot(invoiceNumber, draft, seller, customer, invoiceDate, dueDate, adjustments);
        byte[] pdf = preview ? pdfGenerator.generateDraft(invoice) : pdfGenerator.generate(invoice);
        if (!preview) invoices.save(invoice, pdf);
        return IssuedInvoiceDocument.builder().invoice(invoice).pdf(pdf).build();
    }

    private IssuedInvoice snapshot(int number, com.timereport.invoicing.domain.InvoiceDraft draft,
                                   com.timereport.invoicing.domain.SellerBillingProfile seller,
                                   com.timereport.invoicing.domain.BillingCustomer customer,
                                   LocalDate invoiceDate, LocalDate dueDate, List<InvoiceLineAdjustment> adjustments) {
        record Accumulator(String description, long minutes, BigDecimal rate, BigDecimal vatPercent) {
            Accumulator add(com.timereport.invoicing.domain.InvoiceDraft.Line line) {
                return new Accumulator(description, minutes + line.workedMinutes(), rate, vatPercent);
            }
        }
        Map<String, Accumulator> grouped = new TreeMap<>();
        draft.weeks().forEach(week -> week.lines().forEach(line -> grouped.compute(line.customerCode(),
                (code, current) -> current == null
                        ? new Accumulator(line.description(), line.workedMinutes(), line.hourlyRate(), line.vatPercent())
                        : current.add(line))));
        Map<String, InvoiceLineAdjustment> changes = new HashMap<>();
        for (InvoiceLineAdjustment adjustment : adjustments == null ? List.<InvoiceLineAdjustment>of() : adjustments) {
            if (!grouped.containsKey(adjustment.customerCode()))
                throw new InvoicingConflict("Kundkoden " + adjustment.customerCode() + " finns inte i fakturaunderlaget.");
            if (changes.putIfAbsent(adjustment.customerCode(), adjustment) != null)
                throw new InvoicingConflict("Kundkoden " + adjustment.customerCode() + " förekommer flera gånger i ändringarna.");
        }
        var lines = new ArrayList<IssuedInvoice.Line>();
        Map<BigDecimal, BigDecimal[]> taxes = new TreeMap<>();
        grouped.forEach((code, row) -> {
            InvoiceLineAdjustment change = changes.get(code);
            String description = change == null ? row.description() : change.description();
            long minutes = change == null ? row.minutes() : change.workedMinutes();
            BigDecimal rate = change == null ? row.rate() : change.hourlyRate();
            BigDecimal vatPercent = change == null ? row.vatPercent() : change.vatPercent();
            if (minutes == 0) return;
            BigDecimal net = rate.multiply(BigDecimal.valueOf(minutes))
                    .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
            BigDecimal vat = net.multiply(vatPercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal total = net.add(vat);
            lines.add(IssuedInvoice.Line.builder().customerCode(code).description(description).workedMinutes(minutes)
                    .hourlyRate(rate).vatPercent(vatPercent).netAmount(net).vatAmount(vat)
                    .totalAmount(total).build());
            BigDecimal[] sums = taxes.computeIfAbsent(vatPercent.stripTrailingZeros(), ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(net);
            sums[1] = sums[1].add(vat);
        });
        if (lines.isEmpty()) throw new IllegalArgumentException("Fakturan måste innehålla minst en rad med tid.");
        var vatTotals = taxes.entrySet().stream().map(entry -> IssuedInvoice.VatTotal.builder()
                .vatPercent(entry.getKey()).netAmount(entry.getValue()[0]).vatAmount(entry.getValue()[1]).build()).toList();
        BigDecimal netAmount = lines.stream().map(IssuedInvoice.Line::netAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vatAmount = lines.stream().map(IssuedInvoice.Line::vatAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return IssuedInvoice.builder().id(UUID.randomUUID()).invoiceNumber(number).periodMonth(draft.month())
                .invoiceDate(invoiceDate).dueDate(dueDate).issuedAt(Instant.now()).seller(seller).customer(customer)
                .lines(lines).vatTotals(vatTotals).netAmount(netAmount).vatAmount(vatAmount)
                .totalAmount(netAmount.add(vatAmount)).build();
    }
}

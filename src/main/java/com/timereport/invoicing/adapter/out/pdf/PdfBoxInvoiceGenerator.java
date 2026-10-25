package com.timereport.invoicing.adapter.out.pdf;

import com.timereport.invoicing.application.port.out.InvoicePdfGenerator;
import com.timereport.invoicing.domain.IssuedInvoice;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.awt.Color;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.time.YearMonth;

@ApplicationScoped
public class PdfBoxInvoiceGenerator implements InvoicePdfGenerator {
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float LEFT = 42;
    private static final float RIGHT = PAGE_WIDTH - 42;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("sv-SE"));
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("sv-SE"));
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font BRAND = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE);
    private static final Color PANEL = new Color(247, 250, 248);
    private static final Color BORDER = new Color(205, 218, 212);
    private static final Color HEADING = new Color(91, 151, 120);

    @Override
    public byte[] generate(IssuedInvoice invoice) {
        return generate(invoice, false);
    }

    @Override
    public byte[] generateDraft(IssuedInvoice invoice) {
        return generate(invoice, true);
    }

    private byte[] generate(IssuedInvoice invoice, boolean draft) {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            Page page = newPage(document, invoice, false, draft);
            page = drawLines(document, page, invoice, draft);
            if (page.y < 260) {
                page.stream.close();
                page = newPage(document, invoice, true, draft);
            }
            drawTotals(page, invoice);
            for (PDPage item : document.getPages()) drawFooter(document, item, invoice);
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF-fakturan kunde inte skapas.", e);
        }
    }

    private Page newPage(PDDocument document, IssuedInvoice invoice, boolean continuation, boolean draft) throws IOException {
        var pdfPage = new PDPage(PDRectangle.A4);
        document.addPage(pdfPage);
        var stream = new PDPageContentStream(document, pdfPage);
        var page = new Page(pdfPage, stream);
        if (continuation) {
            text(stream, LEFT, 790, BOLD, 13, (draft ? "Fakturautkast " : "Faktura ") + invoice.invoiceNumber() + " - fortsättning");
            page.y = 730;
            return page;
        }
        var seller = invoice.seller();
        stream.saveGraphicsState();
        stream.setNonStrokingColor(HEADING);
        text(stream, LEFT, 786, BRAND, 18, fit(seller.sellerName(), BRAND, 18, 270));
        String heading = draft ? "FAKTURA - UTKAST" : "FAKTURA";
        text(stream, RIGHT - width(BOLD, draft ? 17 : 22, heading), 786, BOLD, draft ? 17 : 22, heading);
        stream.restoreGraphicsState();
        float metaX = 355;
        roundedPanel(stream, 343, 604, RIGHT - 343, 168);
        roundedHeader(stream, 343, 744, RIGHT - 343, 24, "FAKTURAUPPGIFTER");
        float metaY = 733;
        meta(stream, metaX, metaY, draft ? "Preliminärt fakturanummer" : "Fakturanummer", String.valueOf(invoice.invoiceNumber()));
        meta(stream, metaX, metaY - 28, "Fakturadatum", DATE.format(invoice.invoiceDate()));
        meta(stream, metaX, metaY - 56, "Betalningsvillkor", invoice.customer().paymentTermsDays() + " dagar");
        meta(stream, metaX, metaY - 84, "Förfallodatum", DATE.format(invoice.dueDate()));
        meta(stream, metaX, metaY - 112, "Period", MONTH.format(YearMonth.parse(invoice.periodMonth()).atDay(1)));

        roundedPanel(stream, LEFT - 4, 604, 290, 168);
        roundedHeader(stream, LEFT - 4, 744, 290, 24, "FAKTURERAS TILL");
        var customer = invoice.customer();
        text(stream, LEFT, 734, BOLD, 11, customer.name());
        text(stream, LEFT, 718, REGULAR, 9, customer.addressLine());
        text(stream, LEFT, 704, REGULAR, 9, customer.postalCode() + " " + customer.city());
        text(stream, LEFT, 690, REGULAR, 9, customer.country());
        if (!customer.organizationNumber().isBlank())
            text(stream, LEFT, 676, REGULAR, 9, "Org.nr " + customer.organizationNumber());
        if (!customer.vatNumber().isBlank()) text(stream, LEFT, 662, REGULAR, 9, "Momsnr " + customer.vatNumber());

        float refsY = 646;
        if (!customer.customerNumber().isBlank())
            text(stream, LEFT, refsY, REGULAR, 9, "Kundnummer: " + customer.customerNumber());
        if (!customer.customerReference().isBlank())
            text(stream, LEFT, refsY - 14, REGULAR, 9, "Er referens: " + customer.customerReference());
        if (!customer.ourReference().isBlank())
            text(stream, LEFT, refsY - 28, REGULAR, 9, "Vår referens: " + customer.ourReference());

        text(stream, LEFT, 580, BOLD, 10, "Konsulttjänster - " + MONTH.format(YearMonth.parse(invoice.periodMonth()).atDay(1)));
        page.y = 532;
        return page;
    }

    private Page drawLines(PDDocument document, Page page, IssuedInvoice invoice, boolean draft) throws IOException {
        int index = 0;
        while (index < invoice.lines().size()) {
            float panelTop = page.y + 40;
            float bottom = page.y;
            int end = index;
            // Measure the rows on this page before painting their shared background.
            while (end < invoice.lines().size()) {
                var line = invoice.lines().get(end);
                float height = rowHeight(line);
                if (bottom - height < 112 && end > index) break;
                bottom -= height + 10;
                end++;
            }
            roundedPanel(page.stream, LEFT - 4, bottom + 9, RIGHT - LEFT + 8, panelTop - bottom - 9);
            tableHeader(page.stream, page.y + 22);
            for (; index < end; index++) {
                var line = invoice.lines().get(index);
                String label = line.customerCode() + " - " + line.description();
                var wrapped = wrap(label, REGULAR, 8, 220);
                float rowBottom = page.y - rowHeight(line);
                for (int i = 0; i < wrapped.size(); i++)
                    text(page.stream, LEFT, page.y - i * 11, REGULAR, 8, wrapped.get(i));
                text(page.stream, 300, page.y, REGULAR, 8, hours(line.workedMinutes()));
                textRight(page.stream, 397, page.y, REGULAR, 8, amount(line.hourlyRate(), invoice));
                textRight(page.stream, RIGHT, page.y, REGULAR, 8, amount(line.netAmount(), invoice));
                page.y = rowBottom - 10;
            }
            if (index < invoice.lines().size()) {
                page.stream.close();
                page = newPage(document, invoice, true, draft);
            }
        }
        return page;
    }

    private float rowHeight(IssuedInvoice.Line line) throws IOException {
        return Math.max(28, wrap(line.customerCode() + " - " + line.description(), REGULAR, 8, 220).size() * 11 + 10);
    }

    private void drawTotals(Page page, IssuedInvoice invoice) throws IOException {
        float y = page.y - 58;
        float labelX = 330;
        float panelBottom = y - 17 - (16 * invoice.vatTotals().size()) - 20 - 18;
        roundedPanel(page.stream, labelX - 12, panelBottom, RIGHT - labelX + 12, y + 42 - panelBottom);
        roundedHeader(page.stream, labelX - 12, y + 18, RIGHT - labelX + 12, 24, "SUMMERING");
        textRight(page.stream, RIGHT, y, BOLD, 9, amount(invoice.netAmount(), invoice));
        text(page.stream, labelX, y, REGULAR, 9, "Summa exkl. moms");
        y -= 17;
        for (var tax : invoice.vatTotals()) {
            text(page.stream, labelX, y, REGULAR, 9, "Moms " + percent(tax.vatPercent()) + "%");
            textRight(page.stream, RIGHT, y, REGULAR, 9, amount(tax.vatAmount(), invoice));
            y -= 16;
        }
        text(page.stream, labelX, y, REGULAR, 9, "Öresavrundning");
        textRight(page.stream, RIGHT, y, REGULAR, 9, amount(BigDecimal.ZERO, invoice));
        y -= 20;
        stroke(page.stream, labelX, y + 13, RIGHT, y + 13);
        text(page.stream, labelX, y, BOLD, 12, "ATT BETALA");
        textRight(page.stream, RIGHT, y, BOLD, 12, amount(invoice.totalAmount(), invoice));
        page.stream.close();
    }

    private void drawFooter(PDDocument document, PDPage page, IssuedInvoice invoice) throws IOException {
        try (var stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true)) {
            roundedPanel(stream, LEFT - 4, 20, RIGHT - LEFT + 8, 62);
            var seller = invoice.seller();
            String first = seller.sellerName() + " | " + seller.addressLine() + " | " + seller.postalCode() + " " + seller.city() + " | " + seller.country();
            text(stream, LEFT, 66, REGULAR, 7, fit(first, REGULAR, 7, RIGHT - LEFT));
            String payment = (seller.bankgiro().isBlank() ? "" : "Bankgiro: " + seller.bankgiro())
                    + (seller.email().isBlank() ? "" : (seller.bankgiro().isBlank() ? "" : " | ") + "E-post: " + seller.email());
            if (!payment.isBlank()) text(stream, LEFT, 52, REGULAR, 7, fit(payment, REGULAR, 7, RIGHT - LEFT));
            String second = "Org.nr " + seller.organizationNumber()
                    + (seller.vatNumber().isBlank() ? "" : " | Momsnr " + seller.vatNumber())
                    + (seller.fTaxRegistered() ? " | Godkänd för F-skatt" : "");
            text(stream, LEFT, 38, REGULAR, 7, fit(second, REGULAR, 7, RIGHT - LEFT));
        }
    }

    private void tableHeader(PDPageContentStream stream, float y) throws IOException {
        roundedHeader(stream, LEFT - 4, y - 9, RIGHT - LEFT + 8, 27, "");
        headerText(stream, LEFT, y, "TJÄNST / KUNDKOD");
        headerTextRight(stream, 322, y, "TIMMAR");
        headerTextRight(stream, 397, y, "TIMPRIS");
        headerTextRight(stream, RIGHT, y, "BELOPP");
    }

    private void meta(PDPageContentStream stream, float x, float y, String label, String value) throws IOException {
        text(stream, x, y, REGULAR, 8, label.toUpperCase(Locale.forLanguageTag("sv-SE")));
        text(stream, x, y - 12, BOLD, 10, value);
    }

    private static String hours(long minutes) {
        return number(BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60)));
    }

    private static String percent(BigDecimal value) {
        return number(value.stripTrailingZeros());
    }

    private static String amount(BigDecimal value, IssuedInvoice invoice) {
        return number(value) + " " + invoice.seller().currencyCode();
    }

    private static String number(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("sv-SE"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }

    private static java.util.List<String> wrap(String value, PDType1Font font, float size, float maxWidth) throws IOException {
        var result = new java.util.ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : value.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(font, size, candidate) > maxWidth && !line.isEmpty()) {
                result.add(line.toString());
                line = new StringBuilder(word);
            } else line = new StringBuilder(candidate);
        }
        if (!line.isEmpty()) result.add(line.toString());
        return result;
    }

    private static String fit(String value, PDType1Font font, float size, float maxWidth) throws IOException {
        String result = value;
        while (!result.isEmpty() && width(font, size, result) > maxWidth)
            result = result.substring(0, result.length() - 1);
        return result;
    }

    private static float width(PDType1Font font, float size, String value) throws IOException {
        return font.getStringWidth(value) / 1000 * size;
    }

    private static void text(PDPageContentStream stream, float x, float y, PDType1Font font, float size, String value) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }

    private static void textRight(PDPageContentStream stream, float right, float y, PDType1Font font, float size, String value) throws IOException {
        text(stream, right - width(font, size, value), y, font, size, value);
    }

    private static void stroke(PDPageContentStream stream, float x1, float y1, float x2, float y2) throws IOException {
        stream.setStrokingColor(BORDER);
        stream.setLineWidth(0.6f);
        stream.moveTo(x1, y1);
        stream.lineTo(x2, y2);
        stream.stroke();
    }

    private static void roundedPanel(PDPageContentStream stream, float x, float y, float width, float height) throws IOException {
        float radius = 8;
        float curve = 0.55228475f * radius;
        stream.saveGraphicsState();
        stream.setNonStrokingColor(PANEL);
        stream.setStrokingColor(BORDER);
        stream.setLineWidth(0.8f);
        stream.moveTo(x + radius, y);
        stream.lineTo(x + width - radius, y);
        stream.curveTo(x + width - radius + curve, y, x + width, y + radius - curve, x + width, y + radius);
        stream.lineTo(x + width, y + height - radius);
        stream.curveTo(x + width, y + height - radius + curve, x + width - radius + curve, y + height, x + width - radius, y + height);
        stream.lineTo(x + radius, y + height);
        stream.curveTo(x + radius - curve, y + height, x, y + height - radius + curve, x, y + height - radius);
        stream.lineTo(x, y + radius);
        stream.curveTo(x, y + radius - curve, x + radius - curve, y, x + radius, y);
        stream.closeAndFillAndStroke();
        stream.restoreGraphicsState();
    }

    private static void roundedHeader(PDPageContentStream stream, float x, float y, float width, float height,
                                      String title) throws IOException {
        float radius = 7;
        float curve = 0.55228475f * radius;
        stream.saveGraphicsState();
        stream.setNonStrokingColor(HEADING);
        stream.setStrokingColor(HEADING);
        stream.moveTo(x + radius, y);
        stream.lineTo(x + width - radius, y);
        stream.curveTo(x + width - radius + curve, y, x + width, y + radius - curve, x + width, y + radius);
        stream.lineTo(x + width, y + height - radius);
        stream.curveTo(x + width, y + height - radius + curve, x + width - radius + curve, y + height, x + width - radius, y + height);
        stream.lineTo(x + radius, y + height);
        stream.curveTo(x + radius - curve, y + height, x, y + height - radius + curve, x, y + height - radius);
        stream.lineTo(x, y + radius);
        stream.curveTo(x, y + radius - curve, x + radius - curve, y, x + radius, y);
        stream.closeAndFillAndStroke();
        if (!title.isBlank()) {
            stream.saveGraphicsState();
            stream.setNonStrokingColor(Color.WHITE);
            text(stream, x + 10, y + (height - 8) / 2 + 2, BOLD, 8, fit(title, BOLD, 8, width - 20));
            stream.restoreGraphicsState();
        }
        stream.restoreGraphicsState();
    }

    private static void headerText(PDPageContentStream stream, float x, float y, String value) throws IOException {
        stream.saveGraphicsState();
        stream.setNonStrokingColor(Color.WHITE);
        text(stream, x, y, BOLD, 8, value);
        stream.restoreGraphicsState();
    }

    private static void headerTextRight(PDPageContentStream stream, float right, float y, String value) throws IOException {
        stream.saveGraphicsState();
        stream.setNonStrokingColor(Color.WHITE);
        textRight(stream, right, y, BOLD, 8, value);
        stream.restoreGraphicsState();
    }

    private static final class Page {
        private final PDPage page;
        private final PDPageContentStream stream;
        private float y;

        private Page(PDPage page, PDPageContentStream stream) {
            this.page = page;
            this.stream = stream;
        }
    }
}

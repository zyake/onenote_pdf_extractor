package com.extractor.pdf;

import com.extractor.client.GraphClientWrapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Downloads OneNote page content from the Microsoft Graph API.
 * If the page contains an embedded PDF attachment, downloads it directly.
 * Otherwise, converts the HTML content to PDF.
 */
public class PdfDownloader {

    private static final String CONTENT_URL_TEMPLATE =
            "https://graph.microsoft.com/v1.0/me/onenote/pages/%s/content";

    private final GraphClientWrapper client;

    public PdfDownloader(GraphClientWrapper client) {
        this.client = client;
    }

    /**
     * Download a page's content as PDF bytes.
     * First checks for embedded PDF attachments in the HTML.
     * If found, downloads the PDF resource directly.
     * Otherwise, converts the HTML to PDF.
     */
    public byte[] downloadPageAsPdf(String pageId) throws IOException {
        var url = String.format(CONTENT_URL_TEMPLATE, pageId);
        var headers = Map.of("Accept", "text/html");
        var htmlBytes = client.getBytes(url, headers);
        var html = new String(htmlBytes, StandardCharsets.UTF_8);

        // Try to extract embedded PDF resource URL
        var pdfResourceUrl = extractEmbeddedPdfUrl(html);
        if (pdfResourceUrl != null) {
            System.out.println("    Found embedded PDF, downloading resource directly...");
            return client.getBytes(pdfResourceUrl, Map.of());
        }

        // No embedded PDF — convert HTML to PDF
        System.out.println("    No embedded PDF found, converting HTML to PDF...");
        return convertHtmlToPdf(html);
    }

    /**
     * Extracts the URL of an embedded PDF resource from OneNote HTML.
     * Checks for &lt;object&gt; tags with type="application/pdf" or data-attachment ending in .pdf.
     */
    String extractEmbeddedPdfUrl(String html) {
        var doc = Jsoup.parse(html);

        return Stream.of(
                        doc.select("object[type=application/pdf]"),
                        doc.select("object[data-attachment$=.pdf]")
                )
                .flatMap(elements -> elements.stream())
                .map(el -> el.attr("data"))
                .filter(data -> data != null && !data.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * Converts HTML content to PDF bytes using OpenHTMLToPDF.
     */
    private byte[] convertHtmlToPdf(String html) throws IOException {
        var jsoupDoc = Jsoup.parse(html);
        jsoupDoc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .charset(StandardCharsets.UTF_8);

        var w3cDom = new W3CDom();
        var w3cDoc = w3cDom.fromJsoup(jsoupDoc);

        try (var os = new ByteArrayOutputStream()) {
            var builder = new PdfRendererBuilder();
            builder.withW3cDocument(w3cDoc, "/");
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (Exception e) {
            throw new IOException("Failed to convert HTML to PDF: " + e.getMessage(), e);
        }
    }
}

package com.extractor.pdf;

import com.extractor.client.GraphClientWrapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
     *
     * @param pageId the OneNote page ID
     * @return PDF content as a byte array
     * @throws IOException if the download or conversion fails
     */
    public byte[] downloadPageAsPdf(String pageId) throws IOException {
        String url = String.format(CONTENT_URL_TEMPLATE, pageId);
        Map<String, String> headers = Map.of("Accept", "text/html");
        byte[] htmlBytes = client.getBytes(url, headers);
        String html = new String(htmlBytes, StandardCharsets.UTF_8);

        // Try to extract embedded PDF resource URL
        String pdfResourceUrl = extractEmbeddedPdfUrl(html);
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
     * OneNote embeds files as &lt;object&gt; tags with data-attachment and type attributes.
     * Example: &lt;object data="https://..." data-attachment="paper.pdf" type="application/pdf"/&gt;
     *
     * @param html the page HTML content
     * @return the PDF resource URL, or null if no embedded PDF found
     */
    private String extractEmbeddedPdfUrl(String html) {
        Document doc = Jsoup.parse(html);

        // Look for <object> tags with type="application/pdf"
        Elements pdfObjects = doc.select("object[type=application/pdf]");
        if (!pdfObjects.isEmpty()) {
            Element pdfObj = pdfObjects.first();
            String dataUrl = pdfObj.attr("data");
            if (dataUrl != null && !dataUrl.isBlank()) {
                return dataUrl;
            }
        }

        // Also check for <object> tags with data-attachment ending in .pdf
        Elements attachments = doc.select("object[data-attachment$=.pdf]");
        if (!attachments.isEmpty()) {
            Element attachment = attachments.first();
            String dataUrl = attachment.attr("data");
            if (dataUrl != null && !dataUrl.isBlank()) {
                return dataUrl;
            }
        }

        return null;
    }

    /**
     * Converts HTML content to PDF bytes using OpenHTMLToPDF.
     */
    private byte[] convertHtmlToPdf(String html) throws IOException {
        Document jsoupDoc = Jsoup.parse(html);
        jsoupDoc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .charset(StandardCharsets.UTF_8);

        W3CDom w3cDom = new W3CDom();
        org.w3c.dom.Document w3cDoc = w3cDom.fromJsoup(jsoupDoc);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withW3cDocument(w3cDoc, "/");
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (Exception e) {
            throw new IOException("Failed to convert HTML to PDF: " + e.getMessage(), e);
        }
    }
}

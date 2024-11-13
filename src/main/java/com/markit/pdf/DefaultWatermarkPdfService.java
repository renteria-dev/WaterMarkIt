package com.markit.pdf;

import com.markit.api.TextWatermarkAttributes;
import com.markit.api.WatermarkMethod;
import com.markit.exceptions.AsyncWatermarkPdfException;
import com.markit.exceptions.ExecutorNotFoundException;
import com.markit.exceptions.WatermarkPdfServiceNotFoundException;
import com.markit.pdf.draw.PdfWatermarker;
import com.markit.pdf.overlay.OverlayPdfWatermarker;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * @author Oleg Cheban
 * @since 1.0
 */
public class DefaultWatermarkPdfService implements WatermarkPdfService {
    private static final Log logger = LogFactory.getLog(DefaultWatermarkPdfService.class);
    private final Optional<PdfWatermarker> drawService;
    private final Optional<OverlayPdfWatermarker> overlayService;
    private final Optional<Executor> executorService;

    public DefaultWatermarkPdfService(PdfWatermarker pdfWatermarker, OverlayPdfWatermarker overlayService, Executor es) {
        this.drawService = Optional.ofNullable(pdfWatermarker);
        this.overlayService = Optional.ofNullable(overlayService);
        this.executorService = Optional.ofNullable(es);
    }

    public DefaultWatermarkPdfService(PdfWatermarker pdfWatermarker, OverlayPdfWatermarker overlayService) {
        this.drawService = Optional.ofNullable(pdfWatermarker);
        this.overlayService = Optional.ofNullable(overlayService);
        this.executorService = Optional.empty();
    }

    @Override
    public byte[] watermark(byte[] sourceImageBytes, List<TextWatermarkAttributes> attrs) throws IOException {
        try(PDDocument document = PDDocument.load(sourceImageBytes)) {
            return watermark(document, attrs);
        }
    }

    @Override
    public byte[] watermark(File file, List<TextWatermarkAttributes> attrs) throws IOException {
        try(PDDocument document = PDDocument.load(file)) {
            return watermark(document, attrs);
        }
    }

    @Override
    public byte[] watermark(PDDocument document, List<TextWatermarkAttributes> attrs) throws IOException {
        if (drawService.isEmpty() || overlayService.isEmpty()){
            logger.error("Incorrect configuration. An empty service");
            throw new WatermarkPdfServiceNotFoundException();
        }
        applyWatermark(document, attrs, WatermarkMethod.DRAW, this::draw);
        applyWatermark(document, attrs, WatermarkMethod.OVERLAY, this::overlay);
        removeSecurity(document);
        return convertPDDocumentToByteArray(document);
    }

    private void applyWatermark(PDDocument document, List<TextWatermarkAttributes> attrs,
                                WatermarkMethod method, PdfWatermarkHandler action) throws IOException {
        var filteredAttrs = attrs.stream()
                .filter(attr -> attr.getMethod().equals(method))
                .collect(Collectors.toList());
        if (!filteredAttrs.isEmpty()) {
            action.apply(document, filteredAttrs);
        }
    }

    private void overlay(PDDocument document, List<TextWatermarkAttributes> attrs) throws IOException {
        int numberOfPages = document.getNumberOfPages();
        for (int pageIndex = 0; pageIndex < numberOfPages; pageIndex++) {
            overlayService.get().watermark(document, pageIndex, attrs);
        }
    }

    private void draw(PDDocument document, List<TextWatermarkAttributes> attrs) throws IOException {
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        int numberOfPages = document.getNumberOfPages();
        if (executorService.isEmpty()) {
            sync(document, pdfRenderer, numberOfPages, attrs);
        } else {
            async(document, pdfRenderer, numberOfPages, attrs);
        }
    }

    private void async(PDDocument document, PDFRenderer pdfRenderer, int numberOfPages, List<TextWatermarkAttributes> attrs){
        if (executorService.isEmpty()){
            logger.error("An empty executor");
            throw new ExecutorNotFoundException();
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < numberOfPages; pageIndex++) {
            int finalPageIndex = pageIndex;
            futures.add(
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                drawService.get().watermark(document, pdfRenderer, finalPageIndex, attrs);
                            } catch (IOException e) {
                                logger.error(String.format(
                                        "An error occurred during watermarking on page number %d", finalPageIndex), e);
                                throw new AsyncWatermarkPdfException(e);
                            }
                        },
                        executorService.get()
                )
            );
        }
        var allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
        allOf.join();
    }

    private void sync(PDDocument document, PDFRenderer pdfRenderer, int numberOfPages, List<TextWatermarkAttributes> attrs) throws IOException {
        for (int pageIndex = 0; pageIndex < numberOfPages; pageIndex++) {
            drawService.get().watermark(document, pdfRenderer, pageIndex, attrs);
        }
    }

    private byte[] convertPDDocumentToByteArray(PDDocument document) throws IOException {
        try (var baos = new ByteArrayOutputStream()) {
            document.save(baos);
            return baos.toByteArray();
        }
    }

    private void removeSecurity(PDDocument document) {
        if (document.isEncrypted()){
            document.setAllSecurityToBeRemoved(true);
        }
    }
}

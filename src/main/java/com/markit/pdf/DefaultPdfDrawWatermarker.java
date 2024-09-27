package com.markit.pdf;

import com.markit.image.ImageWatermarker;
import com.markit.api.FileType;
import com.markit.api.WatermarkPosition;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * @author Oleg Cheban
 * @since 1.0
 */
public class DefaultPdfDrawWatermarker implements PdfWatermarker {
    private final ImageWatermarker imageWatermarker;

    public DefaultPdfDrawWatermarker(ImageWatermarker imageWatermarker) {
        this.imageWatermarker = imageWatermarker;
    }

    @Override
    public void watermark(
            PDDocument document,
            PDFRenderer pdfRenderer,
            int pageIndex,
            float dpi,
            String text,
            int textSize,
            Color color,
            boolean trademark,
            WatermarkPosition position) throws IOException {
        var page = document.getPage(pageIndex);
        var image = pdfRenderer.renderImageWithDPI(pageIndex, dpi);
        var baos = new ByteArrayOutputStream();
        ImageIO.write(image, FileType.JPEG.name(), baos);
        var watermarkedImageBytes = imageWatermarker.watermark(baos.toByteArray(), FileType.JPEG, text, textSize, color, trademark, position);
        var pdImage = PDImageXObject.createFromByteArray(document, watermarkedImageBytes, "watermarked");
        replaceImageInPDF(
                document,
                pdImage,
                page,
                page.getCropBox().getLowerLeftX(), page.getCropBox().getLowerLeftY(),
                page.getCropBox().getWidth(), page.getCropBox().getHeight()
        );
    }

    private void replaceImageInPDF(
            PDDocument document,
            PDImageXObject watermarkedImage,
            PDPage page,
            float x,
            float y,
            float width,
            float height) throws IOException {
        try (var contentStream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, false)) {
            contentStream.drawImage(watermarkedImage, x, y, width, height);
        }
    }
}

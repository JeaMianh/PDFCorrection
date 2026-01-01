package com.example.pdfcorrection.strategy;

import com.example.pdfcorrection.model.TocItem;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface TocExtractionStrategy {
    /**
     * Extract TOC items from a PDF file.
     * Some strategies might need the MultipartFile, others might work with PdfDocument (iText) or PDDocument (PDFBox).
     * To keep it generic, we can pass MultipartFile, or we can have specific methods.
     * 
     * However, looking at the original code:
     * - StrategyExplicitToc uses iText PdfDocument and a list of pages.
     * - StrategyLayoutStructure uses iText PdfDocument.
     * - StrategyPdfBox uses MultipartFile (loads PDDocument internally).
     * - StrategyOcr uses MultipartFile (converts to images).
     * 
     * It might be better to have a context object or just pass MultipartFile and let the strategy handle opening it,
     * OR pass both if available.
     * 
     * For now, let's define a generic extract method that takes MultipartFile.
     * But StrategyExplicitToc needs the page numbers found by TocDiscoveryService.
     */
    List<TocItem> extract(MultipartFile file);
}

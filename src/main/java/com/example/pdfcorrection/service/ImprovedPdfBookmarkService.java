package com.example.pdfcorrection.service;

import com.example.pdfcorrection.model.TocItem;
import com.example.pdfcorrection.ocr.OcrEngine;
import com.example.pdfcorrection.ocr.impl.OpenAiCompatibleEngine;
import com.example.pdfcorrection.ocr.impl.TesseractEngine;
import com.example.pdfcorrection.strategy.impl.ExplicitTocStrategy;
import com.example.pdfcorrection.strategy.impl.LayoutStructureStrategy;
import com.example.pdfcorrection.strategy.impl.OcrStrategy;
import com.example.pdfcorrection.strategy.impl.PdfBoxStrategy;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Improved PDF Bookmark Service (Refactored)
 * Uses a component-based architecture with Strategy Pattern.
 */
@Service
public class ImprovedPdfBookmarkService {

    // @Value("${pdf.ocr.provider:local}")
    // private String ocrProvider;

    @Value("${pdf.ocr.api.api-key:}")
    private String apiKey;

    @Value("${pdf.ocr.api.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String apiBaseUrl;

    @Value("${pdf.ocr.api.model:qwen-vl-ocr-2025-11-20}")
    private String apiModel;

    @Value("${pdf.ocr.api.extraction-model:}")
    private String extractionApiModel;

    @Value("${pdf.ocr.api.extraction-api-key:}")
    private String extractionApiKey;

    @Value("${pdf.ocr.api.extraction-base-url:}")
    private String extractionApiBaseUrl;

    @Value("${pdf.ocr.api.enable-high-precision:true}")
    private boolean enableHighPrecision;

    @Value("${pdf.ocr.prompt:请识别图片中的目录内容。请严格按照图片中的顺序，输出一个扁平的 JSON 数组。每个元素包含 'title' (完整的章节标题，必须包含章节编号，如'1.1 绪论'，或'第一章 标题')、'page' (页码) 和 'level' (层级，整数，1表示一级标题，2表示二级标题，以此类推) 三个字段。\\n**关键要求**：\\n1. **完整性**：绝对不要遗漏章节编号！例如图片显示 '1.1.1 数据结构'，title 必须是 '1.1.1 数据结构'。\\n2. **层级判断** (综合判断)：\\n   - **语义编号** (优先)：'第一部/编' > '第一章' > '第一节'。例如：如果有'第一部'，则'第一部'是Level 1，'第一章'是Level 2。如果没有'部'，则'第一章'是Level 1。\\n   - **缩进**：在编号不明确时，缩进越深 level 越大。\\n   - **字体**：字号大或加粗的通常层级更高。\\n3. **页码**：如果某行没有页码，page 字段留空字符串。\\n不要输出 Markdown 标记，只输出 JSON。}")
    private String ocrPrompt;

    private final RestTemplate restTemplate = new RestTemplate();

    private final TocDiscoveryService tocDiscoveryService;
    private final ExplicitTocStrategy explicitTocStrategy;
    private final LayoutStructureStrategy layoutStructureStrategy;
    private final PdfBoxStrategy pdfBoxStrategy;
    private final OcrStrategy ocrStrategy;
    private final PdfBookmarkWriterService pdfBookmarkWriterService;
    private final ProgressService progressService;

    public ImprovedPdfBookmarkService(TocDiscoveryService tocDiscoveryService,
                                      ExplicitTocStrategy explicitTocStrategy,
                                      LayoutStructureStrategy layoutStructureStrategy,
                                      PdfBoxStrategy pdfBoxStrategy,
                                      OcrStrategy ocrStrategy,
                                      PdfBookmarkWriterService pdfBookmarkWriterService,
                                      ProgressService progressService) {
        this.tocDiscoveryService = tocDiscoveryService;
        this.explicitTocStrategy = explicitTocStrategy;
        this.layoutStructureStrategy = layoutStructureStrategy;
        this.pdfBoxStrategy = pdfBoxStrategy;
        this.ocrStrategy = ocrStrategy;
        this.pdfBookmarkWriterService = pdfBookmarkWriterService;
        this.progressService = progressService;
    }

    /* ======================= Public API ======================= */

    public byte[] processAndAddBookmarks(MultipartFile file) throws IOException {
        return processAndAddBookmarks(file, null);
    }

    public byte[] processAndAddBookmarks(MultipartFile file, List<TocItem> providedToc) throws IOException {
        List<TocItem> tocItems;
        if (providedToc != null && !providedToc.isEmpty()) {
            tocItems = providedToc;
        } else {
            tocItems = extractTocItems(file);
        }

        // If no bookmarks extracted, return original file
        if (tocItems.isEmpty()) {
            return new ByteArrayResource(file.getBytes()).getByteArray();
        }
        return pdfBookmarkWriterService.addBookmarksToOriginalPdf(file.getBytes(), tocItems);
    }

    public List<TocItem> extractTocItems(MultipartFile file) throws IOException {
        List<TocItem> items = new ArrayList<>();
        try (InputStream is = file.getInputStream();
             PdfReader reader = new PdfReader(is);
             PdfDocument pdf = new PdfDocument(reader)) {
            // 1. Pre-scan: Find potential TOC pages
            progressService.sendProgress("正在扫描PDF结构，查找目录页...");
            List<Integer> tocCandidatePages = tocDiscoveryService.findTocPages(pdf);

            // 2. Strategy A: Explicit TOC Page Parsing (High Precision)
            if (!tocCandidatePages.isEmpty()) {
                progressService.sendProgress("发现潜在目录页，尝试解析...");
                items = explicitTocStrategy.extract(pdf, tocCandidatePages);
            }

            // 3. Strategy B: Layout Structure Analysis (WPS Fallback)
            if (items.isEmpty()) {
                progressService.sendProgress("尝试使用布局结构分析提取目录...");
                items = layoutStructureStrategy.extract(pdf);
            }
        } catch (Exception e) {
            System.err.println("iText extraction failed: " + e.getMessage());
        }

        // 4. Strategy C: PDFBox Fallback (Solves iText font crashes)
        if (items == null || items.isEmpty()) {
            try {
                progressService.sendProgress("尝试使用PDFBox提取文本...");
                items = pdfBoxStrategy.extract(file);
            } catch (Exception e) {
                System.err.println("PDFBox extraction failed: " + e.getMessage());
            }
        }

        // 5. Strategy D: OCR Fallback (For Scanned PDFs)
        if (items == null || items.isEmpty()) {
            try {
                progressService.sendProgress("未找到文本目录，准备进行OCR识别...");
                OcrEngine engine;
                OcrEngine discoveryEngine;
                
                // Check if API is configured (Base URL and Key must be present)
                if (apiBaseUrl != null && !apiBaseUrl.isEmpty() && apiKey != null && !apiKey.isEmpty()) {
                    System.out.println("Configuring OCR Engines...");
                    
                    // 1. Discovery Engine (Base Model)
                    System.out.println("  Discovery Engine Model: " + apiModel);
                    discoveryEngine = new OpenAiCompatibleEngine(apiKey, apiBaseUrl, apiModel, ocrPrompt, restTemplate);
                    
                    // 2. Extraction Engine (High Precision)
                    boolean isPlaceholder = extractionApiModel != null && extractionApiModel.contains("YOUR_MODEL");
                    
                    if (enableHighPrecision && extractionApiModel != null && !extractionApiModel.trim().isEmpty() && !isPlaceholder) {
                        System.out.println("  Extraction Engine Model: " + extractionApiModel);
                        String effectiveExtractionKey = (extractionApiKey != null && !extractionApiKey.trim().isEmpty() && !extractionApiKey.contains("YOUR_API_KEY")) ? extractionApiKey : apiKey;
                        String effectiveExtractionUrl = (extractionApiBaseUrl != null && !extractionApiBaseUrl.trim().isEmpty() && !extractionApiBaseUrl.contains("YOUR_BASE_URL")) ? extractionApiBaseUrl : apiBaseUrl;
                        
                        engine = new OpenAiCompatibleEngine(effectiveExtractionKey, effectiveExtractionUrl, extractionApiModel, ocrPrompt, restTemplate);
                    } else {
                        if (!enableHighPrecision) {
                            System.out.println("  High Precision Extraction Disabled. Using Discovery Model.");
                        } else if (isPlaceholder) {
                            System.out.println("  Extraction Model is configured with placeholder values. Fallback to Discovery Model: " + apiModel);
                        } else {
                            System.out.println("  Extraction Engine Model not configured. Fallback to Discovery Model: " + apiModel);
                        }
                        engine = discoveryEngine;
                    }
                } else {
                    System.out.println("Using Local Tesseract OCR Strategy...");
                    engine = new TesseractEngine();
                    discoveryEngine = engine;
                }
                items = ocrStrategy.extract(file, engine, discoveryEngine, ocrPrompt);
            } catch (Exception e) {
                System.err.println("OCR extraction failed: " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (items != null && !items.isEmpty()) {
            logTocStructure(items);
        }

        return items != null ? items : new ArrayList<>();
    }


    private void logTocStructure(List<TocItem> items) {
        System.out.println("=== Final TOC Structure (After Correction) ===");
        for (TocItem item : items) {
            StringBuilder indent = new StringBuilder();
            for (int k = 1; k < item.getLevel(); k++) indent.append("  ");
            System.out.printf("%s- %s (Page: %d, Level: %d)%n", indent, item.getTitle(), item.getPage(), item.getLevel());
        }
        System.out.println("==============================================");
    }
}

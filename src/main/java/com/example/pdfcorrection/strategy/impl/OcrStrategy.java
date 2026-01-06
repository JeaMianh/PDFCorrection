package com.example.pdfcorrection.strategy.impl;

import com.example.pdfcorrection.model.RawToc;
import com.example.pdfcorrection.model.TocItem;
import com.example.pdfcorrection.ocr.OcrEngine;
import com.example.pdfcorrection.ocr.impl.TesseractEngine;
import com.example.pdfcorrection.service.PageAlignmentService;
import com.example.pdfcorrection.util.TocTextParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OcrStrategy {

    @Value("${pdf.ocr.discovery-mode:local}")
    private String discoveryMode;

    @Value("${pdf.ocr.batch-size:3}")
    private int batchSize;

    private final PageAlignmentService pageAlignmentService;
    private final com.example.pdfcorrection.service.ProgressService progressService;

    public OcrStrategy(PageAlignmentService pageAlignmentService, com.example.pdfcorrection.service.ProgressService progressService) {
        this.pageAlignmentService = pageAlignmentService;
        this.progressService = progressService;
    }

    public List<TocItem> extract(MultipartFile file, OcrEngine ocrEngine, OcrEngine providedDiscoveryEngine, String ocrPrompt) throws IOException {
        List<TocItem> items = new ArrayList<>();
        
        // Determine Discovery Engine
        OcrEngine discoveryEngine;
        if ("api".equalsIgnoreCase(discoveryMode) || "llm".equalsIgnoreCase(discoveryMode)) {
            discoveryEngine = providedDiscoveryEngine;
            System.out.println("Using API Engine for Discovery Scan (Mode: " + discoveryMode + ").");
        } else {
            discoveryEngine = new TesseractEngine();
            System.out.println("Using Local Tesseract Engine for Discovery Scan.");
        }

        try (InputStream is = file.getInputStream();
             PDDocument doc = PDDocument.load(is)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int maxScan = Math.min(20, doc.getNumberOfPages());

            List<String> tocLines = new ArrayList<>();
            int tocStartPage = -1;
            List<RawToc> raws = new ArrayList<>();

            // 1. Find TOC pages (OCR)
            long discoveryStart = System.currentTimeMillis();
            progressService.sendProgress("正在查找目录...");
            for (int i = 0; i < maxScan; i++) { // PDFBox page index starts at 0
                System.out.println(">>> Scanning Page " + (i + 1) + " for TOC...");
                try {
                    // Phase 1: Quick Scan (150 DPI)
                    long pageScanStart = System.currentTimeMillis();
                    BufferedImage image = renderer.renderImageWithDPI(i, 150);
                    
                    boolean isTocPage = false;
                    
                    if ("llm".equalsIgnoreCase(discoveryMode)) {
                        // LLM Mode: Ask the model directly
                        progressService.sendProgress("正在分析第 " + (i + 1) + " 页是否为目录页...");
                        String prompt = "这是一本书的某一页。请判断这页是否是目录页（Table of Contents）的开始或一部分？\n" +
                                "如果是，请回答“YES”；如果不是，请回答“NO”。\n" +
                                "只回答 YES 或 NO，不要解释。";
                        String response = discoveryEngine.doOCR(image, prompt).trim().toUpperCase();
                        long pageScanDuration = System.currentTimeMillis() - pageScanStart;
                        
                        // System.out.println("Discovery Scan Page " + i + " (LLM): " + pageScanDuration + "ms, Response=" + response);
                        
                        if (response.contains("YES") || response.contains("是")) {
                            isTocPage = true;
                        }
                    } else {
                        // Text Mode (Local or API): Extract text and use regex
                        // Force plain text prompt for detection
                        String text = discoveryEngine.doOCR(image, "请仅输出图像中的文本内容。");
                        long pageScanDuration = System.currentTimeMillis() - pageScanStart;

                        String cleanText = text.replaceAll("\\s+", "");
                        int tocLikeCount = TocTextParser.countTocLikeLines(text);

                        // System.out.println("Discovery Scan Page " + i + " (" + discoveryMode + "): " + pageScanDuration + "ms, length=" + text.length() + ", tocLines=" + tocLikeCount + ", hasKeyword=" + (cleanText.contains("目录") || cleanText.toLowerCase().contains("contents")));

                        isTocPage = cleanText.contains("目录") || cleanText.toLowerCase().contains("contents");
                        if (!isTocPage) {
                            int chapterPatternCount = TocTextParser.countChapterPatterns(text);
                            if (tocLikeCount >= 5 && chapterPatternCount >= 2) {
                                isTocPage = true;
                                // System.out.println("   -> Detected as TOC by density (Lines=" + tocLikeCount + ", Patterns=" + chapterPatternCount + ")");
                            }
                        }
                    }

                    if (isTocPage) {
                        System.out.println(">>> Found TOC Start at Page " + i);
                        tocStartPage = i;

                        // Collect all TOC images
                        List<BufferedImage> tocImages = new ArrayList<>();
                        tocImages.add(renderer.renderImageWithDPI(i, 300));

                        // Try to read subsequent pages
                        for (int j = i + 1; j < maxScan; j++) {
                            BufferedImage nextImageLow = renderer.renderImageWithDPI(j, 150);
                            
                            boolean isContinuation = false;
                            if ("llm".equalsIgnoreCase(discoveryMode)) {
                                String prompt = "这是目录的后续页吗？请判断这页是否包含目录条目。\n" +
                                        "如果是，请回答“YES”；如果不是，请回答“NO”。\n" +
                                        "只回答 YES 或 NO。";
                                String response = discoveryEngine.doOCR(nextImageLow, prompt).trim().toUpperCase();
                                // System.out.println("   > Checking continuation Page " + j + " (LLM): " + response);
                                if (response.contains("YES") || response.contains("是")) {
                                    isContinuation = true;
                                }
                            } else {
                                String nextTextLow = discoveryEngine.doOCR(nextImageLow, "请仅输出图像中的文本内容。");
                                if (TocTextParser.countTocLikeLines(nextTextLow) > 3) {
                                    isContinuation = true;
                                }
                            }

                            if (isContinuation) {
                                System.out.println(">>> Found TOC Continuation at Page " + j);
                                tocImages.add(renderer.renderImageWithDPI(j, 300));
                            } else {
                                break;
                            }
                        }


                        // Batch OCR
                        System.out.println(">>> Batch OCR for " + tocImages.size() + " pages (Batch Size: " + batchSize + ")...");
                        progressService.sendProgress("发现 " + tocImages.size() + " 页目录，开始提取内容...");
                        long batchStart = System.currentTimeMillis();

                        List<RawToc> allBatchRaws = new ArrayList<>();
                        StringBuilder allTextFallback = new StringBuilder();

                        for (int k = 0; k < tocImages.size(); k += batchSize) {
                            int end = Math.min(k + batchSize, tocImages.size());
                            List<BufferedImage> batch = tocImages.subList(k, end);
                            System.out.println(">>> Processing Batch " + (k / batchSize + 1) + " (Pages " + k + " to " + (end - 1) + ")...");
                            progressService.sendProgress("正在提取第 " + (k + 1) + " 到 " + end + " 页目录内容...");
                            long batchItemStart = System.currentTimeMillis();

                            int maxRetries = 3;
                            int retryCount = 0;
                            String currentPrompt = null;

                            // Construct a robust prompt to prevent hallucination
                            String basePrompt = (ocrPrompt != null && !ocrPrompt.isEmpty()) ? ocrPrompt : 
                                    "请识别图片中的目录内容。请严格按照图片中的顺序，输出一个扁平的 JSON 数组。每个元素包含 'title' (完整的章节标题，必须包含章节编号)、'page' (页码) 和 'level' (层级，1-4)。";
                            
                            String antiHallucination = "\n\n**IMPORTANT**: \n" +
                                    "1. Strictly output ONLY the text visible in the images.\n" +
                                    "2. Do NOT predict or complete the table of contents.\n" +
                                    "3. If the text ends, stop the JSON array immediately.\n" +
                                    "4. Do NOT generate chapters that are not present in the image.";
                            
                            String initialPrompt = basePrompt + antiHallucination;

                            while (retryCount <= maxRetries) {
                                try {
                                    String promptToUse = (currentPrompt != null) ? currentPrompt : initialPrompt;
                                    String batchResult = ocrEngine.doOCR(batch, promptToUse);
                                    long batchItemDuration = System.currentTimeMillis() - batchItemStart;
                                    // System.out.println("DEBUG: Batch " + (k / batchSize + 1) + " Try " + (retryCount + 1) + " Result (" + batchItemDuration + "ms):\n" + batchResult);

                                    List<RawToc> batchRaws = TocTextParser.tryParseJson(batchResult);

                                    // Try to fix truncated JSON
                                    if (batchRaws.isEmpty() && batchResult.contains("[")) {
                                        batchRaws = TocTextParser.tryParseJsonLenient(batchResult);
                                    }

                                    if (!batchRaws.isEmpty()) {
                                        // NEW: Overlap/Hallucination Detection (Enhanced)
                                        if (!allBatchRaws.isEmpty()) {
                                            int bestCutIndex = -1;
                                            // Check first 50 items (increased from 10) of new batch to find ANY anchor in the old batch
                                            // This handles cases where the overlap happens after a few new items (e.g. filling a gap)
                                            int checkRange = Math.min(batchRaws.size(), 50); 
                                            
                                            for (int n = 0; n < checkRange; n++) {
                                                RawToc newItem = batchRaws.get(n);
                                                String newTitle = newItem.getTitle().replaceAll("\\s+", "").toLowerCase();
                                                if (newTitle.length() < 2) continue; // Skip very short titles

                                                // Search backwards in allBatchRaws (entire tail)
                                                // We search from end to start to find the LATEST occurrence (closest to the cut point)
                                                for (int j = allBatchRaws.size() - 1; j >= 0; j--) {
                                                    RawToc existingItem = allBatchRaws.get(j);
                                                    String existingTitle = existingItem.getTitle().replaceAll("\\s+", "").toLowerCase();
                                                    
                                                    boolean match = false;
                                                    if (existingTitle.equals(newTitle)) match = true;
                                                    else if (existingTitle.length() > 5 && newTitle.length() > 5) {
                                                        if (existingTitle.contains(newTitle) || newTitle.contains(existingTitle)) match = true;
                                                    }
                                                    
                                                    if (match) {
                                                        // Only accept match if it looks like a chapter/section or is long enough
                                                        // This avoids matching generic words like "Summary" if they appear frequently
                                                        // Also accept if it matches "Chapter X" pattern
                                                        boolean isChapter = newItem.getTitle().matches("^(第[一二三四五六七八九十\\d]+[章部篇]|Chapter\\s*\\d+).*");
                                                        if (isChapter || newItem.getTitle().matches(".*\\d+\\.\\d+.*") || newItem.getTitle().length() > 6) {
                                                             bestCutIndex = j;
                                                             System.out.println(">>> Overlap/Hallucination detected: New item '" + newItem.getTitle() + 
                                                                     "' matches old item '" + existingItem.getTitle() + "' at index " + j);
                                                             break; 
                                                        }
                                                    }
                                                }
                                                if (bestCutIndex != -1) break; // Found a valid cut point
                                            }
                                            
                                            if (bestCutIndex != -1) {
                                                 System.out.println(">>> Pruning hallucinated/overlapped tail from index " + bestCutIndex);
                                                 while (allBatchRaws.size() > bestCutIndex) {
                                                     allBatchRaws.remove(allBatchRaws.size() - 1);
                                                 }
                                            }
                                        }
                                        
                                        allBatchRaws.addAll(batchRaws);
                                    } else {
                                        // Parsing failed or empty result
                                        if (retryCount < maxRetries) {
                                            System.out.println(">>> Parsing failed (empty result). Retrying...");
                                            retryCount++;
                                            try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                                            continue;
                                        }
                                        if (retryCount == maxRetries) {
                                            allTextFallback.append(batchResult).append("\n");
                                        }
                                    }

                                    // Check for truncation
                                    String trimmed = batchResult.trim();
                                    if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
                                    // Allow ending with ']' (array) or '}' (wrapped object)
                                    boolean isTruncated = !trimmed.endsWith("]") && !trimmed.endsWith("}");

                                    if (isTruncated && retryCount < maxRetries) {
                                        String lastTitle = null;
                                        if (!batchRaws.isEmpty()) {
                                            lastTitle = batchRaws.get(batchRaws.size() - 1).getTitle();
                                        } else {
                                            lastTitle = TocTextParser.extractLastTitleFromText(batchResult);
                                        }

                                        if (lastTitle != null) {
                                            System.out.println(">>> Truncation detected. Continuing after: " + lastTitle);

                                            String formatInstruction = (ocrPrompt != null && !ocrPrompt.isEmpty()) ? ocrPrompt : "Output as a JSON array with 'title', 'page', and 'level' fields.";

                                            currentPrompt = "The previous output was truncated. We need to continue extracting the Table of Contents.\n" +
                                                    "**TASK**: Start listing items IMMEDIATELY AFTER the chapter: \"" + lastTitle + "\".\n" +
                                                    "**CONSTRAINT**: \n" +
                                                    "1. Do NOT repeat \"" + lastTitle + "\".\n" +
                                                    "2. Do NOT list any chapters that appear before \"" + lastTitle + "\".\n" +
                                                    "3. Continue until the end of the provided images.\n\n" +
                                                    "**FORMATTING RULES** (Strictly follow these rules):\n" +
                                                    formatInstruction;

                                            // System.out.println("DEBUG: Retry Prompt Length: " + currentPrompt.length());

                                            retryCount++;
                                            continue;
                                        }
                                    }
                                    break; // Success or cannot continue

                                } catch (Exception e) {
                                    System.err.println("Batch OCR Failed: " + e.getMessage());
                                    if (retryCount < maxRetries) {
                                        System.out.println(">>> Retrying batch due to exception...");
                                        retryCount++;
                                        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                                        continue;
                                    }
                                    break;
                                }
                            }

                            if (end < tocImages.size()) Thread.sleep(500);
                        }

                        if (!allBatchRaws.isEmpty()) {
                            raws.addAll(allBatchRaws);

                            // Global Level Correction (Again, to ensure cross-batch context)
                            boolean hasPart = raws.stream().anyMatch(r -> r.getTitle().matches("^第[一二三四五六七八九十百]+[部编].*"));
                            if (hasPart) {
                                for (RawToc r : raws) {
                                    if (r.getTitle().matches("^第[一二三四五六七八九十百]+[章].*")) {
                                        r.setLevel(2);
                                    } else if (r.getTitle().matches("^第[一二三四五六七八九十百]+[节].*") || r.getTitle().matches("^\\d+\\.\\d+.*")) {
                                        r.setLevel(3);
                                    }
                                }
                            }
                        } else {
                            tocLines.addAll(Arrays.asList(allTextFallback.toString().split("\\r?\\n")));
                        }

                        break;
                    }
                } catch (Exception e) {
                    System.err.println("OCR Error on page " + i + ": " + e.getMessage());
                    continue;
                }
            }

            // 2. Parse lines (Fallback if JSON parsing failed but we have text)
            if (raws.isEmpty() && !tocLines.isEmpty()) {
                List<RawToc> parsedRaws = new ArrayList<>();
                for (String line : tocLines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    line = TocTextParser.cleanContentOcrTypos(line);

                    if (TocTextParser.isGarbageLine(line)) continue;

                    // Markdown Table Row
                    if (line.startsWith("|") && line.endsWith("|")) {
                        String[] parts = line.split("\\|");
                        if (parts.length >= 3) {
                            String title = parts[1].trim();
                            String pageStr = parts[2].trim();
                            if (pageStr.matches("\\d+")) {
                                parsedRaws.add(new RawToc(title, Integer.parseInt(pageStr)));
                                continue;
                            }
                        }
                        if (parts.length >= 2) {
                            String title = parts[1].trim();
                            if (!title.equals("目录") && !title.matches("-+")) {
                                parsedRaws.add(new RawToc(title, -1));
                            }
                        }
                    }

                    Matcher m1 = Pattern.compile("^(.*?)[…\\._—\\s]{2,}(\\d+)$").matcher(line);
                    if (m1.find()) {
                        String title = m1.group(1).trim();
                        title = title.replaceAll("[…\\._—]+$", "").trim();

                        if (title.length() > 1) {
                            parsedRaws.add(new RawToc(title, Integer.parseInt(m1.group(2))));
                        }
                        continue;
                    }

                    if (line.matches(".*\\s\\d+$") && line.length() > 5) {
                        String[] parts = line.split("\\s+");
                        String pageStr = parts[parts.length - 1];
                        if (pageStr.matches("\\d+")) {
                            String title = line.substring(0, line.lastIndexOf(pageStr)).trim();
                            parsedRaws.add(new RawToc(title, Integer.parseInt(pageStr)));
                        }
                    }
                }
                raws.addAll(parsedRaws);
            }

            // 2.5 Backfill page numbers
            // Modified: Smart backfill for container nodes (Parts/Volumes)
            for (int i = 0; i < raws.size(); i++) {
                RawToc current = raws.get(i);
                
                // Skip "Table of Contents" itself
                if (current.getTitle() != null && current.getTitle().matches("^(目录|Contents|Table of Contents)$")) {
                    current.setLogicalPage(-999); // Mark for deletion
                    continue;
                }

                if (current.getLogicalPage() <= 0) {
                    // Look ahead for the first valid page number
                    for (int j = i + 1; j < raws.size(); j++) {
                        RawToc next = raws.get(j);
                        if (next.getLogicalPage() > 0) {
                            // Only inherit if the next item is a child (higher level number) or same level
                            // e.g. Part 1 (L1) -> Chapter 1 (L2, Page 10) => Part 1 gets Page 10
                            if (next.getLevel() >= current.getLevel()) {
                                current.setLogicalPage(next.getLogicalPage());
                            }
                            break;
                        }
                    }
                    // If still no page (e.g. end of list), default to 1 or keep as invalid
                    if (current.getLogicalPage() <= 0) current.setLogicalPage(1);
                }
            }

            // 3. Dynamic Page Alignment
            if (!raws.isEmpty()) {
                long alignStart = System.currentTimeMillis();
                for (RawToc r : raws) {
                    if (r.getTitle() != null) {
                        r.setTitle(r.getTitle().replaceAll("[…\\._—\\s]+\\d+$", "").trim());
                    }
                }
                // Use Discovery Engine (Fast Model) for Alignment Search
                System.out.println(">>> Starting Page Alignment using Discovery Engine...");
                progressService.sendProgress("正在进行页码对齐...");
                pageAlignmentService.alignPageNumbers(doc, raws, discoveryEngine);
                System.out.println(">>> Alignment completed in " + (System.currentTimeMillis() - alignStart) + "ms");
            }

            // 4. Generate Result
            for (RawToc r : raws) {
                // Skip items marked for deletion
                if (r.getLogicalPage() == -999) continue;

                // If physical page is set (by PageAlignmentService), use it directly.
                // Otherwise, fallback to logical page (which is likely wrong but better than nothing).
                // Note: PageAlignmentService already converts to 1-based index.
                int physicalPage = (r.getPhysicalPage() > 0) ? r.getPhysicalPage() : r.getLogicalPage();

                if (physicalPage > 0 && physicalPage <= doc.getNumberOfPages()) {
                    TocItem item = new TocItem();
                    item.setTitle(r.getTitle());
                    item.setPage(physicalPage);
                    item.setLevel(r.getLevel());
                    items.add(item);
                }
            }
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Tesseract native library not found: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return items;
    }
}

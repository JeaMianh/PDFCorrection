package com.example.pdfcorrection.strategy.impl;

import com.example.pdfcorrection.model.RawToc;
import com.example.pdfcorrection.model.TocItem;
import com.example.pdfcorrection.ocr.OcrEngine;
import com.example.pdfcorrection.ocr.impl.TesseractEngine;
import com.example.pdfcorrection.service.PageAlignmentService;
import com.example.pdfcorrection.util.TocTextParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OcrStrategy {

    private final PageAlignmentService pageAlignmentService;

    public OcrStrategy(PageAlignmentService pageAlignmentService) {
        this.pageAlignmentService = pageAlignmentService;
    }

    public List<TocItem> extract(MultipartFile file, OcrEngine ocrEngine, String ocrPrompt) throws IOException {
        List<TocItem> items = new ArrayList<>();
        
        // Use Tesseract for Discovery Scan (Faster & Cheaper)
        OcrEngine discoveryEngine = new TesseractEngine();

        try (PDDocument doc = PDDocument.load(file.getInputStream())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int maxScan = Math.min(20, doc.getNumberOfPages());

            List<String> tocLines = new ArrayList<>();
            int tocStartPage = -1;
            List<RawToc> raws = new ArrayList<>();

            // 1. Find TOC pages (OCR)
            for (int i = 0; i < maxScan; i++) { // PDFBox page index starts at 0
                try {
                    // Phase 1: Quick Scan (150 DPI)
                    BufferedImage image = renderer.renderImageWithDPI(i, 150);
                    // Force plain text prompt for detection
                    String text = discoveryEngine.doOCR(image, "请仅输出图像中的文本内容。");

                    String cleanText = text.replaceAll("\\s+", "");
                    int tocLikeCount = TocTextParser.countTocLikeLines(text);

                    System.out.println("Discovery Scan Page " + i + " (TesseractEngine): length=" + text.length() + ", tocLines=" + tocLikeCount + ", hasKeyword=" + (cleanText.contains("目录") || cleanText.toLowerCase().contains("contents")));

                    boolean isTocPage = cleanText.contains("目录") || cleanText.toLowerCase().contains("contents");
                    if (!isTocPage) {
                        int chapterPatternCount = TocTextParser.countChapterPatterns(text);
                        if (tocLikeCount >= 5 && chapterPatternCount >= 2) {
                            isTocPage = true;
                            System.out.println("   -> Detected as TOC by density (Lines=" + tocLikeCount + ", Patterns=" + chapterPatternCount + ")");
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
                            String nextTextLow = discoveryEngine.doOCR(nextImageLow, "请仅输出图像中的文本内容。");

                            if (TocTextParser.countTocLikeLines(nextTextLow) > 3) {
                                System.out.println(">>> Found TOC Continuation at Page " + j);
                                tocImages.add(renderer.renderImageWithDPI(j, 300));
                            } else {
                                break;
                            }
                        }

                        // Batch OCR
                        System.out.println(">>> Batch OCR for " + tocImages.size() + " pages (Batch Size: 3)...");

                        List<RawToc> allBatchRaws = new ArrayList<>();
                        StringBuilder allTextFallback = new StringBuilder();

                        int batchSize = 3;
                        for (int k = 0; k < tocImages.size(); k += batchSize) {
                            int end = Math.min(k + batchSize, tocImages.size());
                            List<BufferedImage> batch = tocImages.subList(k, end);
                            System.out.println(">>> Processing Batch " + (k / batchSize + 1) + " (Pages " + k + " to " + (end - 1) + ")...");

                            int maxRetries = 3;
                            int retryCount = 0;
                            String currentPrompt = null;

                            while (retryCount <= maxRetries) {
                                try {
                                    String batchResult = ocrEngine.doOCR(batch, currentPrompt);
                                    System.out.println("DEBUG: Batch " + (k / batchSize + 1) + " Try " + (retryCount + 1) + " Result:\n" + batchResult);

                                    List<RawToc> batchRaws = TocTextParser.tryParseJson(batchResult);

                                    // Try to fix truncated JSON
                                    if (batchRaws.isEmpty() && batchResult.contains("[")) {
                                        batchRaws = TocTextParser.tryParseJsonLenient(batchResult);
                                    }

                                    if (!batchRaws.isEmpty()) {
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

                                            System.out.println("DEBUG: Retry Prompt Length: " + currentPrompt.length());

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
            for (int i = 0; i < raws.size(); i++) {
                if (raws.get(i).getLogicalPage() == -1) {
                    for (int j = i + 1; j < raws.size(); j++) {
                        if (raws.get(j).getLogicalPage() != -1) {
                            raws.get(i).setLogicalPage(raws.get(j).getLogicalPage());
                            break;
                        }
                    }
                    if (raws.get(i).getLogicalPage() == -1) raws.get(i).setLogicalPage(1);
                }
            }

            // 3. Dynamic Page Alignment
            if (!raws.isEmpty()) {
                for (RawToc r : raws) {
                    if (r.getTitle() != null) {
                        r.setTitle(r.getTitle().replaceAll("[…\\._—\\s]+\\d+$", "").trim());
                    }
                }
                pageAlignmentService.alignPageNumbers(doc, raws, ocrEngine);
            }

            // 4. Generate Result
            for (RawToc r : raws) {
                int physicalPage = (r.getPhysicalPage() > 0) ? r.getPhysicalPage() + 1 : r.getLogicalPage();

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

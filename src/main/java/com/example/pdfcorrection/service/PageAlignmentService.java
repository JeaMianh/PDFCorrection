package com.example.pdfcorrection.service;

import com.example.pdfcorrection.model.RawToc;
import com.example.pdfcorrection.ocr.OcrEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class PageAlignmentService {

    /**
     * Dynamic Page Alignment
     * Strategy Optimization:
     * 1. Quick Pre-check: Sample 3 points (First, Middle, Last) to calculate offset.
     * 2. If offsets are consistent, apply globally.
     * 3. If inconsistent, fallback to segment-based alignment.
     */
    public void alignPageNumbers(PDDocument doc, List<RawToc> raws, OcrEngine ocrEngine) {
        PDFRenderer renderer = new PDFRenderer(doc);
        int totalPages = doc.getNumberOfPages();

        // Filter anchors (Level 1, has page, title length >= 2)
        List<RawToc> anchors = raws.stream()
                .filter(r -> r.getLevel() == 1 && r.getLogicalPage() > 0 && r.getTitle() != null && r.getTitle().length() >= 2)
                .collect(Collectors.toList());

        if (anchors.isEmpty()) {
            // Fallback to Level 2 if no Level 1 exists
            anchors = raws.stream()
                    .filter(r -> r.getLogicalPage() > 0 && r.getTitle() != null && r.getTitle().length() >= 2)
                    .collect(Collectors.toList());
        }

        if (anchors.isEmpty()) {
            // Fallback: If no anchors found (e.g. OCR failed to read page numbers), try to find the first few titles
            // to establish a baseline offset.
            System.out.println("[Align] No anchors with page numbers found. Attempting Blind Search for first chapters...");

            // Try first 3 items that look like chapters
            List<RawToc> candidates = raws.stream()
                    .filter(r -> r.getTitle() != null && r.getTitle().length() >= 4 && !r.getTitle().contains("..."))
                    .limit(3)
                    .collect(Collectors.toList());

            boolean found = false;
            for (RawToc candidate : candidates) {
                // Assume logical page 1 for search purposes if 0
                int assumedLogical = (candidate.getLogicalPage() > 0) ? candidate.getLogicalPage() : 1;

                // Search in a wider range (e.g. 0 to 50)
                int foundOffset = findOffsetForTitle(renderer, ocrEngine, candidate.getTitle(), assumedLogical, 0, 50, totalPages, true);

                if (foundOffset != -999) {
                    System.out.println("[Align]   > Found [" + candidate.getTitle() + "] with offset " + foundOffset + " (assuming logical " + assumedLogical + ")");

                    // Apply this offset to all items
                    int finalOffset = foundOffset;
                    for (RawToc item : raws) {
                        int log = (item.getLogicalPage() > 0) ? item.getLogicalPage() : 1;
                        item.setPhysicalPage(log + finalOffset);
                    }
                    found = true;
                    break;
                }
            }

            if (!found) {
                // If still nothing, we can't align. But we shouldn't drop them.
                // Default to Physical Page 1 to prevent data loss.
                System.out.println("[Align] Blind search failed. Defaulting all items to Physical Page 1 to prevent data loss.");
                for (RawToc item : raws) {
                    if (item.getPhysicalPage() <= 0) {
                        item.setPhysicalPage(1);
                    }
                }
            }
            return;
        }

        // === Phase 1: Sampling ===
        List<RawToc> samples = new ArrayList<>();
        if (!anchors.isEmpty()) samples.add(anchors.get(0)); // First
        if (anchors.size() > 1) samples.add(anchors.get(1)); // Second
        if (anchors.size() > 10) samples.add(anchors.get(anchors.size() / 2)); // Middle
        if (anchors.size() > 2) {
            int lastIndex = anchors.size() - 1;
            if (anchors.size() > 3) {
                lastIndex = anchors.size() - 2; // Second to last (avoid index/appendix)
            }
            samples.add(anchors.get(lastIndex));
        }

        if (anchors.size() > 1) {
            RawToc second = anchors.get(1);
            System.out.println("[Align] Sampling Strategy: Using 2nd chapter [" + second.getTitle() + "] as baseline...");

            // Positive Only search for baseline
            int baselineOffset = findOffsetForTitle(renderer, ocrEngine, second.getTitle(), second.getLogicalPage(), 0, 20, totalPages, true);

            if (baselineOffset != -999) {
                System.out.println("[Align]   > Baseline Offset (from 2nd chapter): " + baselineOffset + ". Verifying Middle/Last...");
                boolean consistent = true;

                // Verify Middle and Last
                for (int i = 2; i < samples.size(); i++) {
                    RawToc s = samples.get(i);
                    if (s == second) continue;

                    int verifyOffset = findOffsetForTitle(renderer, ocrEngine, s.getTitle(), s.getLogicalPage(), baselineOffset, 2, totalPages);
                    if (verifyOffset != baselineOffset) {
                        System.out.println("[Align]   > Mismatch at [" + s.getTitle() + "]: Expected " + baselineOffset + ", Got " + verifyOffset);
                        consistent = false;
                        break;
                    }
                }

                if (consistent) {
                    System.out.println("[Align]   > Body samples consistent. Checking First Chapter...");

                    // Check First Chapter independently
                    RawToc first = anchors.get(0);
                    int firstOffset = findOffsetForTitle(renderer, ocrEngine, first.getTitle(), first.getLogicalPage(), baselineOffset, 15, totalPages);

                    int secondPhysical = second.getLogicalPage() + baselineOffset;
                    if (firstOffset != -999) {
                        int firstPhysical = first.getLogicalPage() + firstOffset;
                        if (firstPhysical > secondPhysical) {
                            System.out.println("[Align]   > First chapter found at " + firstPhysical + " but > 2nd chapter (" + secondPhysical + "). Rejecting.");
                            firstOffset = -999;
                        } else {
                            System.out.println("[Align]   > First Chapter verified at Offset: " + firstOffset);
                        }
                    }

                    if (firstOffset == -999) {
                        System.out.println("[Align]   > First chapter mismatch. Searching independently...");
                        int maxOffset = Math.max(0, secondPhysical - first.getLogicalPage());
                        int searchRange = Math.min(30, maxOffset);
                        System.out.println("[Align]   > Constraining search range to " + searchRange + " (Max Phys: " + secondPhysical + ")");
                        firstOffset = findOffsetForTitle(renderer, ocrEngine, first.getTitle(), first.getLogicalPage(), 0, searchRange, totalPages);
                    }

                    int finalFirstOffset = (firstOffset != -999) ? firstOffset : baselineOffset;

                    System.out.println("[Align]   > SUCCESS: Applying offsets. Body: " + baselineOffset + ", First: " + finalFirstOffset);

                    for (int i = 0; i < raws.size(); i++) {
                        RawToc item = raws.get(i);
                        if (item.getLogicalPage() <= 0) continue;

                        if (i == 0) {
                            item.setPhysicalPage(item.getLogicalPage() + finalFirstOffset);
                        } else {
                            item.setPhysicalPage(item.getLogicalPage() + baselineOffset);
                        }
                    }
                    return; // Success
                }
            }
        }

        System.out.println("[Align] Sampling failed or inconsistent. Fallback to Smart Alignment (Major Chapter Check)...");

        // === Phase 2: Segment-based Alignment ===
        TreeMap<Integer, Integer> knownOffsets = new TreeMap<>();
        int currentOffset = 0;
        int lastLogicalPage = -1;
        int lastPhysicalPage = -1;

        // Pass 1: Collect known offsets
        for (int i = 0; i < raws.size(); i++) {
            RawToc item = raws.get(i);
            if (item.getLogicalPage() <= 0) continue;

            boolean isFirstItem = (lastLogicalPage == -1);
            boolean isPageReset = (lastLogicalPage != -1 && item.getLogicalPage() < lastLogicalPage);
            boolean isMajorChapter = item.getTitle() != null && item.getTitle().matches("^(第[一二三四五六七八九十\\d]+[章部篇]|Chapter\\s*\\d+).*");
            boolean isSamePage = (item.getLogicalPage() == lastLogicalPage);
            boolean shouldSearch = (isFirstItem || isPageReset || isMajorChapter) && !isSamePage;

            if (shouldSearch) {
                if (item.getTitle() != null && item.getTitle().length() >= 2) {
                    int searchBaseOffset = currentOffset;
                    int searchRange = 5;
                    if (isFirstItem || isPageReset) {
                        searchRange = 30;
                        if (isPageReset && lastPhysicalPage != -1) {
                            int minPhysical = lastPhysicalPage + 1;
                            int minOffset = minPhysical - item.getLogicalPage();
                            if (minOffset > currentOffset) searchBaseOffset = minOffset;
                        }
                    }

                    int foundOffset = findOffsetForTitle(renderer, ocrEngine, item.getTitle(), item.getLogicalPage(), searchBaseOffset, searchRange, totalPages);

                    if (foundOffset != -999) {
                        currentOffset = foundOffset;
                        knownOffsets.put(item.getLogicalPage(), foundOffset);
                        System.out.println("[Align]   > Found Offset " + foundOffset + " at Logical Page " + item.getLogicalPage() + " [" + item.getTitle() + "]");
                    }
                }
            }
            lastLogicalPage = item.getLogicalPage();
            lastPhysicalPage = item.getLogicalPage() + currentOffset;
        }

        if (knownOffsets.isEmpty()) {
            System.out.println("[Align] No valid offsets found. Defaulting to 0.");
            knownOffsets.put(0, 0);
        }

        // Pass 2: Apply segment offsets
        for (RawToc item : raws) {
            if (item.getLogicalPage() > 0) {
                Map.Entry<Integer, Integer> entry = knownOffsets.floorEntry(item.getLogicalPage());
                int offsetToUse;
                if (entry != null) {
                    offsetToUse = entry.getValue();
                } else {
                    offsetToUse = knownOffsets.firstEntry().getValue();
                }
                item.setPhysicalPage(item.getLogicalPage() + offsetToUse);
            }
        }
    }

    private int findOffsetForTitle(PDFRenderer renderer, OcrEngine ocrEngine, String title, int logicalPage, int baseOffset, int searchRange, int totalPages) {
        return findOffsetForTitle(renderer, ocrEngine, title, logicalPage, baseOffset, searchRange, totalPages, false);
    }

    private int findOffsetForTitle(PDFRenderer renderer, OcrEngine ocrEngine, String title, int logicalPage, int baseOffset, int searchRange, int totalPages, boolean positiveOnly) {
        List<Integer> searchOrder = new ArrayList<>();
        searchOrder.add(0);
        for (int i = 1; i <= searchRange; i++) {
            searchOrder.add(i);
        }
        if (!positiveOnly) {
            for (int i = 1; i <= searchRange; i++) {
                searchOrder.add(-i);
            }
        }

        for (int k : searchOrder) {
            int offset = baseOffset + k;
            int guessPhys = logicalPage + offset;

            if (guessPhys < 0 || guessPhys >= totalPages) continue;

            try {
                // Render top 50% of the page
                BufferedImage fullImage = renderer.renderImageWithDPI(guessPhys, 100);
                int h = fullImage.getHeight();
                int w = fullImage.getWidth();
                if (h < 10 || w < 10) continue;
                BufferedImage topImage = fullImage.getSubimage(0, 0, w, h / 2);

                // VQA Prompt
                String prompt = "请仔细观察图片，判断这是否是章节“" + title + "”的【起始页】。\n" +
                        "判断标准：\n" +
                        "1. 页面上必须包含标题“" + title + "”。\n" +
                        "2. 该标题必须是【大标题】（字号明显大于正文，通常居中或加粗）。\n" +
                        "3. 严禁匹配【页眉】！如果标题仅出现在页面顶部的页眉区域（字体较小，旁边可能有页码），请回答“否”。\n" +
                        "4. 严禁匹配【目录】！如果页面包含多个章节标题和页码，请回答“否”。\n" +
                        "请只回答“是”或“否”。\n" +
                        "请以 JSON 格式输出，格式为：{\"result\": \"是\"} 或 {\"result\": \"否\"}";
                String response = ocrEngine.doOCR(topImage, prompt).trim();

                if (response.contains("是") || response.toLowerCase().contains("yes")) {
                    return offset;
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return -999;
    }
}

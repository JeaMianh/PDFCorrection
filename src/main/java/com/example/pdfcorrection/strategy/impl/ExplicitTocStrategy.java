package com.example.pdfcorrection.strategy.impl;

import com.example.pdfcorrection.model.TextLine;
import com.example.pdfcorrection.model.TocItem;
import com.example.pdfcorrection.util.StyledTextExtractor;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ExplicitTocStrategy {

    // Match lines with page numbers (same line or cross-line)
    private static final Pattern SUB_TITLE_PATTERN = Pattern.compile("^([一二三四五六七八九十百]+[、]|第[一二三四五六七八九十百]+[部分|章|节]|附录).*");

    public List<TocItem> extract(PdfDocument pdf, List<Integer> pages) {
        List<RawToc> raws = new ArrayList<>();

        for (Integer p : pages) {
            StyledTextExtractor extractor = new StyledTextExtractor();
            try {
                new PdfCanvasProcessor(extractor).processPageContent(pdf.getPage(p));
                extractor.finish();
            } catch (Exception e) {
                System.err.println("Warning: Failed to process page " + p + " in explicit TOC strategy: " + e.getMessage());
                continue;
            }

            List<TextLine> lines = extractor.getLines();

            for (int i = 0; i < lines.size(); i++) {
                TextLine curr = lines.get(i);
                String text = curr.getText().trim();
                if (text.isEmpty()) continue;

                // 1. Try to match line with page number (Same line)
                String normText = text.replaceAll("[\\u2000-\\u200B]", "  ");
                Matcher m = Pattern.compile("^(.*?)" + "(?:\\.{2,}|…|—|\\s{2,})" + "(\\d+)$").matcher(normText);

                if (m.find()) {
                    raws.add(new RawToc(m.group(1).trim(), Integer.parseInt(m.group(2)), curr.getFontSize(), true));
                }
                // 2. Try to match cross-line (Current line title, Next line pure number)
                else if (i + 1 < lines.size() && lines.get(i + 1).getText().trim().matches("^\\d+$")) {
                    raws.add(new RawToc(text, Integer.parseInt(lines.get(i + 1).getText().trim()), curr.getFontSize(), true));
                    i++; // Skip page number line
                }
                // 3. Match subtitle without page number (e.g. "一、Title")
                else if (SUB_TITLE_PATTERN.matcher(text).matches()) {
                    // Record with -1, fill later
                    raws.add(new RawToc(text, -1, curr.getFontSize(), false));
                }
            }
        }

        if (raws.isEmpty()) return Collections.emptyList();

        // 4. Backfill page numbers
        for (int i = 0; i < raws.size(); i++) {
            if (raws.get(i).logicalPage == -1) {
                // Look forward for the nearest valid page number
                for (int j = i + 1; j < raws.size(); j++) {
                    if (raws.get(j).logicalPage != -1) {
                        raws.get(i).logicalPage = raws.get(j).logicalPage;
                        break;
                    }
                }
                // If no page number found (rare), set to 1
                if (raws.get(i).logicalPage == -1) raws.get(i).logicalPage = 1;
            }
        }

        // Calibration and Conversion
        int offset = calculatePageOffset(pdf, raws.stream().filter(r -> r.hasExplicitPage).collect(Collectors.toList()));

        List<TocItem> result = new ArrayList<>();
        for (RawToc r : raws) {
            int physicalPage = r.logicalPage + offset;
            if (physicalPage > 0 && physicalPage <= pdf.getNumberOfPages()) {
                TocItem item = new TocItem();
                item.setTitle(r.title);
                item.setPage(physicalPage);
                item.setLevel(inferLevel(r.title));
                result.add(item);
            }
        }
        return result;
    }

    // Smart Calibration: Look for title in content near logical page
    private int calculatePageOffset(PdfDocument pdf, List<RawToc> raws) {
        Map<Integer, Integer> offsetVotes = new HashMap<>();

        // Sample first 5 and last 5
        List<RawToc> samples = new ArrayList<>();
        samples.addAll(raws.subList(0, Math.min(raws.size(), 5)));
        if (raws.size() > 10) samples.addAll(raws.subList(raws.size() - 5, raws.size()));

        for (RawToc item : samples) {
            // Assume offset is between -20 and +30
            for (int k = -20; k <= 30; k++) {
                int guessPhys = item.logicalPage + k;
                if (guessPhys < 1 || guessPhys > pdf.getNumberOfPages()) continue;

                String pageContent = "";
                try {
                    pageContent = PdfTextExtractor.getTextFromPage(pdf.getPage(guessPhys));
                } catch (Exception e) {
                    continue;
                }

                // Loose match
                if (matchTitleInContent(item.title, pageContent)) {
                    offsetVotes.merge(k, 1, Integer::sum);
                }
            }
        }

        // Return offset with most votes, default 0
        return offsetVotes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);
    }

    private boolean matchTitleInContent(String title, String content) {
        String cleanTitle = title.replaceAll("[\\s\\d.]", "");
        if (cleanTitle.length() < 2) return false;
        return content.replaceAll("\\s", "").contains(cleanTitle);
    }

    /**
     * Enhanced Level Inference
     */
    private int inferLevel(String title) {
        String t = title.trim();

        // Level 1
        if (t.matches("^第[一二三四五六七八九十百]+[部编].*")) {
            return 1;
        }

        // Level 2
        if (t.matches("^第[一二三四五六七八九十百]+[章].*") || t.matches("^[一二三四五六七八九十百]+[、\\s].*")) {
            return 2;
        }

        // Level 3
        if (t.matches("^第[一二三四五六七八九十百]+[节].*") || t.matches("^\\d+\\.\\d+.*") || t.matches("^[(（][一二三四五六七八九十百]+[)）].*")) {
            return 3;
        }

        // Level 4
        if (t.matches("^\\d+[\\.\\s].*") || t.matches("^[(（]\\d+[)）].*")) {
            return 4;
        }

        // Fallback
        return 2;
    }

    private static class RawToc {
        String title;
        int logicalPage;
        float fontSize; // Kept for potential future use or debugging
        boolean hasExplicitPage;

        RawToc(String t, int p, float f, boolean hasPage) {
            title = t;
            logicalPage = p;
            fontSize = f;
            hasExplicitPage = hasPage;
        }
    }
}

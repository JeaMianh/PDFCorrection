package com.example.pdfcorrection.strategy.impl;

import com.example.pdfcorrection.model.TextLine;
import com.example.pdfcorrection.model.TocItem;
import com.example.pdfcorrection.util.StyledTextExtractor;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class LayoutStructureStrategy {

    public List<TocItem> extract(PdfDocument pdf) {
        // 1. Sampling: Calculate Body Font Size (Mode Font Size)
        float bodyFontSize = analyzeBodyFont(pdf);

        List<Candidate> candidates = new ArrayList<>();
        int totalPages = pdf.getNumberOfPages();

        // 2. Full Scan
        for (int p = 1; p <= totalPages; p++) {
            StyledTextExtractor extractor = new StyledTextExtractor();
            try {
                new PdfCanvasProcessor(extractor).processPageContent(pdf.getPage(p));
                extractor.finish();
            } catch (Exception e) {
                System.err.println("Warning: Failed to process page " + p + " in layout strategy: " + e.getMessage());
                continue;
            }

            List<TextLine> lines = extractor.getLines();
            for (int i = 0; i < lines.size(); i++) {
                TextLine line = lines.get(i);
                // Filter conditions:
                // A. Font size significantly larger than body (> body + 1pt) OR Bold and >= body
                // B. Reasonable length (3 ~ 50 chars)
                // C. Or matches standard "1. xxx" pattern
                boolean isBig = line.getFontSize() > bodyFontSize + 1.0f;
                boolean isBold = line.isBold() && line.getFontSize() >= bodyFontSize;
                boolean isPattern = line.getText().trim().matches("^(第.+章|\\d+\\.|\\d+\\s+).*");

                if ((isBig || (isBold && isPattern)) && line.getText().length() < 60 && line.getText().length() > 2) {
                    // Exclude header/footer (by coordinates, simplified here by relying on later cleaning)
                    candidates.add(new Candidate(line.getText().trim(), p, line.getFontSize()));
                }
            }
        }

        // 3. Determine Levels
        // Cluster candidates by font size
        List<Float> fontSizes = candidates.stream()
                .map(c -> c.fontSize)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        // Assume largest font is Level 1, second largest is Level 2

        List<TocItem> result = new ArrayList<>();
        for (Candidate c : candidates) {
            int level = 1;
            if (fontSizes.indexOf(c.fontSize) > 0) level = 2; // Second largest
            if (fontSizes.indexOf(c.fontSize) > 1) level = 3;

            TocItem item = new TocItem();
            item.setTitle(c.title);
            item.setPage(c.page);
            item.setLevel(level);
            result.add(item);
        }

        // 4. Clean: Deduplicate and Sort
        return cleanLayoutResult(result);
    }

    private float analyzeBodyFont(PdfDocument pdf) {
        Map<Float, Integer> freq = new HashMap<>();
        // Sample first 10 pages
        for (int i = 1; i <= Math.min(10, pdf.getNumberOfPages()); i++) {
            StyledTextExtractor ex = new StyledTextExtractor();
            try {
                new PdfCanvasProcessor(ex).processPageContent(pdf.getPage(i));
                ex.finish();
                for (TextLine l : ex.getLines()) {
                    if (!l.getText().trim().isEmpty())
                        freq.merge(l.getFontSize(), l.getText().length(), Integer::sum);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        // Return font size with most characters
        return freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(12.0f);
    }

    private List<TocItem> cleanLayoutResult(List<TocItem> items) {
        List<TocItem> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (TocItem i : items) {
            String key = i.getPage() + "_" + i.getTitle();
            if (!seen.contains(key)) {
                // Simple regex to filter garbage
                if (!i.getTitle().matches("^\\d+$") && !i.getTitle().contains("......")) {
                    clean.add(i);
                }
                seen.add(key);
            }
        }
        return clean;
    }

    private static class Candidate {
        String title;
        int page;
        float fontSize;
        Candidate(String t, int p, float f) { title = t; page = p; fontSize = f; }
    }
}

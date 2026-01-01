package com.example.pdfcorrection.service;

import com.example.pdfcorrection.model.TextLine;
import com.example.pdfcorrection.util.StyledTextExtractor;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class TocDiscoveryService {

    /**
     * Core Improvement: Use "Density Detection" to identify multi-page TOCs.
     */
    public List<Integer> findTocPages(PdfDocument pdf) {
        List<Integer> tocPages = new ArrayList<>();
        int totalPages = pdf.getNumberOfPages();
        int maxScan = Math.min(20, totalPages);

        int startPage = -1;

        for (int i = 1; i <= maxScan; i++) {
            // Get all lines on the current page (with layout info)
            List<TextLine> lines = extractLinesWithLayout(pdf, i);

            // 1. Check for "Contents" keyword
            boolean hasKeyword = false;
            for (TextLine l : lines) {
                String t = l.getText().replaceAll("\\s+", "").toLowerCase();
                if (t.contains("目录") || t.contains("contents")) hasKeyword = true;
            }

            // 2. Core: Use "Smart Scoring" to judge if it's a TOC page
            // As long as the score is high enough, even without keywords (continuation pages), it counts.
            int score = scorePageAsToc(lines);

            if (startPage == -1) {
                // Finding the first page: Must have keyword + at least some format score
                if (hasKeyword && score >= 1) {
                    startPage = i;
                    tocPages.add(i);
                }
            } else {
                // Finding continuation pages: No keyword needed, just high format score
                // Continuity check: Once score drops significantly, assume body text starts
                if (score >= 3) { // Continuation pages are usually dense, so higher threshold
                    tocPages.add(i);
                } else {
                    break;
                }
            }
        }
        return tocPages;
    }

    /**
     * Helper: Extract page lines with layout (reuses StyledTextExtractor)
     */
    public List<TextLine> extractLinesWithLayout(PdfDocument pdf, int pageNum) {
        StyledTextExtractor extractor = new StyledTextExtractor();
        try {
            new PdfCanvasProcessor(extractor).processPageContent(pdf.getPage(pageNum));
            extractor.finish();
        } catch (Exception e) {
            // Catch font parsing exceptions (e.g. NullPointerException in PdfType0Font)
            System.err.println("Warning: Failed to extract layout from page " + pageNum + ": " + e.getMessage());
        }
        return extractor.getLines();
    }

    /**
     * Core Scoring Logic: Supports "Single Line" and "Multi-line" TOC formats.
     */
    private int scorePageAsToc(List<TextLine> lines) {
        int matchCount = 0;
        for (int i = 0; i < lines.size(); i++) {
            TextLine curr = lines.get(i);
            String currText = curr.getText().trim();

            // Case A: Standard single line "Title.......1"
            if (currText.matches("^.+?[…\\.\\s\\u2000-\\u200B]+\\d+$")) { // Added Unicode space support
                matchCount++;
                continue;
            }

            // Case B: Multi-line mode (Line i = Title, Line i+1 = Page)
            if (i + 1 < lines.size()) {
                TextLine next = lines.get(i + 1);
                String nextText = next.getText().trim();

                // If next line is pure number
                if (nextText.matches("^\\d+$")) {
                    // And current line looks like a title (not pure number, reasonable length)
                    if (!currText.matches("^\\d+$") && currText.length() > 2) {
                        // Y-coordinate check: lines shouldn't be too far apart
                        if (Math.abs(curr.getY() - next.getY()) < 20) {
                            matchCount++;
                            i++; // Skip next line as it's paired
                        }
                    }
                }
            }
        }
        return matchCount;
    }

    /**
     * Helper: Count how many lines in a page text look like "Title......Page"
     */
    public int countTocLikeLines(String pageText) {
        int count = 0;
        String[] lines = pageText.split("\n");
        // Loose match: ends with number, some gap in between
        Pattern p = Pattern.compile(".+[…\\.\\s]{2,}\\d+$");

        for (String line : lines) {
            // Exclude pure page number lines
            if (line.trim().matches("^\\d+$")) continue;

            if (p.matcher(line.trim()).find()) {
                count++;
            } else {
                // Backup match: "1.1 Title 5" format without dots
                // Only if ends with number and has reasonable length
                if (line.trim().matches(".*\\s\\d+$") && line.length() > 5) {
                    count++;
                }
            }
        }
        return count;
    }
}

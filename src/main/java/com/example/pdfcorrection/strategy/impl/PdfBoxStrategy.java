package com.example.pdfcorrection.strategy.impl;

import com.example.pdfcorrection.model.RawToc;
import com.example.pdfcorrection.model.TocItem;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PdfBoxStrategy {

    public List<TocItem> extract(MultipartFile file) throws IOException {
        List<TocItem> items = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int maxScan = Math.min(20, doc.getNumberOfPages());
            List<String> tocLines = new ArrayList<>();
            int tocStartPage = -1;

            // 1. Find TOC pages
            for (int i = 1; i <= maxScan; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(doc);

                if (text.contains("目录") || text.toLowerCase().contains("contents")) {
                    tocStartPage = i;
                    tocLines.addAll(Arrays.asList(text.split("\\r?\\n")));
                    // Try to read subsequent pages
                    for (int j = i + 1; j <= maxScan; j++) {
                        stripper.setStartPage(j);
                        stripper.setEndPage(j);
                        String nextText = stripper.getText(doc);
                        // Simple check: if it contains many "......number" lines, consider it a continuation
                        if (countTocLikeLines(nextText) > 3) {
                            tocLines.addAll(Arrays.asList(nextText.split("\\r?\\n")));
                        } else {
                            break;
                        }
                    }
                    break;
                }
            }

            if (tocLines.isEmpty()) return items;

            // 2. Parse lines
            List<RawToc> raws = new ArrayList<>();
            for (String line : tocLines) {
                line = line.trim();
                // Match "Title......Page"
                Matcher m1 = Pattern.compile("^(.*?)(?:\\.{2,}|…|—|\\s{2,})(\\d+)$").matcher(line);
                if (m1.find()) {
                    raws.add(new RawToc(m1.group(1).trim(), Integer.parseInt(m1.group(2))));
                    continue;
                }

                // Match "1. Title 5" format
                if (line.matches("^\\d+\\..+\\s+\\d+$")) {
                    String[] parts = line.split("\\s+");
                    String pageStr = parts[parts.length - 1];
                    if (pageStr.matches("\\d+")) {
                        String title = line.substring(0, line.lastIndexOf(pageStr)).trim();
                        raws.add(new RawToc(title, Integer.parseInt(pageStr)));
                    }
                }
            }

            // 3. Calculate offset
            int offset = 0;
            if (!raws.isEmpty()) {
                offset = calculatePageOffset(doc, raws) + 1;  // pdf page starts at 0, book page starts at 1
            }

            for (RawToc r : raws) {
                int physicalPage = r.getLogicalPage() + offset;
                if (physicalPage > 0 && physicalPage <= doc.getNumberOfPages()) {
                    TocItem item = new TocItem();
                    item.setTitle(r.getTitle());
                    item.setPage(physicalPage);
                    item.setLevel(1); // Default to 1
                    items.add(item);
                }
            }
        }
        return items;
    }

    private int countTocLikeLines(String text) {
        int count = 0;
        for (String line : text.split("\\n")) {
            if (line.trim().matches(".*\\d+$") && line.length() > 5) count++;
        }
        return count;
    }

    private int calculatePageOffset(PDDocument doc, List<RawToc> raws) {
        Map<Integer, Integer> votes = new HashMap<>();

        try {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 0; i < Math.min(raws.size(), 3); i++) {
                RawToc item = raws.get(i);
                for (int k = -20; k <= 20; k++) {
                    int guess = item.getLogicalPage() + k;
                    if (guess < 1 || guess > doc.getNumberOfPages()) continue;

                    stripper.setStartPage(guess);
                    stripper.setEndPage(guess);
                    String content = stripper.getText(doc);

                    if (content.replaceAll("\\s", "").contains(item.getTitle().replaceAll("\\s", ""))) {
                        votes.merge(k, 1, Integer::sum);
                    }
                }
            }
        } catch (IOException e) {
            return 0;
        }

        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);
    }
}

package com.example.pdfcorrection.service;

import com.example.pdfcorrection.model.TocItem;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfOutline;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.navigation.PdfExplicitDestination;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PdfBookmarkWriterService {

    public byte[] addBookmarksToOriginalPdf(byte[] pdfBytes, List<TocItem> tocItems) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdfBytes)), new PdfWriter(out))) {
            // Clear existing bookmarks (optional)
            pdf.getCatalog().setLang(null);

            PdfOutline root = pdf.getOutlines(false);

            // Maintain level stack: key=level, value=Outline
            Map<Integer, PdfOutline> levelMap = new HashMap<>();
            levelMap.put(0, root); // Level 0 is root

            for (TocItem i : tocItems) {
                // Find parent: if current is Level 2, parent should be Level 1. If Level 1 not found, attach to Root.
                PdfOutline parent = root;
                for (int l = i.getLevel() - 1; l >= 0; l--) {
                    if (levelMap.containsKey(l)) {
                        parent = levelMap.get(l);
                        break;
                    }
                }

                PdfOutline current = parent.addOutline(i.getTitle());
                // Use FitH (Fit Horizontal) mode, position at top of page
                current.addDestination(PdfExplicitDestination.createFitH(pdf.getPage(i.getPage()), pdf.getPage(i.getPage()).getPageSize().getTop()));

                levelMap.put(i.getLevel(), current);
                // Clear deeper levels cache to prevent hierarchy confusion
                levelMap.keySet().removeIf(k -> k > i.getLevel());
            }
        }
        return out.toByteArray();
    }
}

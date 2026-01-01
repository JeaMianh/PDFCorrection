package com.example.pdfcorrection.util;

import com.example.pdfcorrection.model.TextLine;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;

import java.util.*;

/**
 * Extracts text with font size, boldness, and Y-coordinate information.
 * Critical for distinguishing TOC entries from body text.
 */
public class StyledTextExtractor implements IEventListener {
    private final List<TextLine> lines = new ArrayList<>();
    private final Map<String, Integer> fontSizeHistogram = new HashMap<>();
    private float currentY = -1;
    private StringBuilder currentLineText = new StringBuilder();
    private float currentMaxFontSize = 0;
    private boolean currentLineBold = false;

    @Override
    public void eventOccurred(IEventData data, EventType type) {
        if (type == EventType.RENDER_TEXT) {
            TextRenderInfo info = (TextRenderInfo) data;
            String text = info.getText();
            if (text == null || text.trim().isEmpty()) return;

            float y = info.getBaseline().getStartPoint().get(1);
            float fontSize = info.getFontSize();
            // Simple bold check: check if font name contains "Bold" or line width
            boolean isBold = false;
            if (info.getFont() != null && info.getFont().getFontProgram() != null) {
                 String fontNames = info.getFont().getFontProgram().getFontNames().toString().toLowerCase();
                 isBold = fontNames.contains("bold");
            }

            // Font size histogram (for later body text size determination)
            String fontKey = Math.round(fontSize) + "-" + isBold;
            fontSizeHistogram.merge(fontKey, text.length(), Integer::sum);

            // New line detection (Y coordinate change > threshold)
            if (currentY != -1 && Math.abs(y - currentY) > 2.0) {
                flushLine();
            }

            if (currentLineText.length() == 0) {
                currentY = y;
            }
            currentLineText.append(text);
            currentMaxFontSize = Math.max(currentMaxFontSize, fontSize);
            if (isBold) currentLineBold = true;
        }
    }

    private void flushLine() {
        if (currentLineText.length() > 0) {
            lines.add(new TextLine(currentLineText.toString(), currentMaxFontSize, currentLineBold, currentY));
            currentLineText.setLength(0);
            currentMaxFontSize = 0;
            currentLineBold = false;
        }
    }

    public void finish() { flushLine(); }

    @Override
    public Set<EventType> getSupportedEvents() { return Collections.singleton(EventType.RENDER_TEXT); }

    public List<TextLine> getLines() { return lines; }
    public Map<String, Integer> getFontSizeHistogram() { return fontSizeHistogram; }
}

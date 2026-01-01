package com.example.pdfcorrection.ocr;

import java.awt.image.BufferedImage;
import java.util.List;

public interface OcrEngine {
    String doOCR(BufferedImage image) throws Exception;
    
    default String doOCR(BufferedImage image, String promptOverride) throws Exception {
        return doOCR(image);
    }
    
    default String doOCR(List<BufferedImage> images, String promptOverride) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (BufferedImage img : images) {
            sb.append(doOCR(img, promptOverride)).append("\n");
        }
        return sb.toString();
    }
}

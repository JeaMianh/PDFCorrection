package com.example.pdfcorrection.ocr.impl;

import com.example.pdfcorrection.ocr.OcrEngine;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import java.awt.image.BufferedImage;
import java.io.File;

public class TesseractEngine implements OcrEngine {
    private final Tesseract tesseract;

    public TesseractEngine() {
        this.tesseract = new Tesseract();
        String datapath = System.getenv("TESSDATA_PREFIX");
        if (datapath == null) {
            if (new File("tessdata").exists()) {
                datapath = new File("tessdata").getAbsolutePath();
            } else if (new File("src/main/resources/tessdata").exists()) {
                datapath = new File("src/main/resources/tessdata").getAbsolutePath();
            } else {
                datapath = "tessdata";
            }
        }
        this.tesseract.setDatapath(datapath);
        this.tesseract.setLanguage("chi_sim+eng");
    }

    @Override
    public String doOCR(BufferedImage image) throws TesseractException {
        return tesseract.doOCR(image);
    }
}

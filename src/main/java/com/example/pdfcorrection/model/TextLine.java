package com.example.pdfcorrection.model;

public class TextLine {
    private String text;
    private float fontSize;
    private boolean isBold;
    private float y; // Y坐标，用于排序

    public TextLine() {}

    public TextLine(String text, float fontSize, boolean isBold, float y) {
        this.text = text;
        this.fontSize = fontSize;
        this.isBold = isBold;
        this.y = y;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
    }

    public boolean isBold() {
        return isBold;
    }

    public void setBold(boolean bold) {
        isBold = bold;
    }

    public float getY() {
        return y;
    }

    public void setY(float y) {
        this.y = y;
    }
}

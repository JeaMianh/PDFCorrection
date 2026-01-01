package com.example.pdfcorrection.model;

public class RawToc {
    private String title;
    private int logicalPage;
    private int physicalPage = -1; // 新增：实际物理页码
    private boolean hasExplicitPage = false;
    private int level = 1; // 新增：层级
    
    public RawToc() {}

    public RawToc(String t, int p) { 
        this.title = t; 
        this.logicalPage = p; 
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getLogicalPage() {
        return logicalPage;
    }

    public void setLogicalPage(int logicalPage) {
        this.logicalPage = logicalPage;
    }

    public int getPhysicalPage() {
        return physicalPage;
    }

    public void setPhysicalPage(int physicalPage) {
        this.physicalPage = physicalPage;
    }

    public boolean isHasExplicitPage() {
        return hasExplicitPage;
    }

    public void setHasExplicitPage(boolean hasExplicitPage) {
        this.hasExplicitPage = hasExplicitPage;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }
}

package com.example.pdfcorrection.model;

public class TocItem {
    private String title;
    private int page;
    private int level = 1;

    public TocItem() {}

    public TocItem(String title, int page, int level) {
        this.title = title;
        this.page = page;
        this.level = level;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }
}

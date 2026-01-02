package com.example.pdfcorrection.util;

import com.example.pdfcorrection.model.RawToc;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TocTextParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Tries to parse a JSON string into a list of RawToc objects.
     * Includes logic for cleaning OCR typos in JSON keys and inferring levels.
     */
    public static List<RawToc> tryParseJson(String text) {
        try {
            // 1. Pre-cleaning: remove Markdown code blocks
            String json = text.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7);
            } else if (json.startsWith("```")) {
                json = json.substring(3);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();

            // 2. Clean common OCR typos in keys
            json = cleanOcrTypos(json);

            // 3. Parse
            List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            List<RawToc> result = new ArrayList<>();

            for (Map<String, Object> map : list) {
                String title = (String) map.get("title");
                Object pageObj = map.get("page");

                    if (title != null) {
                        int page = 0;
                        if (pageObj != null) {
                            if (pageObj instanceof Integer) {
                                page = (Integer) pageObj;
                            } else if (pageObj instanceof String) {
                                String pageStr = ((String) pageObj).trim();
                                // Handle "123" or "123-124" (take first)
                                if (pageStr.contains("-")) {
                                    pageStr = pageStr.split("-")[0].trim();
                                }
                                if (!pageStr.isEmpty() && pageStr.matches("\\d+")) {
                                    try {
                                        page = Integer.parseInt(pageStr);
                                    } catch (NumberFormatException e) {
                                        // ignore
                                    }
                                }
                            }
                        }

                        // Fallback: Extract page from title if not found in "page" field
                        if (page <= 0) {
                            // Match patterns like "Title ... 123", "Title 123", "Title...123"
                            // We look for a number at the end of the string, preceded by dots, dashes, or spaces.
                            // We must be careful not to match "Chapter 1" as page 1.
                            // Usually page numbers are > 0.
                            
                            // Regex: 
                            // 1. Separator: ... or .. or . or — or - or spaces
                            // 2. Number: \d+
                            // 3. End of line
                            Matcher m = Pattern.compile("[…\\._—\\s]{2,}(\\d+)$").matcher(title);
                            if (m.find()) {
                                try {
                                    page = Integer.parseInt(m.group(1));
                                    // Clean title by removing the page number part
                                    title = title.substring(0, m.start()).trim();
                                } catch (NumberFormatException e) {}
                            } else {
                                // Try simpler pattern: space + number at end, but only if number is not part of the title text (heuristic)
                                // e.g. "Chapter 1" -> No (1 is part of title)
                                // "Introduction 5" -> Maybe
                                // "Section 1.1 10" -> Yes (10 is page)
                                Matcher m2 = Pattern.compile("\\s+(\\d+)$").matcher(title);
                                if (m2.find()) {
                                    String numStr = m2.group(1);
                                    // Heuristic: If title ends with digit, check if it looks like a chapter number
                                    boolean looksLikeChapterNum = title.matches(".*(Chapter|第.+章|Section|Part)\\s*" + numStr + "$");
                                    if (!looksLikeChapterNum) {
                                        try {
                                            page = Integer.parseInt(numStr);
                                            title = title.substring(0, m2.start()).trim();
                                        } catch (NumberFormatException e) {}
                                    }
                                }
                            }
                        }

                        RawToc raw = new RawToc(title, page);

                    // Parse Level
                    int level = 1;
                    if (map.containsKey("level")) {
                        Object levelObj = map.get("level");
                        if (levelObj != null) {
                            try {
                                level = Integer.parseInt(String.valueOf(levelObj).trim());
                            } catch (Exception e) {
                                // ignore
                            }
                        }
                    }

                    // Regex Verification for Level
                    int regexLevel = inferLevel(title);
                    if (regexLevel != 2) { // 2 is the default fallback in inferLevel
                        raw.setLevel(regexLevel);
                    } else {
                        // Trust AI if regex is inconclusive
                        raw.setLevel(level);
                    }

                    result.add(raw);
                }
            }

            // Global Level Correction
            // If "Part" (Level 1) and "Chapter" (Level 1) coexist, downgrade "Chapter" to Level 2.
            boolean hasPart = result.stream().anyMatch(r -> r.getTitle().matches("^第[一二三四五六七八九十百]+[部编].*"));
            if (hasPart) {
                for (RawToc r : result) {
                    if (r.getTitle().matches("^第[一二三四五六七八九十百]+[章].*")) {
                        r.setLevel(2);
                    }
                    // If Chapter becomes Level 2, Section becomes Level 3
                    else if (r.getTitle().matches("^第[一二三四五六七八九十百]+[节].*") || r.getTitle().matches("^\\d+\\.\\d+.*")) {
                        r.setLevel(3);
                    }
                }
            }

            return result;

        } catch (Exception e) {
            System.err.println("JSON Parse Error: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    public static List<RawToc> tryParseJsonLenient(String text) {
        try {
            int start = text.indexOf('[');
            int lastBrace = text.lastIndexOf('}');
            if (start >= 0 && lastBrace > start) {
                // Try to close the JSON array
                String jsonCandidate = text.substring(start, lastBrace + 1) + "]";
                return tryParseJson(jsonCandidate);
            }
        } catch (Exception e) {
            // ignore
        }
        return new ArrayList<>();
    }

    public static String extractLastTitleFromText(String text) {
        Pattern p = Pattern.compile("\"title\"\\s*:\\s*\"(.*?)\"");
        Matcher m = p.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private static String cleanOcrTypos(String json) {
        // Common OCR errors for "title"
        json = json.replaceAll("\"t[il1]tle\"\\s*:", "\"title\":");
        json = json.replaceAll("\"tit[l1]e\"\\s*:", "\"title\":");
        json = json.replaceAll("\"t[il1]t[l1]e\"\\s*:", "\"title\":");
        // Common OCR errors for "page"
        json = json.replaceAll("\"p[a@]ge\"\\s*:", "\"page\":");
        json = json.replaceAll("\"pag[e3]\"\\s*:", "\"page\":");
        return json;
    }

    public static String cleanContentOcrTypos(String line) {
        // 1. Fix common typos in "Chapter X"
        if (line.contains("第") && line.contains("章")) {
            line = line.replaceAll("第\\s*关\\s*章", "第六章");
            line = line.replaceAll("第\\s*作\\s*章", "第八章");
            line = line.replaceAll("第\\s*十\\s*_\\s*章", "第十一章");
            line = line.replaceAll("第\\s*一\\s*章", "第一章"); // Normalize spaces
        }
        if (line.startsWith("荐")) {
            line = "第" + line.substring(1);
        }
        return line;
    }

    public static int countTocLikeLines(String text) {
        int count = 0;
        for (String line : text.split("\\n")) {
            if (line.trim().matches(".*\\d+$") && line.length() > 5) count++;
        }
        return count;
    }

    public static int countChapterPatterns(String text) {
        int count = 0;
        Pattern p = Pattern.compile("^(第.+章|Chapter|\\d+\\.|[一二三四五六七八九十]+、).*");
        for (String line : text.split("\\n")) {
            if (p.matcher(line.trim()).find()) count++;
        }
        return count;
    }

    public static boolean isGarbageLine(String line) {
        // Filter out lines that are mostly garbage
        if (line.length() > 10) {
            boolean hasChinese = line.matches(".*[\\u4e00-\\u9fa5].*");
            boolean hasEnglishKeyword = line.toLowerCase().matches(".*(chapter|section|part|index|content).*");

            if (!hasChinese && !hasEnglishKeyword) {
                // Calculate ratio of non-alphanumeric characters
                long symbolCount = line.chars().filter(ch -> !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch)).count();
                if ((double) symbolCount / line.length() > 0.5) {
                    return true; // > 50% symbols
                }
            }
        }
        return false;
    }

    /**
     * Static level inference tool.
     */
    public static int inferLevel(String title) {
        String t = title.trim();

        // Level 1: Part/Volume (Highest)
        if (t.matches("^第[一二三四五六七八九十百]+[部编].*")) {
            return 1;
        }

        // Level 1 (Correction): Chapter is usually Level 1 unless there is a Part.
        // Here we map Chapter to 1 initially.
        if (t.matches("^第[一二三四五六七八九十百]+[章].*")) {
            return 1;
        }

        // Level 2: Section, 1.1, (一)
        if (t.matches("^第[一二三四五六七八九十百]+[节].*") || t.matches("^\\d+\\.\\d+.*")) {
            return 2;
        }

        // Level 3: X.X.X (e.g. 1.1.1)
        if (t.matches("^\\d+\\.\\d+\\.\\d+.*")) {
            return 3;
        }

        // Other cases
        if (t.matches("^[一二三四五六七八九十百]+[、\\s].*")) return 2;
        if (t.matches("^[(（][一二三四五六七八九十百]+[)）].*")) return 3;

        // Fallback: 2 (Uncertain)
        return 2;
    }
}

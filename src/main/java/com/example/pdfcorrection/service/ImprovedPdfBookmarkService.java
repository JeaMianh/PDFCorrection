package com.example.pdfcorrection.service;

import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;
import com.itextpdf.kernel.pdf.navigation.PdfExplicitDestination;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 改进版 PDF 书签服务
 * 核心思路：视觉语义分析（Visual Semantic Analysis）替代纯正则
 */
@Service
public class ImprovedPdfBookmarkService {

    /* ======================= 对外 API (保持不变) ======================= */

    public byte[] processAndAddBookmarks(MultipartFile file) throws IOException {
        List<TocItem> tocItems = extractTocItems(file);
        // 如果没有提取到任何书签，直接返回原文件，避免破坏
        if (tocItems.isEmpty()) {
            return new ByteArrayResource(file.getBytes()).getByteArray();
        }
        return addBookmarksToOriginalPdf(file.getBytes(), tocItems);
    }

    public List<TocItem> extractTocItems(MultipartFile file) throws IOException {
        try (PdfDocument pdf = new PdfDocument(new PdfReader(file.getInputStream()))) {
            // 1. 预扫描：寻找潜在的目录页范围
            List<Integer> tocCandidatePages = findTocPages(pdf);

            List<TocItem> items = new ArrayList<>();

            // 2. 策略A：如果有目录页，优先解析目录页 (高精度)
            if (!tocCandidatePages.isEmpty()) {
                items = new StrategyExplicitToc().extract(pdf, tocCandidatePages);
            }

            // 3. 策略B：如果没有目录页，或目录页解析失败，使用全文版式分析 (WPS兜底方案)
            if (items.isEmpty()) {
                items = new StrategyLayoutStructure().extract(pdf);
            }

            return items;
        }
    }

    /* ======================= 1. 改进后的目录页定位逻辑 ======================= */

    /**
     * 核心改进：引入“密度检测”来识别多页目录
     */
    private List<Integer> findTocPages(PdfDocument pdf) {
        List<Integer> tocPages = new ArrayList<>();
        int totalPages = pdf.getNumberOfPages();
        int maxScan = Math.min(20, totalPages);

        int startPage = -1;

        for (int i = 1; i <= maxScan; i++) {
            // 获取当前页所有行（带布局信息）
            List<TextLine> lines = extractLinesWithLayout(pdf, i);

            // 1. 检查是否有“目录”关键字
            boolean hasKeyword = false;
            for (TextLine l : lines) {
                String t = l.text.replaceAll("\\s+", "").toLowerCase();
                if (t.contains("目录") || t.contains("contents")) hasKeyword = true;
            }

            // 2. 核心：使用“智能评分”来判断是否是目录页
            // 只要得分够高，即使没有关键字（续页），也算目录
            int score = scorePageAsToc(lines);

            if (startPage == -1) {
                // 寻找第一页：必须有关键字 + 至少一定的格式分
                if (hasKeyword && score >= 1) {
                    startPage = i;
                    tocPages.add(i);
                }
            } else {
                // 寻找续页：不需要关键字，只要格式分够高
                // 连续性检查：一旦分数断崖下跌，说明进入正文
                if (score >= 3) { // 续页通常比较密集，阈值设高一点
                    tocPages.add(i);
                } else {
                    break;
                }
            }
        }
        return tocPages;
    }

    /**
     * 辅助方法：提取页面行信息（复用 StyledTextExtractor）
     */
    private List<TextLine> extractLinesWithLayout(PdfDocument pdf, int pageNum) {
        StyledTextExtractor extractor = new StyledTextExtractor();
        new PdfCanvasProcessor(extractor).processPageContent(pdf.getPage(pageNum));
        extractor.finish();
        return extractor.getLines();
    }

    /**
     * 核心评分逻辑：支持“同行”和“跨行”两种目录格式
     */
    private int scorePageAsToc(List<TextLine> lines) {
        int matchCount = 0;
        for (int i = 0; i < lines.size(); i++) {
            TextLine curr = lines.get(i);
            String currText = curr.text.trim();

            // 情况A: 标准单行模式 "标题.......1"
            if (currText.matches("^.+?[…\\.\\s\\u2000-\\u200B]+\\d+$")) { // 增加 Unicode 空格支持
                matchCount++;
                continue;
            }

            // 情况B: 跨行模式 (Line i = 标题, Line i+1 = 页码)
            // 这种就是你遇到的情况：标题和页码被拆成了两行
            if (i + 1 < lines.size()) {
                TextLine next = lines.get(i + 1);
                String nextText = next.text.trim();

                // 如果下一行是纯数字
                if (nextText.matches("^\\d+$")) {
                    // 且当前行看起来像标题（不是纯数字，长度适中）
                    if (!currText.matches("^\\d+$") && currText.length() > 2) {
                        // 这里可以加一个Y坐标判断：两行距离不能太远
                        if (Math.abs(curr.y - next.y) < 20) {
                            matchCount++;
                            i++; // 跳过下一行，因为已经配对了
                        }
                    }
                }
            }
        }
        return matchCount;
    }

    /**
     * 辅助方法：计算一个页面中有多少行符合 "标题......页码" 的特征
     */
    private int countTocLikeLines(String pageText) {
        int count = 0;
        String[] lines = pageText.split("\n");
        // 宽松匹配：只要结尾是数字，且中间有些许间隔
        Pattern p = Pattern.compile(".+[…\\.\\s]{2,}\\d+$");

        for (String line : lines) {
            // 排除单纯的页码行（只有数字）
            if (line.trim().matches("^\\d+$")) continue;

            if (p.matcher(line.trim()).find()) {
                count++;
            } else {
                // 备用匹配：也就是 "1.1 标题 5" 这种没有点的格式
                // 只有当行尾是数字，且长度适中时才算
                if (line.trim().matches(".*\\s\\d+$") && line.length() > 5) {
                    count++;
                }
            }
        }
        return count;
    }

    /* ======================= 核心工具：字体与位置感知提取器 ======================= */

    /**
     * 不仅仅提取文本，还提取字体大小、是否加粗、Y坐标
     * 这是区别于普通方案的关键
     */
    static class StyledTextExtractor implements IEventListener {
        private final List<TextLine> lines = new ArrayList<>();
        private final Map<String, Integer> fontSizeHistogram = new HashMap<>();
        private float currentY = -1;
        private StringBuilder currentLineText = new StringBuilder();
        private float currentMaxFontSize = 0;
        private boolean currentLineBold = false;

        @Override
        public void eventOccurred(com.itextpdf.kernel.pdf.canvas.parser.data.IEventData data, EventType type) {
            if (type == EventType.RENDER_TEXT) {
                TextRenderInfo info = (TextRenderInfo) data;
                String text = info.getText();
                if (text == null || text.trim().isEmpty()) return;

                float y = info.getBaseline().getStartPoint().get(1);
                float fontSize = info.getFontSize();
                // 简单判定加粗：看字体名是否含Bold或线宽
                boolean isBold = info.getFont().getFontProgram().getFontNames().toString().toLowerCase().contains("bold");

                // 统计字体直方图（用于后续判断正文大小）
                String fontKey = Math.round(fontSize) + "-" + isBold;
                fontSizeHistogram.merge(fontKey, text.length(), Integer::sum);

                // 换行判定（Y坐标变化超过阈值）
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

    static class TextLine {
        String text;
        float fontSize;
        boolean isBold;
        float y; // Y坐标，用于排序

        public TextLine(String text, float fontSize, boolean isBold, float y) {
            this.text = text;
            this.fontSize = fontSize;
            this.isBold = isBold;
            this.y = y;
        }
    }

    /* ======================= 策略A：基于目录页的精确提取 ======================= */

    static class StrategyExplicitToc {

        // 匹配带页码的行 (支持同行或跨行)
        private static final String GAP_REGEX = "(?:\\.{2,}|…|—|\\s{2,}|[\\u2000-\\u200B]+)";
        // 匹配无页码的层级标志：如 "一、", "第一章", "附录" 等
        private static final Pattern SUB_TITLE_PATTERN = Pattern.compile("^([一二三四五六七八九十百]+[、]|第[一二三四五六七八九十百]+[部分|章|节]|附录).*");

        public List<TocItem> extract(PdfDocument pdf, List<Integer> pages) {
            List<RawToc> raws = new ArrayList<>();

            for (Integer p : pages) {
                StyledTextExtractor extractor = new StyledTextExtractor();
                new PdfCanvasProcessor(extractor).processPageContent(pdf.getPage(p));
                extractor.finish();

                List<TextLine> lines = extractor.getLines();

                for (int i = 0; i < lines.size(); i++) {
                    TextLine curr = lines.get(i);
                    String text = curr.text.trim();
                    if (text.isEmpty()) continue;

                    // 1. 尝试匹配带页码的行 (同行匹配)
                    String normText = text.replaceAll("[\\u2000-\\u200B]", "  ");
                    Matcher m = Pattern.compile("^(.*?)" + "(?:\\.{2,}|…|—|\\s{2,})" + "(\\d+)$").matcher(normText);

                    if (m.find()) {
                        raws.add(new RawToc(m.group(1).trim(), Integer.parseInt(m.group(2)), curr.fontSize, true));
                    }
                    // 2. 尝试跨行匹配 (当前行标题，下一行纯数字页码)
                    else if (i + 1 < lines.size() && lines.get(i+1).text.trim().matches("^\\d+$")) {
                        raws.add(new RawToc(text, Integer.parseInt(lines.get(i+1).text.trim()), curr.fontSize, true));
                        i++; // 跳过页码行
                    }
                    // 3. 【新增】匹配无页码的副标题 (如：一、教学管理)
                    else if (SUB_TITLE_PATTERN.matcher(text).matches()) {
                        // 先暂时记录，页码设为-1，后续填充
                        raws.add(new RawToc(text, -1, curr.fontSize, false));
                    }
                }
            }

            if (raws.isEmpty()) return Collections.emptyList();

            // 4. 页码回填逻辑：无页码的标题承接后一个条目的页码
            for (int i = 0; i < raws.size(); i++) {
                if (raws.get(i).logicalPage == -1) {
                    // 向后寻找最近的一个有效页码
                    for (int j = i + 1; j < raws.size(); j++) {
                        if (raws.get(j).logicalPage != -1) {
                            raws.get(i).logicalPage = raws.get(j).logicalPage;
                            break;
                        }
                    }
                    // 如果后面全没页码（极少见），设为1
                    if (raws.get(i).logicalPage == -1) raws.get(i).logicalPage = 1;
                }
            }

            // 校准与转换 (逻辑同前)
            int offset = calculatePageOffset(pdf, raws.stream().filter(r -> r.hasExplicitPage).collect(Collectors.toList()));

            List<TocItem> result = new ArrayList<>();
            for (RawToc r : raws) {
                int physicalPage = r.logicalPage + offset;
                if (physicalPage > 0 && physicalPage <= pdf.getNumberOfPages()) {
                    TocItem item = new TocItem();
                    item.title = r.title;
                    item.page = physicalPage;
                    item.level = inferLevel(r.title);
                    result.add(item);
                }
            }
            return result;
        }

        // 智能校准：在逻辑页码对应的物理页附近寻找标题，计算偏差
        private int calculatePageOffset(PdfDocument pdf, List<RawToc> raws) {
            Map<Integer, Integer> offsetVotes = new HashMap<>();

            // 采样前5个和后5个条目进行校验
            List<RawToc> samples = new ArrayList<>();
            samples.addAll(raws.subList(0, Math.min(raws.size(), 5)));
            if (raws.size() > 10) samples.addAll(raws.subList(raws.size() - 5, raws.size()));

            for (RawToc item : samples) {
                // 假设 offset 在 -20 到 +20 之间（通常也是前言造成的）
                // 默认猜测：目录最后一页的下一页是正文第1页
                // 这里采用暴力扫描法：检查 logicalPage + k 页面中是否包含 title
                for (int k = -20; k <= 30; k++) {
                    int guessPhys = item.logicalPage + k;
                    if (guessPhys < 1 || guessPhys > pdf.getNumberOfPages()) continue;

                    String pageContent = com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
                            .getTextFromPage(pdf.getPage(guessPhys));

                    // 宽松匹配：去掉空格和标点
                    if (matchTitleInContent(item.title, pageContent)) {
                        offsetVotes.merge(k, 1, Integer::sum);
                    }
                }
            }

            // 返回票数最多的偏移量，如果没有，默认0
            return offsetVotes.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(0);
        }

        private boolean matchTitleInContent(String title, String content) {
            String cleanTitle = title.replaceAll("[\\s\\d.]", "");
            if (cleanTitle.length() < 2) return false;
            return content.replaceAll("\\s", "").contains(cleanTitle);
        }

        /**
         * 增强层级判断逻辑
         * 优先级： 中文大写(一、) > 括号中文((一)) > 数字点(1.) > 独立数字
         */
        private int inferLevel(String title) {
            String t = title.trim();

            // Level 1: 一、 或 第一章
            if (t.matches("^[一二三四五六七八九十百]+[、\\s].*") || t.matches("^第[一二三四五六七八九十百]+[章节部].*")) {
                return 1;
            }

            // Level 2: （一）、（1）
            if (t.matches("^[(（][一二三四五六七八九十百\\d]+[)）].*")) {
                return 2;
            }

            // Level 3: 1.1 或 1. 使用说明 (数字后面带点或空格)
            if (t.matches("^\\d+\\.\\d+.*") || t.matches("^\\d+[\\.\\s].*")) {
                return 3;
            }

            // Level 4: (1) 这种小括号数字
            if (t.matches("^[(（]\\d+[)）].*")) {
                return 4;
            }

            // 兜底逻辑：如果没有明显前缀，根据缩进判断（可选）或默认为较低层级
            return 2;
        }

        static class RawToc {
            String title;
            int logicalPage;
            float fontSize;
            boolean hasExplicitPage; // 标记是否是直接带页码的行

            RawToc(String t, int p, float f, boolean hasPage) {
                title = t;
                logicalPage = p;
                fontSize = f;
                hasExplicitPage = hasPage;
            }
        }
    }

    /* ======================= 策略B：基于全文版式结构的提取 ======================= */

    static class StrategyLayoutStructure {

        public List<TocItem> extract(PdfDocument pdf) {
            // 1. 采样分析：计算正文字体大小 (Mode Font Size)
            float bodyFontSize = analyzeBodyFont(pdf);

            List<Candidate> candidates = new ArrayList<>();
            int totalPages = pdf.getNumberOfPages();

            // 2. 全文扫描
            for (int p = 1; p <= totalPages; p++) {
                StyledTextExtractor extractor = new StyledTextExtractor();
                new PdfCanvasProcessor(extractor).processPageContent(pdf.getPage(p));
                extractor.finish();

                List<TextLine> lines = extractor.getLines();
                for (int i = 0; i < lines.size(); i++) {
                    TextLine line = lines.get(i);
                    // 过滤条件：
                    // A. 字体必须显著大于正文 ( > body + 1pt) 或者 加粗且等于正文
                    // B. 长度适中 (3 ~ 50 chars)
                    // C. 或者是标准的 "1. xxx" 格式
                    boolean isBig = line.fontSize > bodyFontSize + 1.0f;
                    boolean isBold = line.isBold && line.fontSize >= bodyFontSize;
                    boolean isPattern = line.text.trim().matches("^(第.+章|\\d+\\.|\\d+\\s+).*");

                    if ((isBig || (isBold && isPattern)) && line.text.length() < 60 && line.text.length() > 2) {
                        // 排除页眉页脚 (通过坐标判断，PDF坐标系原点在左下角，Top在PageHeight附近)
                        // 简单处理：假设页眉在最上面10%，页脚在最下面10%，这里暂不硬编码，依靠后续排序清洗
                        candidates.add(new Candidate(line.text.trim(), p, line.fontSize));
                    }
                }
            }

            // 3. 确定层级
            // 将候选标题按字体大小聚类
            List<Float> fontSizes = candidates.stream().map(c -> c.fontSize).distinct().sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            // 假设最大的字体是1级，次大是2级

            List<TocItem> result = new ArrayList<>();
            for (Candidate c : candidates) {
                int level = 1;
                if (fontSizes.indexOf(c.fontSize) > 0) level = 2; // 次大字体
                if (fontSizes.indexOf(c.fontSize) > 1) level = 3;

                TocItem item = new TocItem();
                item.title = c.title;
                item.page = c.page;
                item.level = level;
                result.add(item);
            }

            // 4. 清洗：去重 (同一页可能重复提取)、排序
            return cleanLayoutResult(result);
        }

        private float analyzeBodyFont(PdfDocument pdf) {
            Map<Float, Integer> freq = new HashMap<>();
            // 采样前10页
            for (int i = 1; i <= Math.min(10, pdf.getNumberOfPages()); i++) {
                StyledTextExtractor ex = new StyledTextExtractor();
                new PdfCanvasProcessor(ex).processPageContent(pdf.getPage(i));
                ex.finish();
                for (TextLine l : ex.getLines()) {
                    if (!l.text.trim().isEmpty())
                        freq.merge(l.fontSize, l.text.length(), Integer::sum);
                }
            }
            // 返回出现字数最多的字体大小
            return freq.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(12.0f);
        }

        private List<TocItem> cleanLayoutResult(List<TocItem> items) {
            List<TocItem> clean = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            for (TocItem i : items) {
                String key = i.page + "_" + i.title;
                if (!seen.contains(key)) {
                    // 简单的正则过滤垃圾字符
                    if (!i.title.matches("^\\d+$") && !i.title.contains("......")) {
                        clean.add(i);
                    }
                    seen.add(key);
                }
            }
            return clean;
        }

        static class Candidate {
            String title;
            int page;
            float fontSize;
            Candidate(String t, int p, float f) { title = t; page = p; fontSize = f; }
        }
    }

    /* ======================= PDF 写入工具 (复用原逻辑但优化资源) ======================= */

    private byte[] addBookmarksToOriginalPdf(byte[] pdfBytes, List<TocItem> tocItems) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdfBytes)), new PdfWriter(out))) {
            // 清除原有书签 (可选)
            pdf.getCatalog().setLang(null);

            PdfOutline root = pdf.getOutlines(false);

            // 维护层级栈：key=level, value=Outline
            Map<Integer, PdfOutline> levelMap = new HashMap<>();
            levelMap.put(0, root); // 0级即根节点

            for (TocItem i : tocItems) {
                // 找父节点：如果当前是 Level 2，父节点应该是 Level 1。如果没找到 Level 1，就挂在 Root 下
                PdfOutline parent = root;
                for (int l = i.level - 1; l >= 0; l--) {
                    if (levelMap.containsKey(l)) {
                        parent = levelMap.get(l);
                        break;
                    }
                }

                PdfOutline current = parent.addOutline(i.title);
                // 使用 FitH (Fit Horizontal) 模式，定位到页面顶部
                current.addDestination(PdfExplicitDestination.createFitH(pdf.getPage(i.page), pdf.getPage(i.page).getPageSize().getTop()));

                levelMap.put(i.level, current);
                // 清除更深层级的缓存，防止层级错乱
                levelMap.keySet().removeIf(k -> k > i.level);
            }
        }
        return out.toByteArray();
    }

    /* ======================= DTO ======================= */
    public static class TocItem {
        public String title;
        public int page;
        public int level = 1;
        // Getter Setter 省略，配合 Lombok 或手动生成
    }
}
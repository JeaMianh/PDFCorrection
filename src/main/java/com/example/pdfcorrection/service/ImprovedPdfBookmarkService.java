package com.example.pdfcorrection.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;
import com.itextpdf.kernel.pdf.navigation.PdfExplicitDestination;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 改进版 PDF 书签服务Identify the table of contents from this image. Return the result as a JSON array of objects with 'title' and 'page' fields. Example: [{\"title\": \"Chapter 1\", \"page\": 10}]. If this is not a table of contents page, return []. Output only the JSON.
 * 核心思路：视觉语义分析（Visual Semantic Analysis）替代纯正则
 */
@Service
public class ImprovedPdfBookmarkService {

    @Value("${pdf.ocr.provider:local}")
    private String ocrProvider;

    @Value("${pdf.ocr.api.api-key:}")
    private String apiKey;

    @Value("${pdf.ocr.api.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String apiBaseUrl;

    @Value("${pdf.ocr.api.model:qwen-vl-ocr-2025-11-20}")
    private String apiModel;


    // @Value("${pdf.ocr.prompt:请识别图片中的目录内容。将结果作为包含 'title' 和 'page' 字段的 JSON 对象数组返回。示例：[{'title': '第一章', 'page': 10}]。如果这不是目录页，请返回 []。仅输出 JSON 格式，不要包含 Markdown 标记（如 ```json）。也不必包括 title 或者 page 为空的项。}")
    @Value("${pdf.ocr.prompt:请识别图片中的目录内容。请严格按照图片中的顺序，输出一个扁平的 JSON 数组。每个元素包含 'title' (完整的章节标题，必须包含章节编号，如'1.1 绪论'，或'第一章 标题')、'page' (页码) 和 'level' (层级，整数，1表示一级标题，2表示二级标题，以此类推) 三个字段。\\n**关键要求**：\\n1. **完整性**：绝对不要遗漏章节编号！例如图片显示 '1.1.1 数据结构'，title 必须是 '1.1.1 数据结构'。\\n2. **层级判断** (综合判断)：\\n   - **语义编号** (优先)：'第一部/编' > '第一章' > '第一节'。例如：如果有'第一部'，则'第一部'是Level 1，'第一章'是Level 2。如果没有'部'，则'第一章'是Level 1。\\n   - **缩进**：在编号不明确时，缩进越深 level 越大。\\n   - **字体**：字号大或加粗的通常层级更高。\\n3. **页码**：如果某行没有页码，page 字段留空字符串。\\n不要输出 Markdown 标记，只输出 JSON。}")
    private String ocrPrompt;


    private final RestTemplate restTemplate = new RestTemplate();

    /* ======================= 对外 API (保持不变) ======================= */

    public byte[] processAndAddBookmarks(MultipartFile file) throws IOException {
        return processAndAddBookmarks(file, null);
    }

    public byte[] processAndAddBookmarks(MultipartFile file, List<TocItem> providedToc) throws IOException {
        List<TocItem> tocItems;
        if (providedToc != null && !providedToc.isEmpty()) {
            tocItems = providedToc;
        } else {
            tocItems = extractTocItems(file);
        }

        // 如果没有提取到任何书签，直接返回原文件，避免破坏
        if (tocItems.isEmpty()) {
            return new ByteArrayResource(file.getBytes()).getByteArray();
        }
        return addBookmarksToOriginalPdf(file.getBytes(), tocItems);
    }

    public List<TocItem> extractTocItems(MultipartFile file) throws IOException {
        List<TocItem> items = new ArrayList<>();
        try (PdfDocument pdf = new PdfDocument(new PdfReader(file.getInputStream()))) {
            // 1. 预扫描：寻找潜在的目录页范围
            List<Integer> tocCandidatePages = findTocPages(pdf);

            // 2. 策略A：如果有目录页，优先解析目录页 (高精度)
            if (!tocCandidatePages.isEmpty()) {
                items = new StrategyExplicitToc().extract(pdf, tocCandidatePages);
            }

            // 3. 策略B：如果没有目录页，或目录页解析失败，使用全文版式分析 (WPS兜底方案)
            if (items.isEmpty()) {
                items = new StrategyLayoutStructure().extract(pdf);
            }
        } catch (Exception e) {
            System.err.println("iText extraction failed: " + e.getMessage());
        }

        // 4. 策略C：PDFBox 兜底 (解决 iText 字体解析崩溃问题)
        if (items == null || items.isEmpty()) {
            try {
                items = new StrategyPdfBox().extract(file);
            } catch (Exception e) {
                System.err.println("PDFBox extraction failed: " + e.getMessage());
            }
        }

        // 5. 策略D：OCR 兜底 (针对扫描件)
        if (items == null || items.isEmpty()) {
            try {
                if ("aliyun".equalsIgnoreCase(ocrProvider) || "deepseek".equalsIgnoreCase(ocrProvider)) {
                    System.out.println("Using API OCR Strategy (" + apiModel + ")...");
                    items = new StrategyOcr(new OpenAiCompatibleEngine(apiKey, apiBaseUrl, apiModel, ocrPrompt, restTemplate)).extract(file);
                } else {
                    System.out.println("Using Local Tesseract OCR Strategy...");
                    items = new StrategyOcr().extract(file);
                }
            } catch (Exception e) {
                System.err.println("OCR extraction failed: " + e.getMessage());
                e.printStackTrace();
            }
        }

        return items != null ? items : new ArrayList<>();
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
        try {
            new PdfCanvasProcessor(extractor).processPageContent(pdf.getPage(pageNum));
            extractor.finish();
        } catch (Exception e) {
            // 捕获字体解析异常(如 NullPointerException in PdfType0Font)，防止整个流程失败
            System.err.println("Warning: Failed to extract layout from page " + pageNum + ": " + e.getMessage());
        }
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

    /* ======================= 策略C：基于 PDFBox 的兜底提取 ======================= */

    static class StrategyPdfBox {
        
        static class RawToc {
            String title;
            int logicalPage;
            int physicalPage = -1; // 新增：实际物理页码
            boolean hasExplicitPage = false;
            int level = 1; // 新增：层级
            RawToc(String t, int p) { title = t; logicalPage = p; }
        }

        public List<TocItem> extract(MultipartFile file) throws IOException {
            List<TocItem> items = new ArrayList<>();
            try (PDDocument doc = PDDocument.load(file.getInputStream())) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);

                int maxScan = Math.min(20, doc.getNumberOfPages());
                List<String> tocLines = new ArrayList<>();
                int tocStartPage = -1;

                // 1. 寻找目录页
                for (int i = 1; i <= maxScan; i++) {
                    stripper.setStartPage(i);
                    stripper.setEndPage(i);
                    String text = stripper.getText(doc);

                    if (text.contains("目录") || text.toLowerCase().contains("contents")) {
                        tocStartPage = i;
                        tocLines.addAll(Arrays.asList(text.split("\\r?\\n")));
                        // 尝试读取后续页
                        for (int j = i + 1; j <= maxScan; j++) {
                            stripper.setStartPage(j);
                            stripper.setEndPage(j);
                            String nextText = stripper.getText(doc);
                            // 简单判断：如果包含大量 "......数字" 则认为是续页
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

                // 2. 解析行
                List<RawToc> raws = new ArrayList<>();
                for (String line : tocLines) {
                    line = line.trim();
                    // 匹配 "标题......页码"
                    Matcher m1 = Pattern.compile("^(.*?)(?:\\.{2,}|…|—|\\s{2,})(\\d+)$").matcher(line);
                    if (m1.find()) {
                        raws.add(new RawToc(m1.group(1).trim(), Integer.parseInt(m1.group(2))));
                        continue;
                    }
                    
                    // 匹配 "1. 标题 5" 这种格式
                    if (line.matches("^\\d+\\..+\\s+\\d+$")) {
                         String[] parts = line.split("\\s+");
                         String pageStr = parts[parts.length-1];
                         if (pageStr.matches("\\d+")) {
                             String title = line.substring(0, line.lastIndexOf(pageStr)).trim();
                             raws.add(new RawToc(title, Integer.parseInt(pageStr)));
                         }
                    }
                }

                // 3. 计算偏移量
                int offset = 0;
                if (!raws.isEmpty()) {
                    offset = calculatePageOffset(doc, raws) + 1;  // pdf 页码从 0 开始，而书籍页码从 1 开始。
                }

                for (RawToc r : raws) {
                    int physicalPage = r.logicalPage + offset;
                    if (physicalPage > 0 && physicalPage <= doc.getNumberOfPages()) {
                        TocItem item = new TocItem();
                        item.title = r.title;
                        item.page = physicalPage;
                        item.level = 1; 
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
                         int guess = item.logicalPage + k;
                         if (guess < 1 || guess > doc.getNumberOfPages()) continue;
                         
                         stripper.setStartPage(guess);
                         stripper.setEndPage(guess);
                         String content = stripper.getText(doc);
                         
                         if (content.replaceAll("\\s", "").contains(item.title.replaceAll("\\s", ""))) {
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

    /* ======================= OCR 引擎抽象 ======================= */

    interface OcrEngine {
        String doOCR(BufferedImage image) throws Exception;
        // 新增：支持自定义 Prompt 的重载方法
        default String doOCR(BufferedImage image, String promptOverride) throws Exception {
            return doOCR(image);
        }
        // 新增：支持批量图片识别 (不拼接，作为多图输入)
        default String doOCR(List<BufferedImage> images, String promptOverride) throws Exception {
            StringBuilder sb = new StringBuilder();
            for (BufferedImage img : images) {
                sb.append(doOCR(img, promptOverride)).append("\n");
            }
            return sb.toString();
        }
    }

    static class TesseractEngine implements OcrEngine {
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

    static class OpenAiCompatibleEngine implements OcrEngine {
        private final String apiKey;
        private final String baseUrl;
        private final String model;
        private final String defaultPrompt;
        private final RestTemplate restTemplate;

        public OpenAiCompatibleEngine(String apiKey, String baseUrl, String model, String defaultPrompt, RestTemplate restTemplate) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
            this.defaultPrompt = defaultPrompt;
            this.restTemplate = restTemplate;
        }

        @Override
        public String doOCR(BufferedImage image) throws Exception {
            return doOCR(image, null);
        }

        @Override
        public String doOCR(BufferedImage image, String promptOverride) throws Exception {
            return doOCR(Collections.singletonList(image), promptOverride);
        }

        @Override
        public String doOCR(List<BufferedImage> images, String promptOverride) throws Exception {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", model);
            payload.put("max_tokens", 4096);

            // 第一步：先创建thinking对应的嵌套Map，存储其内部的键值对
            Map<String, Object> thinkingMap = new HashMap<>();
            thinkingMap.put("type", "disable");
            thinkingMap.put("clear_thinking", true);

            // 第二步：将嵌套Map作为值，放入顶层payload中
            payload.put("thinking", thinkingMap);
            
            
            // Request JSON format if supported (e.g. gpt-4o, gpt-3.5-turbo-0125)
            if (model.contains("gpt") || model.contains("json")) {
                Map<String, String> responseFormat = new HashMap<>();
                responseFormat.put("type", "json_object");
                payload.put("response_format", responseFormat);
            }

            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");

            List<Map<String, Object>> contentList = new ArrayList<>();

            // 1. 先添加图片 (Images First)
            for (BufferedImage image : images) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                // 使用 JPEG 压缩，质量 0.8，平衡体积和画质
                javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
                javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.8f);
                
                try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                    writer.setOutput(ios);
                    writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
                }
                writer.dispose();
                
                String base64Image = Base64.getEncoder().encodeToString(baos.toByteArray());

                Map<String, Object> imageContent = new HashMap<>();
                imageContent.put("type", "image_url");
                Map<String, String> imageUrl = new HashMap<>();
                imageUrl.put("url", "data:image/jpeg;base64," + base64Image);
                imageContent.put("image_url", imageUrl);
                contentList.add(imageContent);
            }

            // 2. 后添加文本 Prompt (Text Last)
            Map<String, Object> textContent = new HashMap<>();
            textContent.put("type", "text");
            // 使用 Override 的 Prompt，如果没有则使用默认配置的 Prompt，如果还没配置则使用兜底
            String finalPrompt = (promptOverride != null && !promptOverride.isEmpty()) ? promptOverride : defaultPrompt;
            textContent.put("text", finalPrompt != null && !finalPrompt.isEmpty() ? finalPrompt : "OCR this image. Output only the text content.");
            contentList.add(textContent);

            userMessage.put("content", contentList);
            payload.put("messages", Collections.singletonList(userMessage));

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            try {
                ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl + "/chat/completions", request, Map.class);

                if (response.getBody() != null) {
                    if (response.getBody().containsKey("choices")) {
                        List choices = (List) response.getBody().get("choices");
                        if (!choices.isEmpty()) {
                            Map choice = (Map) choices.get(0);
                            Map message = (Map) choice.get("message");
                            return (String) message.get("content");
                        }
                    } else if (response.getBody().containsKey("error")) {
                        System.err.println("API Error Response: " + response.getBody());
                    } else {
                        System.err.println("Unknown API Response format: " + response.getBody());
                    }
                }
            } catch (Exception e) {
                System.err.println("API Request Failed: " + e.getMessage());
                throw e;
            }
            return "";
        }
    }

    /* ======================= 策略D：基于 OCR 的扫描件提取 ======================= */

    class StrategyOcr {
        private final OcrEngine ocrEngine;

        public StrategyOcr() {
            this(new TesseractEngine());
        }

        public StrategyOcr(OcrEngine ocrEngine) {
            this.ocrEngine = ocrEngine;
        }

        public List<TocItem> extract(MultipartFile file) throws IOException {
            List<TocItem> items = new ArrayList<>();
            
            try (PDDocument doc = PDDocument.load(file.getInputStream())) {
                PDFRenderer renderer = new PDFRenderer(doc);
                int maxScan = Math.min(20, doc.getNumberOfPages());
                
                List<String> tocLines = new ArrayList<>();
                int tocStartPage = -1;
                List<StrategyPdfBox.RawToc> raws = new ArrayList<>();

                // 1. 寻找目录页 (OCR)
                // 优化：使用配置的 OCR 引擎进行探测 
                
                for (int i = 0; i < maxScan; i++) { // PDFBox page index starts at 0 for rendering
                    try {
                        // 阶段一：快速扫描 (150 DPI)
                        BufferedImage image = renderer.renderImageWithDPI(i, 150); 
                        // 使用配置的引擎进行探测，强制使用纯文本 Prompt
                        String text = ocrEngine.doOCR(image, "请仅输出图像中的文本内容。");
                        
                        // 传统文本模式 (Tesseract 或 LLM 返回纯文本)
                        String cleanText = text.replaceAll("\\s+", "");
                        int tocLikeCount = countTocLikeLines(text);
                        
                        System.out.println("Discovery Scan Page " + i + ": length=" + text.length() + ", tocLines=" + tocLikeCount + ", hasKeyword=" + (cleanText.contains("目录") || cleanText.toLowerCase().contains("contents")));
                        
                        boolean isTocPage = cleanText.contains("目录") || cleanText.toLowerCase().contains("contents");
                        if (!isTocPage) {
                            int chapterPatternCount = countChapterPatterns(text);
                            if (tocLikeCount >= 5 && chapterPatternCount >= 2) {
                                isTocPage = true;
                                System.out.println("   -> Detected as TOC by density (Lines=" + tocLikeCount + ", Patterns=" + chapterPatternCount + ")");
                            }
                        }

                        if (isTocPage) {
                            System.out.println(">>> Found TOC Start at Page " + i);
                            tocStartPage = i;
                            
                            // 收集所有目录页图片
                            List<BufferedImage> tocImages = new ArrayList<>();
                            tocImages.add(renderer.renderImageWithDPI(i, 300));
                            
                            // 尝试读取后续页
                            for (int j = i + 1; j < maxScan; j++) {
                                BufferedImage nextImageLow = renderer.renderImageWithDPI(j, 150);
                                // 使用配置的引擎探测续页，强制使用纯文本 Prompt
                                String nextTextLow = ocrEngine.doOCR(nextImageLow, "请仅输出图像中的文本内容。");
                                
                                // 续页判断也需要稍微严格一点，或者保持宽松但依赖后续清洗
                                if (countTocLikeLines(nextTextLow) > 3) {
                                    System.out.println(">>> Found TOC Continuation at Page " + j);
                                    tocImages.add(renderer.renderImageWithDPI(j, 300));
                                } else {
                                    break;
                                }
                            }
                            
                            // 批量发送识别 (分批处理，每批3张)
                            System.out.println(">>> Batch OCR for " + tocImages.size() + " pages (Batch Size: 3)...");
                            
                            List<StrategyPdfBox.RawToc> allBatchRaws = new ArrayList<>();
                            StringBuilder allTextFallback = new StringBuilder();
                            
                            int batchSize = 3;
                            for (int k = 0; k < tocImages.size(); k += batchSize) {
                                int end = Math.min(k + batchSize, tocImages.size());
                                List<BufferedImage> batch = tocImages.subList(k, end);
                                System.out.println(">>> Processing Batch " + (k/batchSize + 1) + " (Pages " + k + " to " + (end-1) + ")...");
                                
                                int maxRetries = 3;
                                int retryCount = 0;
                                String currentPrompt = null;
                                
                                while (retryCount <= maxRetries) {
                                    try {
                                        String batchResult = ocrEngine.doOCR(batch, currentPrompt);
                                        System.out.println("DEBUG: Batch " + (k/batchSize + 1) + " Try " + (retryCount+1) + " Result:\n" + batchResult);
                                        
                                        List<StrategyPdfBox.RawToc> batchRaws = tryParseJson(batchResult);
                                        
                                        // 尝试修复截断的 JSON
                                        if (batchRaws.isEmpty() && batchResult.contains("[")) {
                                            batchRaws = tryParseJsonLenient(batchResult);
                                        }
                                        
                                        if (!batchRaws.isEmpty()) {
                                            allBatchRaws.addAll(batchRaws);
                                        } else {
                                            if (retryCount == maxRetries) {
                                                allTextFallback.append(batchResult).append("\n");
                                            }
                                        }
                                        
                                        // 检查是否截断
                                        String trimmed = batchResult.trim();
                                        if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
                                        boolean isTruncated = !trimmed.endsWith("]");
                                        
                                        if (isTruncated && retryCount < maxRetries) {
                                            String lastTitle = null;
                                            if (!batchRaws.isEmpty()) {
                                                lastTitle = batchRaws.get(batchRaws.size() - 1).title;
                                            } else {
                                                lastTitle = extractLastTitleFromText(batchResult);
                                            }
                                            
                                            if (lastTitle != null) {
                                                System.out.println(">>> Truncation detected. Continuing after: " + lastTitle);
                                                
                                                // 提取原始 Prompt 中的格式要求部分
                                                String formatInstruction = (ocrPrompt != null && !ocrPrompt.isEmpty()) ? ocrPrompt : "Output as a JSON array with 'title', 'page', and 'level' fields.";
                                                
                                                // 构建更明确的接续 Prompt
                                                currentPrompt = "The previous output was truncated. We need to continue extracting the Table of Contents.\n" +
                                                                "**TASK**: Start listing items IMMEDIATELY AFTER the chapter: \"" + lastTitle + "\".\n" +
                                                                "**CONSTRAINT**: \n" +
                                                                "1. Do NOT repeat \"" + lastTitle + "\".\n" +
                                                                "2. Do NOT list any chapters that appear before \"" + lastTitle + "\".\n" +
                                                                "3. Continue until the end of the provided images.\n\n" +
                                                                "**FORMATTING RULES** (Strictly follow these rules):\n" + 
                                                                formatInstruction;
                                                
                                                System.out.println("DEBUG: Retry Prompt Length: " + currentPrompt.length());
                                                
                                                retryCount++;
                                                continue;
                                            }
                                        }
                                        break; // 成功或无法继续
                                        
                                    } catch (Exception e) {
                                        System.err.println("Batch OCR Failed: " + e.getMessage());
                                        break;
                                    }
                                }
                                
                                // 简单的延时
                                if (end < tocImages.size()) Thread.sleep(500);
                            }
                            
                            if (!allBatchRaws.isEmpty()) {
                                raws.addAll(allBatchRaws);
                                
                                // [再次执行] 全局层级修正 (确保跨 Batch 的上下文生效)
                                boolean hasPart = raws.stream().anyMatch(r -> r.title.matches("^第[一二三四五六七八九十百]+[部编].*"));
                                if (hasPart) {
                                    for (StrategyPdfBox.RawToc r : raws) {
                                        if (r.title.matches("^第[一二三四五六七八九十百]+[章].*")) {
                                            r.level = 2;
                                        } else if (r.title.matches("^第[一二三四五六七八九十百]+[节].*") || r.title.matches("^\\d+\\.\\d+.*")) {
                                            r.level = 3;
                                        }
                                    }
                                }
                            } else {
                                tocLines.addAll(Arrays.asList(allTextFallback.toString().split("\\r?\\n")));
                            }
                            
                            break;
                        }
                    } catch (Exception e) {
                        System.err.println("OCR Error on page " + i + ": " + e.getMessage());
                        continue;
                    }
                }

                // 2. 解析行 (复用 PDFBox 策略的逻辑)
                // 如果 raws 已经通过 JSON 填充了，就不需要再解析 tocLines 了
                if (raws.isEmpty() && !tocLines.isEmpty()) {
                    List<StrategyPdfBox.RawToc> parsedRaws = new ArrayList<>();
                    for (String line : tocLines) {
                        line = line.trim();
                        if (line.isEmpty()) continue;

                        // 2.1 清洗 OCR 常见错误
                        line = cleanOcrTypos(line);

                        // 2.2 过滤垃圾行 (全是乱码或无意义字符)
                        if (isGarbageLine(line)) continue;

                        // 2.3 [新增] 解析 Markdown 表格行 (针对 Qwen-VL 等模型)
                        // 格式: | 标题 | 页码 | 或 | 标题 |
                        if (line.startsWith("|") && line.endsWith("|")) {
                            String[] parts = line.split("\\|");
                            // parts[0] is empty because line starts with |
                            if (parts.length >= 3) {
                                String title = parts[1].trim();
                                String pageStr = parts[2].trim();
                                if (pageStr.matches("\\d+")) {
                                    parsedRaws.add(new StrategyPdfBox.RawToc(title, Integer.parseInt(pageStr)));
                                    continue;
                                }
                            }
                            // 处理只有标题没有页码的情况 (如 "第一部")
                            if (parts.length >= 2) {
                                String title = parts[1].trim();
                                // 避免把表头 "目录", "---" 当作标题
                                if (!title.equals("目录") && !title.matches("-+")) {
                                    // 启用：添加没有页码的行 (如 "第一部")，页码设为 -1，后续回填
                                    parsedRaws.add(new StrategyPdfBox.RawToc(title, -1));
                                }
                            }
                        }

                        // OCR 结果可能包含杂乱空格，稍微放宽正则
                        // 允许 _ (OCR常把点识别为下划线) 和空格
                        Matcher m1 = Pattern.compile("^(.*?)[…\\._—\\s]{2,}(\\d+)$").matcher(line);
                        if (m1.find()) {
                            String title = m1.group(1).trim();
                            // 再次清洗标题部分的特殊字符
                            title = title.replaceAll("[…\\._—]+$", "").trim();
                            
                            if (title.length() > 1) {
                                parsedRaws.add(new StrategyPdfBox.RawToc(title, Integer.parseInt(m1.group(2))));
                            }
                            continue;
                        }
                        
                        // 备用：如果行尾是数字，且前面有足够长度的文本
                        if (line.matches(".*\\s\\d+$") && line.length() > 5) {
                             String[] parts = line.split("\\s+");
                             String pageStr = parts[parts.length-1];
                             if (pageStr.matches("\\d+")) {
                                 String title = line.substring(0, line.lastIndexOf(pageStr)).trim();
                                 parsedRaws.add(new StrategyPdfBox.RawToc(title, Integer.parseInt(pageStr)));
                             }
                        }
                    }
                    raws.addAll(parsedRaws);
                }

                // 2.5 [新增] 页码回填逻辑：无页码的标题 (如 "第一部") 承接后一个条目的页码
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

                // 3. 动态校准页码 (Dynamic Page Alignment)
                // 使用 VQA 模式逐章校准，解决缺页导致的偏移量变化问题
                if (!raws.isEmpty()) {
                    // 再次清洗所有标题，确保没有页码后缀残留，否则 VQA 会失败
                    for (StrategyPdfBox.RawToc r : raws) {
                        if (r.title != null) {
                            r.title = r.title.replaceAll("[…\\._—\\s]+\\d+$", "").trim();
                        }
                    }
                    alignPageNumbers(doc, raws, ocrEngine);
                }

                // 4. 生成结果
                for (StrategyPdfBox.RawToc r : raws) {
                    // 如果 alignPageNumbers 成功找到了 physicalPage，则使用它
                    // 否则使用 logicalPage + 0 (或者在 alignPageNumbers 中设置默认值)
                    // 修正：物理页索引转页码，需要 +1
                    int physicalPage = (r.physicalPage > 0) ? r.physicalPage + 1 : r.logicalPage;
                    
                    if (physicalPage > 0 && physicalPage <= doc.getNumberOfPages()) {
                        TocItem item = new TocItem();
                        item.title = r.title;
                        item.page = physicalPage;
                        item.level = r.level; // 使用解析出的 level
                        items.add(item);
                    }
                }
            } catch (UnsatisfiedLinkError e) {
                System.err.println("Tesseract native library not found: " + e.getMessage());
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

        private int countChapterPatterns(String text) {
            int count = 0;
            Pattern p = Pattern.compile("^(第.+章|Chapter|\\d+\\.|[一二三四五六七八九十]+、).*");
            for (String line : text.split("\\n")) {
                if (p.matcher(line.trim()).find()) count++;
            }
            return count;
        }

        private String cleanOcrTypos(String line) {
            // 1. 修复 "第 x 章" 中的常见错字
            // 匹配模式： 第 + (错字) + 章
            // 关 -> 六, 作 -> 八, 荐 -> 第
            if (line.contains("第") && line.contains("章")) {
                line = line.replaceAll("第\\s*关\\s*章", "第六章");
                line = line.replaceAll("第\\s*作\\s*章", "第八章");
                line = line.replaceAll("第\\s*十\\s*_\\s*章", "第十一章");
                line = line.replaceAll("第\\s*一\\s*章", "第一章"); // 规范化空格
            }
            if (line.startsWith("荐")) {
                line = "第" + line.substring(1);
            }
            
            // 2. 修复页码前的乱码
            // 比如 "......003" 被识别为 "...... 3" 或 "......O3"
            // 这里很难做通用修复，主要依赖正则提取数字
            
            return line;
        }

        private boolean isGarbageLine(String line) {
            // 过滤掉全是乱码的行
            // 规则：如果长度 > 10 且不包含任何中文，也不包含常见英文单词(Chapter, Section)，且包含大量非字母数字符号
            if (line.length() > 10) {
                boolean hasChinese = line.matches(".*[\\u4e00-\\u9fa5].*");
                boolean hasEnglishKeyword = line.toLowerCase().matches(".*(chapter|section|part|index|content).*");
                
                if (!hasChinese && !hasEnglishKeyword) {
                    // 计算非字母数字字符的比例
                    long symbolCount = line.chars().filter(ch -> !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch)).count();
                    if ((double) symbolCount / line.length() > 0.5) {
                        return true; // 符号占比超过 50%，认为是乱码
                    }
                }
            }
            return false;
        }

        /**
         * 通过 OCR 采样验证来计算页码偏移量
         */
        /**
         * 动态页码校准 (Dynamic Page Alignment)
         * 策略优化：
         * 1. 快速预检：随机抽取 3 个样本（前、中、后）计算偏移量。
         * 2. 如果样本偏移量一致，则认为全书偏移量固定，直接应用，跳过后续验证。
         * 3. 如果样本偏移量不一致，则回退到逐章校准模式。
         */
        private void alignPageNumbers(PDDocument doc, List<StrategyPdfBox.RawToc> raws, OcrEngine ocrEngine) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int totalPages = doc.getNumberOfPages();
            
            // 过滤出适合作为锚点的章节 (Level 1, 有页码且标题长度足够)
            // 用户反馈：必须限制 Level 1，否则次章可能选到第一章的第一节，导致基准偏移计算错误
            List<StrategyPdfBox.RawToc> anchors = raws.stream()
                .filter(r -> r.level == 1 && r.logicalPage > 0 && r.title != null && r.title.length() >= 2)
                .collect(Collectors.toList());

            if (anchors.isEmpty()) {
                // 如果没有 Level 1，尝试放宽到 Level 2 (针对只有 Level 2 的特殊书籍)
                anchors = raws.stream()
                    .filter(r -> r.logicalPage > 0 && r.title != null && r.title.length() >= 2)
                    .collect(Collectors.toList());
            }

            if (anchors.isEmpty()) return;

            // === 阶段一：快速预检 (Sampling) ===
            // 策略：采样首、次、中、尾。
            // 用次章（通常是正文开始）的偏移去验证中章和尾章。
            // 如果一致，说明正文偏移统一。
            
            List<StrategyPdfBox.RawToc> samples = new ArrayList<>();
            // 1. 首章
            if (!anchors.isEmpty()) samples.add(anchors.get(0));
            // 2. 次章 (如果存在)
            if (anchors.size() > 1) samples.add(anchors.get(1));
            // 3. 中章
            if (anchors.size() > 10) samples.add(anchors.get(anchors.size() / 2));
            // 4. 尾章 (改为倒数第二章，避开可能的附录/索引/后记，通常正文结束于倒数第二章)
            if (anchors.size() > 2) {
                int lastIndex = anchors.size() - 1;
                if (anchors.size() > 3) {
                    lastIndex = anchors.size() - 2;
                }
                samples.add(anchors.get(lastIndex));
            }

            // 至少要有次章才能执行此策略 (即至少2个元素)
            if (anchors.size() > 1) {
                StrategyPdfBox.RawToc second = anchors.get(1);
                System.out.println("[Align] Sampling Strategy: Using 2nd chapter [" + second.title + "] as baseline...");
                
                // 1. 计算次章偏移 (范围大，20，确保能跨越前言或找到正文)
                // 关键修正：对于基准搜索，强制只搜索正向偏移 (Positive Only)。
                // 原因：物理页码 = 逻辑页码 + 前言长度。前言长度 >= 0。
                // 如果允许负向搜索，可能会匹配到前言中的“下章预告”或目录页，导致偏移量为负，造成全书错误。
                int baselineOffset = findOffsetForTitle(renderer, ocrEngine, second.title, second.logicalPage, 0, 20, totalPages, true);
                
                if (baselineOffset != -999) {
                     System.out.println("[Align]   > Baseline Offset (from 2nd chapter): " + baselineOffset + ". Verifying Middle/Last...");
                     boolean consistent = true;
                     
                     // 2. 验证中章和尾章 (从 samples index 2 开始)
                     // 注意：samples列表里，index 0是首章，index 1是次章(baseline)
                     for (int i = 2; i < samples.size(); i++) {
                         StrategyPdfBox.RawToc s = samples.get(i);
                         // 避免重复验证次章自己
                         if (s == second) continue;
                         
                         int verifyOffset = findOffsetForTitle(renderer, ocrEngine, s.title, s.logicalPage, baselineOffset, 2, totalPages);
                         if (verifyOffset != baselineOffset) {
                             System.out.println("[Align]   > Mismatch at [" + s.title + "]: Expected " + baselineOffset + ", Got " + verifyOffset);
                             consistent = false;
                             break;
                         }
                     }
                     
                     if (consistent) {
                         System.out.println("[Align]   > Body samples consistent. Checking First Chapter...");
                         
                         // 3. 单独检查首章 (First Chapter)
                         // 即使首章偏移不同，只要正文一致，我们也可以分别处理，从而避免回退到慢速模式
                         StrategyPdfBox.RawToc first = anchors.get(0);
                         
                         // 扩大搜索范围到 15，以便能覆盖 Preface 的不同偏移 (如 Escape from Freedom)，
                         // 同时利用 findOffsetForTitle 的搜索顺序 (优先 Baseline) 避免误匹配到 TOC (Offset 0)
                         int firstOffset = findOffsetForTitle(renderer, ocrEngine, first.title, first.logicalPage, baselineOffset, 15, totalPages);
                         
                         // 验证 firstOffset 是否合理：首章物理页必须 <= 次章物理页
                         // (防止前言被错误匹配到正文里的 running header)
                         int secondPhysical = second.logicalPage + baselineOffset;
                         if (firstOffset != -999) {
                             int firstPhysical = first.logicalPage + firstOffset;
                             if (firstPhysical > secondPhysical) {
                                 System.out.println("[Align]   > First chapter found at " + firstPhysical + " but > 2nd chapter (" + secondPhysical + "). Rejecting.");
                                 firstOffset = -999;
                             } else {
                                 System.out.println("[Align]   > First Chapter verified at Offset: " + firstOffset);
                             }
                         }
                         
                         if (firstOffset == -999) {
                             // 如果首章用 baselineOffset 没找到，尝试从 0 开始搜 (处理前言偏移不同的情况)
                             System.out.println("[Align]   > First chapter mismatch. Searching independently...");
                             
                             // 限制搜索范围：首章物理页不能超过次章
                             int maxOffset = Math.max(0, secondPhysical - first.logicalPage);
                             int searchRange = Math.min(30, maxOffset);
                             
                             System.out.println("[Align]   > Constraining search range to " + searchRange + " (Max Phys: " + secondPhysical + ")");
                             
                             firstOffset = findOffsetForTitle(renderer, ocrEngine, first.title, first.logicalPage, 0, searchRange, totalPages);
                         }
                         
                         int finalFirstOffset = (firstOffset != -999) ? firstOffset : baselineOffset;
                         
                         System.out.println("[Align]   > SUCCESS: Applying offsets. Body: " + baselineOffset + ", First: " + finalFirstOffset);
                         
                         for (int i = 0; i < raws.size(); i++) {
                             StrategyPdfBox.RawToc item = raws.get(i);
                             if (item.logicalPage <= 0) continue;
                             
                             // 简单的启发式逻辑：
                             // 如果是第一个条目，使用 finalFirstOffset
                             // 其他条目使用 baselineOffset
                             // (这能解决大部分前言+正文模式的书籍)
                             if (i == 0) {
                                 item.physicalPage = item.logicalPage + finalFirstOffset;
                             } else {
                                 item.physicalPage = item.logicalPage + baselineOffset;
                             }
                         }
                         return; // 成功结束
                     }
                }
            }
            
            System.out.println("[Align] Sampling failed or inconsistent. Fallback to Smart Alignment (Major Chapter Check)...");

            // 策略优化：动态偏移 (Dynamic Offset)
            // 不再强制使用全局统一的偏移量，而是根据检测到的锚点进行分段应用。
            // 这能解决偏移量随页码线性漂移的问题 (如 3, 2, 1, 0, -1...)。

            TreeMap<Integer, Integer> knownOffsets = new TreeMap<>();
            int currentOffset = 0; 
            int lastLogicalPage = -1;
            int lastPhysicalPage = -1;
            
            // 第一遍扫描：收集所有确定的偏移量
            for (int i = 0; i < raws.size(); i++) {
                StrategyPdfBox.RawToc item = raws.get(i);
                if (item.logicalPage <= 0) continue;

                boolean isFirstItem = (lastLogicalPage == -1);
                boolean isPageReset = (lastLogicalPage != -1 && item.logicalPage < lastLogicalPage);
                boolean isMajorChapter = item.title != null && item.title.matches("^(第[一二三四五六七八九十\\d]+[章部篇]|Chapter\\s*\\d+).*");
                boolean isSamePage = (item.logicalPage == lastLogicalPage);
                boolean shouldSearch = (isFirstItem || isPageReset || isMajorChapter) && !isSamePage;

                if (shouldSearch) {
                    if (item.title != null && item.title.length() >= 2) {
                        int searchBaseOffset = currentOffset;
                        int searchRange = 5; 
                        if (isFirstItem || isPageReset) {
                            searchRange = 30;
                            if (isPageReset && lastPhysicalPage != -1) {
                                 int minPhysical = lastPhysicalPage + 1;
                                 int minOffset = minPhysical - item.logicalPage;
                                 if (minOffset > currentOffset) searchBaseOffset = minOffset;
                            }
                        }
                        
                        int foundOffset = findOffsetForTitle(renderer, ocrEngine, item.title, item.logicalPage, searchBaseOffset, searchRange, totalPages);
                        
                        if (foundOffset != -999) {
                            currentOffset = foundOffset;
                            knownOffsets.put(item.logicalPage, foundOffset);
                            System.out.println("[Align]   > Found Offset " + foundOffset + " at Logical Page " + item.logicalPage + " [" + item.title + "]");
                        }
                    }
                }
                lastLogicalPage = item.logicalPage;
                lastPhysicalPage = item.logicalPage + currentOffset; // 估算
            }

            if (knownOffsets.isEmpty()) {
                System.out.println("[Align] No valid offsets found. Defaulting to 0.");
                knownOffsets.put(0, 0);
            }

            // 第二遍扫描：应用分段偏移量
            // 使用 floorEntry 查找最近的前一个已知偏移量
            
            for (StrategyPdfBox.RawToc item : raws) {
                if (item.logicalPage > 0) {
                    Map.Entry<Integer, Integer> entry = knownOffsets.floorEntry(item.logicalPage);
                    int offsetToUse;
                    if (entry != null) {
                        offsetToUse = entry.getValue();
                    } else {
                        // 如果当前页码小于第一个已知点，使用第一个已知点的偏移
                        offsetToUse = knownOffsets.firstEntry().getValue();
                    }
                    item.physicalPage = item.logicalPage + offsetToUse;
                }
            }

            return;

        }

        /**
         * 在指定范围内搜索标题，返回找到的偏移量。如果没找到返回 -999。
         * searchRange: 搜索半径 (例如 2 表示搜索 baseOffset-2 到 baseOffset+2)
         */
        private int findOffsetForTitle(PDFRenderer renderer, OcrEngine ocrEngine, String title, int logicalPage, int baseOffset, int searchRange, int totalPages) {
            return findOffsetForTitle(renderer, ocrEngine, title, logicalPage, baseOffset, searchRange, totalPages, false);
        }

        private int findOffsetForTitle(PDFRenderer renderer, OcrEngine ocrEngine, String title, int logicalPage, int baseOffset, int searchRange, int totalPages, boolean positiveOnly) {
            // 优化搜索顺序：优先搜索正向偏移 (物理页 >= 逻辑页是绝大多数情况)
            // 顺序：0, 1, 2... range, -1, -2... -range
            List<Integer> searchOrder = new ArrayList<>();
            searchOrder.add(0);
            for (int i = 1; i <= searchRange; i++) {
                searchOrder.add(i);
            }
            if (!positiveOnly) {
                for (int i = 1; i <= searchRange; i++) {
                    searchOrder.add(-i);
                }
            }

            for (int k : searchOrder) {
                int offset = baseOffset + k;
                int guessPhys = logicalPage + offset;
                
                if (guessPhys < 0 || guessPhys >= totalPages) continue;

                try {
                    // 渲染页面顶部 30%
                    BufferedImage fullImage = renderer.renderImageWithDPI(guessPhys, 100);
                    int h = fullImage.getHeight();
                    int w = fullImage.getWidth();
                    if (h < 10 || w < 10) continue;
                    BufferedImage topImage = fullImage.getSubimage(0, 0, w, h / 2);

                    // VQA Prompt
                    // 强化 Prompt：明确排除目录页，防止 Offset 被锁定在目录页
                    String prompt = "请仔细观察图片，判断这是否是章节“" + title + "”的【起始页】。\n" +
                     "判断标准：\n" +
                     "1. 页面上必须包含标题“" + title + "”。\n" +
                     "2. 该标题必须是【大标题】（字号明显大于正文，通常居中或加粗）。\n" +
                     "3. 严禁匹配【页眉】！如果标题仅出现在页面顶部的页眉区域（字体较小，旁边可能有页码），请回答“否”。\n" +
                     "4. 严禁匹配【目录】！如果页面包含多个章节标题和页码，请回答“否”。\n" +
                     "请只回答“是”或“否”。";
                    String response = ocrEngine.doOCR(topImage, prompt).trim();
                    
                    // System.out.println("DEBUG: Check Page " + guessPhys + " for '" + title + "' -> " + response);

                    if (response.contains("是") || response.toLowerCase().contains("yes")) {
                        return offset;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
            return -999;
        }

        // 保留旧方法以防 StrategyPdfBox 使用 (虽然它不传 ocrEngine)
        private int calculatePageOffset(PDDocument doc, List<StrategyPdfBox.RawToc> raws, OcrEngine ocrEngine) {
             return 0; 
        }

        private boolean matchTitleInContent(String title, String content) {
            // 1. 预处理：去除标点、空格
            String cleanTitle = title.replaceAll("[\\s\\p{Punct}]", "");
            String cleanContent = content.replaceAll("[\\s\\p{Punct}]", "");
            
            if (cleanTitle.length() < 2) return false;

            // 2. 严格匹配：标题必须作为连续子串出现
            // 之前是 contains，对于 "第一章" 这种短标题，容易误判 (如正文中出现 "第一...章...")
            // 但考虑到 OCR 错误，完全连续可能太严。
            // 折中方案：如果标题较短 (<4字)，必须连续出现。如果较长，允许一定的容错。
            
            if (cleanTitle.length() <= 4) {
                return cleanContent.contains(cleanTitle);
            } else {
                // 对于长标题，允许简单的模糊匹配 (比如漏掉一个字)
                // 这里简单起见，还是用 contains，但依赖 cleanContent 的准确性
                return cleanContent.contains(cleanTitle);
            }
        }

        private List<StrategyPdfBox.RawToc> tryParseJson(String text) {
            try {
                // 简单的 JSON 提取：查找第一个 [ 和最后一个 ]
                int start = text.indexOf('[');
                int end = text.lastIndexOf(']');
                if (start >= 0 && end > start) {
                    String json = text.substring(start, end + 1);
                    // 移除可能存在的 Markdown 代码块标记
                    json = json.replaceAll("```json", "").replaceAll("```", "").trim();
                    
                    ObjectMapper mapper = new ObjectMapper();
                    List<Map<String, Object>> list = mapper.readValue(json, new TypeReference<List<Map<String, Object>>>(){});
                    
                    List<StrategyPdfBox.RawToc> result = new ArrayList<>();
                    for (Map<String, Object> map : list) {
                        if (map.containsKey("title")) {
                            String title = String.valueOf(map.get("title"));
                            // 强力清洗标题：去除末尾的页码后缀 (如 "......001")
                            title = title.replaceAll("[…\\._—]+\\d+$", "").trim();
                            
                            int page = -1;
                            if (map.containsKey("page")) {
                                Object pageObj = map.get("page");
                                if (pageObj != null) {
                                    String pageStr = String.valueOf(pageObj).trim();
                                    if (!pageStr.isEmpty() && pageStr.matches("\\d+")) {
                                        try {
                                            page = Integer.parseInt(pageStr);
                                        } catch (NumberFormatException e) {
                                            // ignore
                                        }
                                    }
                                }
                            }
                            
                            StrategyPdfBox.RawToc raw = new StrategyPdfBox.RawToc(title, page);
                            
                            // 解析 Level
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
                            
                            // 使用正则进行层级校验 (Regex Verification)
                            // 策略：如果正则能明确判断出层级，优先使用正则结果（因为 AI 可能对缩进判断不准，但对数字编号判断很准）
                            // 如果正则判断不出（返回默认值），则信任 AI 的结果
                            int regexLevel = inferLevel(title);
                            if (regexLevel != 2) { // 2 是 inferLevel 的默认兜底值，如果不是2，说明匹配到了明确的模式
                                raw.level = regexLevel;
                            } else {
                                // 如果正则没匹配到明确模式，就用 AI 的结果 (如果 AI 也没给，默认是 1)
                                // 但要注意，AI 默认给 1 可能也不对，这里可以结合缩进判断，但我们没有缩进信息
                                // 所以这里信任 AI，如果 AI 给了 > 1 的值，就用它
                                raw.level = level;
                            }
                            
                            result.add(raw);
                        }
                    }
                    
                    // [新增] 全局层级修正 (Global Level Correction)
                    // 解决问题：如果书中同时存在 "第一部" (Level 1) 和 "第一章" (Level 1)，
                    // 应该将 "第一部" 保持 Level 1，将 "第一章" 降级为 Level 2。
                    boolean hasPart = result.stream().anyMatch(r -> r.title.matches("^第[一二三四五六七八九十百]+[部编].*"));
                    if (hasPart) {
                        for (StrategyPdfBox.RawToc r : result) {
                            if (r.title.matches("^第[一二三四五六七八九十百]+[章].*")) {
                                r.level = 2;
                            }
                            // 如果章变成了 Level 2，那么节 (原本可能是 Level 2) 应该变成 Level 3
                            else if (r.title.matches("^第[一二三四五六七八九十百]+[节].*") || r.title.matches("^\\d+\\.\\d+.*")) {
                                r.level = 3;
                            }
                        }
                    }

                    return result;
                }
            } catch (Exception e) {
                System.err.println("JSON Parse Error: " + e.getMessage());
            }
            return new ArrayList<>();
        }

        private List<StrategyPdfBox.RawToc> tryParseJsonLenient(String text) {
            try {
                int start = text.indexOf('[');
                int lastBrace = text.lastIndexOf('}');
                if (start >= 0 && lastBrace > start) {
                    // 尝试闭合 JSON 数组
                    String jsonCandidate = text.substring(start, lastBrace + 1) + "]";
                    return tryParseJson(jsonCandidate);
                }
            } catch (Exception e) {
                // ignore
            }
            return new ArrayList<>();
        }

        private String extractLastTitleFromText(String text) {
             Pattern p = Pattern.compile("\"title\"\\s*:\\s*\"(.*?)\"");
             Matcher m = p.matcher(text);
             String last = null;
             while (m.find()) {
                 last = m.group(1);
             }
             return last;
        }

        /**
         * 静态层级推断工具 (复用 StrategyExplicitToc 的逻辑)
         */
        private int inferLevel(String title) {
            String t = title.trim();

            // Level 1: 第一部、第一编 (最高层级)
            if (t.matches("^第[一二三四五六七八九十百]+[部编].*")) {
                return 1;
            }

            // Level 1 (修正): 第X章 通常也是 Level 1，除非上面有“编/部”
            // 但为了通用性，我们把“章”也视为 Level 1，把“节”视为 Level 2
            // 如果书中有“编”，那么“编”是1，“章”是2。这是一个动态调整的问题。
            // 这里我们采用一种更通用的映射：
            // 编/部 -> 1
            // 章 -> 1 (如果没有编) 或 2 (如果有编) -- 暂时统一为 1，后续可以通过后处理调整
            if (t.matches("^第[一二三四五六七八九十百]+[章].*")) {
                return 1;
            }

            // Level 2: 第一节、1.1、(一)
            // 修正：X.X (如 1.1) 通常是 Level 2 (如果章是 Level 1)
            if (t.matches("^第[一二三四五六七八九十百]+[节].*") || t.matches("^\\d+\\.\\d+.*")) {
                return 2;
            }
            
            // Level 3: X.X.X (如 1.1.1)
            if (t.matches("^\\d+\\.\\d+\\.\\d+.*")) {
                return 3;
            }

            // 其他情况 (如 "一、", "(一)")
            if (t.matches("^[一二三四五六七八九十百]+[、\\s].*")) return 2;
            if (t.matches("^[(（][一二三四五六七八九十百]+[)）].*")) return 3;

            // 兜底逻辑：如果没有明显前缀，默认为 2 (表示普通章节)
            // 注意：这里返回 2 是为了配合上面的校验逻辑，表示“不确定”
            return 2;
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
                try {
                    new PdfCanvasProcessor(extractor).processPageContent(pdf.getPage(p));
                    extractor.finish();
                } catch (Exception e) {
                    System.err.println("Warning: Failed to process page " + p + " in explicit TOC strategy: " + e.getMessage());
                    continue;
                }

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

            // Level 1: 第一部、第一编 (最高层级)
            if (t.matches("^第[一二三四五六七八九十百]+[部编].*")) {
                return 1;
            }

            // Level 2: 第一章、一、 (次级)
            if (t.matches("^第[一二三四五六七八九十百]+[章].*") || t.matches("^[一二三四五六七八九十百]+[、\\s].*")) {
                return 2;
            }

            // Level 3: 第一节、1.1、(一)
            if (t.matches("^第[一二三四五六七八九十百]+[节].*") || t.matches("^\\d+\\.\\d+.*") || t.matches("^[(（][一二三四五六七八九十百]+[)）].*")) {
                return 3;
            }

            // Level 4: 1.、(1)
            if (t.matches("^\\d+[\\.\\s].*") || t.matches("^[(（]\\d+[)）].*")) {
                return 4;
            }

            // 兜底逻辑：如果没有明显前缀，默认为最低层级 (或者根据上下文，这里简单设为 2)
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
                try {
                    new PdfCanvasProcessor(extractor).processPageContent(pdf.getPage(p));
                    extractor.finish();
                } catch (Exception e) {
                    System.err.println("Warning: Failed to process page " + p + " in layout strategy: " + e.getMessage());
                    continue;
                }

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
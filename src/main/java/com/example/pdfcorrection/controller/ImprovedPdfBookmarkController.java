package com.example.pdfcorrection.controller;

import com.example.pdfcorrection.service.ImprovedPdfBookmarkService;
import com.example.pdfcorrection.service.ImprovedPdfBookmarkService.TocItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * PDF目录书签控制器 - 改进版
 */
@RestController
@RequestMapping("/api/pdf")
@CrossOrigin(origins = "*")
public class ImprovedPdfBookmarkController {

    private static final Logger logger = LoggerFactory.getLogger(ImprovedPdfBookmarkController.class);

    // 文件大小限制 (500MB)
    private static final long MAX_FILE_SIZE = 500 * 1024 * 1024;

    @Autowired
    private ImprovedPdfBookmarkService pdfBookmarkService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 上传PDF文件并自动识别插入目录书签
     * @param file 上传的PDF文件
     * @param tocJson 可选的目录结构JSON，如果提供则直接使用，不再重新识别
     * @return 处理后的PDF文件
     */
    @PostMapping(value = "/add-bookmarks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> addBookmarksToPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "tocJson", required = false) String tocJson) {
        long startTime = System.currentTimeMillis();

        try {
            // 文件验证
            ValidationResult validation = validateFile(file);
            if (!validation.isValid()) {
                logger.warn("File validation failed: {}", validation.getMessage());
                return ResponseEntity.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(createErrorResponse(validation.getMessage()));
            }

            logger.info("Processing PDF: {}, size: {} bytes",
                    file.getOriginalFilename(), file.getSize());

            // 处理PDF并添加书签
            byte[] resource;
            if (tocJson != null && !tocJson.trim().isEmpty()) {
                try {
                    // 兼容处理：支持直接的 List<TocItem> 或包含 tableOfContents 字段的包装对象
                    JsonNode rootNode = objectMapper.readTree(tocJson);
                    List<TocItem> providedToc = new ArrayList<>();
                    
                    if (rootNode.isArray()) {
                        providedToc = objectMapper.convertValue(rootNode, new TypeReference<List<TocItem>>(){});
                    } else if (rootNode.has("tableOfContents") && rootNode.get("tableOfContents").isArray()) {
                        providedToc = objectMapper.convertValue(rootNode.get("tableOfContents"), new TypeReference<List<TocItem>>(){});
                    } else {
                        logger.warn("Unknown TOC JSON structure, trying direct parse as fallback");
                        providedToc = objectMapper.readValue(tocJson, new TypeReference<List<TocItem>>(){});
                    }
                    
                    logger.info("Using provided TOC with {} items", providedToc.size());
                    resource = pdfBookmarkService.processAndAddBookmarks(file, providedToc);
                } catch (Exception e) {
                    logger.warn("Failed to parse provided TOC JSON, falling back to extraction. Error: {}", e.getMessage());
                    // 打印部分 JSON 以便调试 (截取前200字符)
                    logger.debug("Received JSON snippet: {}", tocJson.length() > 200 ? tocJson.substring(0, 200) : tocJson);
                    resource = pdfBookmarkService.processAndAddBookmarks(file);
                }
            } else {
                resource = pdfBookmarkService.processAndAddBookmarks(file);
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("PDF processed successfully in {} ms", duration);

            // 设置响应头
            String originalFilename = file.getOriginalFilename();
            String filename = generateOutputFilename(originalFilename);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encodeFilename(filename))
                    .header("X-Processing-Time-Ms", String.valueOf(duration))
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(resource);

        } catch (IOException e) {
            logger.error("IO error processing PDF: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorResponse("文件处理失败: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error processing PDF: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorResponse("系统错误: " + e.getMessage()));
        }
    }

    /**
     * 预览识别的目录结构(不生成文件)
     * @param file 上传的PDF文件
     * @param includeScore 是否包含评分信息(可选,默认false)
     * @return 识别出的目录结构JSON
     */
    @PostMapping(value = "/preview-toc", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> previewTableOfContents(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "includeScore", defaultValue = "false") boolean includeScore) {

        long startTime = System.currentTimeMillis();

        try {
            // 文件验证
            ValidationResult validation = validateFile(file);
            if (!validation.isValid()) {
                logger.warn("File validation failed: {}", validation.getMessage());
                return ResponseEntity.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(createErrorResponse(validation.getMessage()));
            }

            logger.info("Extracting TOC from: {}", file.getOriginalFilename());

            // 提取目录
            List<TocItem> tocItems = pdfBookmarkService.extractTocItems(file);

            long duration = System.currentTimeMillis() - startTime;

            // 构建响应
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("filename", file.getOriginalFilename());
            response.put("totalItems", tocItems.size());
            response.put("processingTimeMs", duration);
            response.put("tableOfContents", formatTocItems(tocItems, includeScore));

            String jsonResponse = objectMapper.writeValueAsString(response);

            logger.info("TOC extracted successfully: {} items in {} ms",
                    tocItems.size(), duration);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonResponse);

        } catch (IOException e) {
            logger.error("IO error extracting TOC: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorResponse("文件读取失败: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error extracting TOC: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorResponse("系统错误: " + e.getMessage()));
        }
    }

    /**
     * 批量处理多个PDF文件
     * @param files 多个PDF文件
     * @return 处理结果摘要
     */
    @PostMapping(value = "/batch-add-bookmarks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> batchAddBookmarks(@RequestParam("files") MultipartFile[] files) {
        logger.info("Batch processing {} files", files.length);

        Map<String, Object> response = new HashMap<>();
        int successCount = 0;
        int failCount = 0;
        Map<String, String> results = new HashMap<>();

        for (MultipartFile file : files) {
            try {
                ValidationResult validation = validateFile(file);
                if (!validation.isValid()) {
                    results.put(file.getOriginalFilename(), "验证失败: " + validation.getMessage());
                    failCount++;
                    continue;
                }

                pdfBookmarkService.processAndAddBookmarks(file);
                results.put(file.getOriginalFilename(), "成功");
                successCount++;

            } catch (Exception e) {
                logger.error("Error processing file {}: {}", file.getOriginalFilename(), e.getMessage());
                results.put(file.getOriginalFilename(), "失败: " + e.getMessage());
                failCount++;
            }
        }

        response.put("total", files.length);
        response.put("success", successCount);
        response.put("failed", failCount);
        response.put("results", results);

        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("响应序列化失败"));
        }
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "PDF Bookmark Service");
        health.put("version", "2.0");
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }

    /**
     * 获取服务信息
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> info = new HashMap<>();
        info.put("serviceName", "智能PDF书签生成服务");
        info.put("version", "2.0");
        info.put("algorithm", "多特征融合算法");
        info.put("supportedFormats", new String[]{"PDF"});
        info.put("maxFileSize", MAX_FILE_SIZE);
        info.put("maxFileSizeMB", MAX_FILE_SIZE / (1024 * 1024));
        info.put("features", new String[]{
                "智能标题识别",
                "多种编号格式支持",
                "层级结构自动分析",
                "中英文混合支持"
        });
        return ResponseEntity.ok(info);
    }

    // ========== 辅助方法 ==========

    /**
     * 验证上传的文件
     */
    private ValidationResult validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ValidationResult.invalid("文件为空");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return ValidationResult.invalid(
                    String.format("文件过大，最大支持 %d MB", MAX_FILE_SIZE / (1024 * 1024)));
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            return ValidationResult.invalid("只支持PDF格式文件");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return ValidationResult.invalid("文件扩展名必须为.pdf");
        }

        return ValidationResult.valid();
    }

    /**
     * 生成输出文件名
     */
    private String generateOutputFilename(String originalFilename) {
        if (originalFilename == null) {
            return "bookmarked_output.pdf";
        }

        String nameWithoutExt = originalFilename.replaceFirst("[.][^.]+$", "");
        return nameWithoutExt + "_with_bookmarks.pdf";
    }

    /**
     * URL编码文件名(支持中文)
     */
    private String encodeFilename(String filename) {
        try {
            return java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20");
        } catch (Exception e) {
            return filename;
        }
    }

    /**
     * 格式化TOC项
     */
    private List<Map<String, Object>> formatTocItems(List<TocItem> tocItems, boolean includeScore) {
        return tocItems.stream().map(item -> {
            Map<String, Object> map = new HashMap<>();
            map.put("title", item.title);
            map.put("page", item.page);
            map.put("level", item.level);
            if (includeScore) {
                // TocItem类中没有score字段，所以这里忽略
            }
            return map;
        }).toList();
    }

    /**
     * 创建错误响应
     */
    private String createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("error", message);
        error.put("timestamp", System.currentTimeMillis());

        try {
            return objectMapper.writeValueAsString(error);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + message + "\"}";
        }
    }

    // ========== 内部类 ==========

    /**
     * 文件验证结果
     */
    private static class ValidationResult {
        private final boolean valid;
        private final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }
    }
}

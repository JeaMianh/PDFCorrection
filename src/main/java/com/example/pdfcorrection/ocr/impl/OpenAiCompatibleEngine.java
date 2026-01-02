package com.example.pdfcorrection.ocr.impl;

import com.example.pdfcorrection.ocr.OcrEngine;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;

public class OpenAiCompatibleEngine implements OcrEngine {
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

        // if (apiModel.equalsIgnoreCase("GLM-4.6V-Flash")) {
        //     // 第一步：先创建thinking对应的嵌套Map，存储其内部的键值对
        // Map<String, Object> thinkingMap = new HashMap<>();
        // thinkingMap.put("type", "disable");
        // thinkingMap.put("clear_thinking", true);

        // // 第二步：将嵌套Map作为值，放入顶层payload中
        // payload.put("thinking", thinkingMap);
        // }
        
        
        
        // Determine final prompt first to decide on response_format
        String finalPrompt = (promptOverride != null && !promptOverride.isEmpty()) ? promptOverride : defaultPrompt;
        if (finalPrompt == null || finalPrompt.isEmpty()) {
            finalPrompt = "OCR this image. Output only the text content.";
        }

        // Request JSON format if supported (e.g. gpt-4o, gpt-3.5-turbo-0125) AND requested in prompt
        if ((model.contains("gpt") || model.contains("json")) && finalPrompt.toLowerCase().contains("json")) {
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
        textContent.put("text", finalPrompt);
        contentList.add(textContent);

        userMessage.put("content", contentList);
        payload.put("messages", Collections.singletonList(userMessage));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        int maxRetries = 3;
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetries) {
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
                        // If it's a rate limit or server error, we might want to retry.
                        // For now, let's treat it as an exception to trigger retry.
                        throw new RuntimeException("API Error: " + response.getBody());
                    } else {
                        System.err.println("Unknown API Response format: " + response.getBody());
                    }
                }
                // If we got a valid response but no content, break loop and return empty?
                // Or maybe retry? Let's assume success if we got here without exception.
                return ""; 

            } catch (Exception e) {
                lastException = e;
                attempt++;
                System.err.println("API Request Failed (Attempt " + attempt + "/" + maxRetries + "): " + e.getMessage());
                
                if (attempt < maxRetries) {
                    try {
                        long sleepTime = 2000L * attempt; // Exponential backoff: 2s, 4s, ...
                        System.out.println("Waiting " + sleepTime + "ms before retry...");
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Interrupted during retry wait", ie);
                    }
                }
            }
        }
        
        throw lastException != null ? lastException : new Exception("Unknown error after " + maxRetries + " attempts");
    }
}

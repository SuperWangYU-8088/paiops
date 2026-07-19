package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.service.MinioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Tag(name = "媒体文件接口")
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;

    private final MinioService minioService;

    public MediaController(MinioService minioService) {
        this.minioService = minioService;
    }

    @Operation(summary = "上传工作流图片")
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file) throws Exception {
        validateImage(file);

        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename()
                : "image.png";
        String contentType = StringUtils.hasText(file.getContentType())
                ? file.getContentType()
                : "image/png";
        String objectName = "images/uploads/" + UUID.randomUUID() + extensionFor(originalName, contentType);
        String url = minioService.uploadFile(objectName, file.getInputStream(), contentType, file.getSize());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("url", url);
        output.put("fileName", originalName);
        output.put("contentType", contentType);
        output.put("size", file.getSize());
        return Result.success(output);
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传图片不能为空");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("图片大小不能超过 10MB");
        }

        String contentType = file.getContentType();
        String fileName = file.getOriginalFilename();
        if (StringUtils.hasText(contentType) && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return;
        }
        if (StringUtils.hasText(fileName) && fileName.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|webp|gif)$")) {
            return;
        }
        throw new IllegalArgumentException("仅支持上传图片文件");
    }

    private String extensionFor(String fileName, String contentType) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        int dotIndex = lowerName.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < lowerName.length() - 1) {
            String extension = lowerName.substring(dotIndex);
            if (extension.matches("\\.(png|jpg|jpeg|webp|gif)")) {
                return extension;
            }
        }

        String lowerType = contentType.toLowerCase(Locale.ROOT);
        return switch (lowerType) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
    }
}

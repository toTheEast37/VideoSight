package com.vibeteaching.parsing.media;

import com.vibeteaching.parsing.config.ParseProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TesseractOcrClient implements OcrClient {
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private final ParseProperties properties;

    public TesseractOcrClient(ParseProperties properties) {
        this.properties = properties;
    }

    @Override
    public String recognize(Path image) {
        if (image == null || !Files.isRegularFile(image)) {
            throw new RecognitionException("OCR_INPUT_MISSING", "画面识别输入不存在", true, false);
        }
        if (properties.ocrCommand() == null || properties.ocrCommand().isBlank()) {
            throw new RecognitionException("OCR_CONFIG_MISSING", "未配置本地 OCR 工具", false, false);
        }
        String command = properties.ocrCommand();
        Path workingDirectory = null;
        List<String> arguments = new ArrayList<>();
        Path executable = Path.of(command);
        if (executable.getParent() != null) {
            executable = executable.toAbsolutePath().normalize();
            command = executable.toString();
            workingDirectory = executable.getParent();
        }
        arguments.addAll(List.of(command, "stdin", "stdout", "-l", "chi_sim+eng"));
        if (workingDirectory != null && Files.isDirectory(workingDirectory.resolve("tessdata"))) {
            // Windows 便携版对中文绝对路径的文件 API 支持有限；语言相对路径配合 stdin 绕开它。
            arguments.addAll(List.of("--tessdata-dir", "tessdata"));
        }
        byte[] result = MediaProcess.run(arguments, properties.processTimeout(), MAX_TEXT_BYTES, "OCR",
                workingDirectory, image.toAbsolutePath());
        return new String(result, StandardCharsets.UTF_8).trim();
    }
}

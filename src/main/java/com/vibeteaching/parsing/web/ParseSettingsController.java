package com.vibeteaching.parsing.web;

import com.vibeteaching.parsing.ParseService;
import com.vibeteaching.parsing.config.ParseProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
public class ParseSettingsController {
    private final ObjectProvider<ParseService> parsing;
    private final ParseProperties properties;
    public ParseSettingsController(ObjectProvider<ParseService> parsing, ParseProperties properties) {
        this.parsing = parsing;
        this.properties = properties;
    }

    @GetMapping("/api/parse-settings")
    public ParseService.Settings settings() {
        ParseService service = parsing.getIfAvailable();
        if (service != null) return service.settings();
        return new ParseService.Settings(false, false, "上传服务尚未启用，暂不能解析视频",
                properties.maxDuration().toSeconds(), properties.frameIntervalSeconds(), properties.maxFrames());
    }
}

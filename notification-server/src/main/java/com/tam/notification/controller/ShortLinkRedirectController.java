package com.tam.notification.controller;


import com.tam.notification.shortlink.ShortLinkVisitorKeyGenerator;
import com.tam.notification.shortlink.dto.ResolvedShortLink;
import com.tam.notification.shortlink.service.ShortLinkClickRecordService;
import com.tam.notification.shortlink.service.ShortLinkRedirectService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class ShortLinkRedirectController {

    private final ShortLinkRedirectService redirectService;
    private final ShortLinkClickRecordService clickRecordService;
    private final ShortLinkVisitorKeyGenerator visitorKeyGenerator;

    @GetMapping("/s/{shortCode:[0-9a-zA-Z]{8}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode, HttpServletRequest request) {
        ResolvedShortLink resolved = redirectService.resolve(shortCode);

        String visitorKey = visitorKeyGenerator.generate(request);
        // 更新点击记录
        clickRecordService.record(resolved, visitorKey);

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(resolved.originalUrl()))
                .build();
    }
}

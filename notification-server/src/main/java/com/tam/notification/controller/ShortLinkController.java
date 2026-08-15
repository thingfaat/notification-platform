package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.dto.CreateShortLinkRequest;
import com.tam.notification.shortlink.dto.CreateShortLinkCommand;
import com.tam.notification.shortlink.dto.CreatedShortLink;
import com.tam.notification.shortlink.service.ShortLinkService;
import com.tam.notification.vo.ShortLinkResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/short-links")
@RequiredArgsConstructor
public class ShortLinkController {
    private final ShortLinkService shortLinkService;

    @PostMapping
    public ApiResponse<ShortLinkResponse> create(@Valid @RequestBody CreateShortLinkRequest request) {
        CreateShortLinkCommand command = CreateShortLinkCommand.management(
                request.applicationId(),
                request.requestId(),
                request.originalUrl(),
                request.expireAt()
        );

        CreatedShortLink created = shortLinkService.create(command);
        return ApiResponse.success(ShortLinkResponse.from(created));
    }
}

package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.domain.channel.ChannelAccount;
import com.tam.notification.dto.CreateChannelAccountRequest;
import com.tam.notification.service.ChannelAccountService;
import com.tam.notification.vo.ChannelAccountResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/channel-accounts")
@RequiredArgsConstructor
public class ChannelAccountController {

    private final ChannelAccountService channelAccountService;

    @PostMapping
    public ApiResponse<ChannelAccountResponse> create(@Valid @RequestBody CreateChannelAccountRequest request) {
        ChannelAccount channelAccount = channelAccountService.create(
                request.applicationId(), request.accountCode(), request.accountName(),
                request.channelType(), request.provider(), request.configJson());
        return ApiResponse.success(ChannelAccountResponse.from(channelAccount));
    }
}

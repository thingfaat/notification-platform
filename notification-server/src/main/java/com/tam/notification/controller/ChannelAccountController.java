package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.domain.channel.ChannelAccount;
import com.tam.notification.dto.CreateChannelAccountRequest;
import com.tam.notification.service.ChannelAccountService;
import com.tam.notification.vo.ChannelAccountResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping
    public ApiResponse<ChannelAccountResponse> get(@RequestParam Long id) {
        ChannelAccount channelAccount = channelAccountService.get(id);
        return ApiResponse.success(ChannelAccountResponse.from(channelAccount));
    }

    @PutMapping
    public ApiResponse<ChannelAccountResponse> update(@RequestParam Long id, @Valid @RequestBody CreateChannelAccountRequest request) {
        channelAccountService.update(id, request.applicationId(), request.accountCode(), request.accountName(),
                request.channelType(), request.provider(), request.configJson());
        return ApiResponse.success(null);
    }

    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam Long id) {
        channelAccountService.delete(id);
        return ApiResponse.success(null);
    }
}

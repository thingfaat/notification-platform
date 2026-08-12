package com.tam.notification.shortlink;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class ShortLinkVisitorKeyGenerator {

    private final String visitorSlat;

    public ShortLinkVisitorKeyGenerator(
            @Value("${notification.shortlink.click.visitor-salt}")
            String visitorSlat
    ) {
        if (!StringUtils.hasText(visitorSlat)) {
            throw new IllegalArgumentException(
                    "short-link visitor salt must not be blank"
            );
        }
        this.visitorSlat = visitorSlat;
    }

    public String generate(HttpServletRequest request) {
        String source = clientIp(request)
                + "|"
                + userAgent(request)
                + "|"
                + visitorSlat;

        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                            source.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return HexFormat
                    .of()
                    .formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0]
                    .trim();
        }

        return request.getRemoteAddr();
    }

    private String userAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");

        return StringUtils.hasText(userAgent)
                ? userAgent
                : "unknown";
    }
}

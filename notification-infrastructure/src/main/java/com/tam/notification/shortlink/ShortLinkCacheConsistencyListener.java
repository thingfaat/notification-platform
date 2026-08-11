package com.tam.notification.shortlink;

import com.tam.notification.domain.shortlink.ShortLinkCreatedEvent;
import com.tam.notification.domain.shortlink.ShortLinkProtection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ShortLinkCacheConsistencyListener {

    private final ShortLinkProtection shortLinkProtection;


    /**
     * 短链创建成功后，将短链加入布隆过滤器
     *
     * @param event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) // 监听事务提交后
    public void onShortLinkCreated(ShortLinkCreatedEvent event) {
        // 添加到布隆过滤器
        shortLinkProtection.addToBloom(event.shortCode());
        // 删除负缓存
        shortLinkProtection.evictNegative(event.shortCode());
    }
}

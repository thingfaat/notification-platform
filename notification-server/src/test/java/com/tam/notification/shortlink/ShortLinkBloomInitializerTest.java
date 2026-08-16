package com.tam.notification.shortlink;

import com.tam.notification.config.ShortLinkBloomInitializer;
import com.tam.notification.domain.shortlink.ShortLinkMappingRepository;
import com.tam.notification.domain.shortlink.ShortLinkProtection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.mockito.Mockito.*;

class ShortLinkBloomInitializerTest {

    @Test
    void startupShouldLoadOnlyActiveCodesAndCompleteRebuild() {
        ShortLinkMappingRepository repository = mock(ShortLinkMappingRepository.class);
        ShortLinkProtection protection = mock(ShortLinkProtection.class);
        when(protection.isBloomReady()).thenReturn(false);
        when(protection.beginBloomRebuild()).thenReturn(true);
        when(repository.findAllActiveShortCodesAcrossTenants())
                .thenReturn(List.of("Ab12Cd34", "Ef56Gh78"));

        ShortLinkBloomInitializer initializer = new ShortLinkBloomInitializer(repository, protection);
        initializer.run(mock(ApplicationArguments.class));

        verify(repository).findAllActiveShortCodesAcrossTenants();
        verify(protection).completeBloomRebuild(
                List.of("Ab12Cd34", "Ef56Gh78")
        );
    }
}

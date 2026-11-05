package com.ceos.cgv.domain.payment.config;

import com.ceos.cgv.domain.payment.scheduler.PaymentRecoveryScheduler;
import com.ceos.cgv.domain.payment.service.PaymentRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PaymentRecoverySchedulerPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentConfig.class);

    @Test
    void 복구_기준은_기본_1분_배치_50건이다() {
        contextRunner.run(context -> {
            PaymentRecoverySchedulerProperties properties =
                    context.getBean(PaymentRecoverySchedulerProperties.class);
            assertThat(properties.staleAfter()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.batchSize()).isEqualTo(50);
        });
    }

    @Test
    void 복구_기준과_배치는_양수여야_한다() {
        assertThatThrownBy(() -> new PaymentRecoverySchedulerProperties(Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentRecoverySchedulerProperties(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentRecoverySchedulerProperties(Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        contextRunner.withPropertyValues("cgv.payment.recovery.scheduler.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 스케줄러는_명시적으로_켤_때만_등록된다() {
        ApplicationContextRunner schedulerRunner = new ApplicationContextRunner()
                .withBean(PaymentRecoveryService.class, () -> mock(PaymentRecoveryService.class))
                .withUserConfiguration(PaymentRecoveryScheduler.class);
        schedulerRunner.run(context -> assertThat(context).doesNotHaveBean(PaymentRecoveryScheduler.class));
        schedulerRunner.withPropertyValues("cgv.payment.recovery.scheduler.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(PaymentRecoveryScheduler.class));
    }
}

package com.ceos.cgv.domain.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 미완료 결제 작업 자동 복구 설정.
 *
 * <p>staleAfter: 마지막 변경 후 이 시간이 지난 작업만 복구한다. 진행 중인 요청과 겹치지 않도록 PG 연결/응답 타임아웃보다 길게
 * 둔다. batchSize: 한 번의 실행에서 PG를 조회할 최대 작업 수.
 */
@ConfigurationProperties("cgv.payment.recovery.scheduler")
public record PaymentRecoverySchedulerProperties(
        @DefaultValue("PT1M") Duration staleAfter, @DefaultValue("50") int batchSize) {
    public PaymentRecoverySchedulerProperties {
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero())
            throw new IllegalArgumentException("Recovery stale threshold must be positive");
        if (batchSize < 1) throw new IllegalArgumentException("Recovery batch size must be positive");
    }
}

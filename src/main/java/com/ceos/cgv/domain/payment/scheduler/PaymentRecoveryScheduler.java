package com.ceos.cgv.domain.payment.scheduler;

import com.ceos.cgv.domain.payment.service.PaymentRecoveryService;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "cgv.payment.recovery.scheduler",
        name = "enabled",
        havingValue = "true")
public class PaymentRecoveryScheduler {
    private final PaymentRecoveryService recoveryService;

    @Scheduled(
            fixedDelayString = "${cgv.payment.recovery.scheduler.delay-ms:60000}",
            initialDelayString = "${cgv.payment.recovery.scheduler.delay-ms:60000}")
    public void recover() {
        recoveryService.recoverStaleOperations();
    }
}

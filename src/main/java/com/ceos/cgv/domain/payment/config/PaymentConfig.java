package com.ceos.cgv.domain.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    PaymentPricingProperties.class,
    PaymentRecoveryProperties.class,
    PaymentRecoverySchedulerProperties.class
})
public class PaymentConfig {}

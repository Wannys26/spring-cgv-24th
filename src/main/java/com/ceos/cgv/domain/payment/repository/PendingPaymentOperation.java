package com.ceos.cgv.domain.payment.repository;

/** 자동 복구 대상 작업의 식별자와 소유 회원. 복구는 일반 reconcile과 같은 소유권 검증 경로를 사용한다. */
public record PendingPaymentOperation(Long id, Long userId) {}

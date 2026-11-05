package com.ceos.cgv.domain.payment.service;

import com.ceos.cgv.domain.payment.config.PaymentRecoverySchedulerProperties;
import com.ceos.cgv.domain.payment.dto.response.PaymentOperationResponse;
import com.ceos.cgv.domain.payment.enums.PaymentOperationStatus;
import com.ceos.cgv.domain.payment.repository.PaymentOperationRepository;
import com.ceos.cgv.domain.payment.repository.PendingPaymentOperation;
import com.ceos.cgv.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 사용자가 reconcile을 호출하지 않아도 오래된 미완료 작업을 PG 조회로 정리한다.
 *
 * <p>PAYMENT_PENDING 선점은 TTL 만료 대상이 아니고 활성 선점 한도에 포함되므로, 이 복구가 없으면 좌석과 회원의 선점 한도가 계속
 * 묶일 수 있다. 각 작업은 기존 reconcile 경로를 그대로 사용하므로 동시 요청, 멱등키, 결과 검증 규칙이 동일하게 적용된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class PaymentRecoveryService {
    private static final List<PaymentOperationStatus> UNFINISHED =
            List.of(
                    PaymentOperationStatus.PROCESSING,
                    PaymentOperationStatus.UNKNOWN,
                    PaymentOperationStatus.COMPENSATING);

    private final PaymentOperationRepository operationRepository;
    private final PaymentWorkflowService workflowService;
    private final PaymentRecoverySchedulerProperties properties;

    // 단일 서버 가정. 오래 해결되지 않는 작업이 매번 배치 앞쪽을 차지하지 않도록 ID 커서로 순환한다.
    private final AtomicLong cursor = new AtomicLong();

    /** @return 이번 실행에서 SUCCEEDED/FAILED로 종결된 작업 수 */
    public int recoverStaleOperations() {
        // updatedAt은 JPA Auditing 기본 시간(LocalDateTime.now())으로 기록되므로 같은 기준으로 비교한다.
        LocalDateTime staleBefore = LocalDateTime.now().minus(properties.staleAfter());
        List<PendingPaymentOperation> batch =
                operationRepository.findStaleUnfinished(
                        UNFINISHED,
                        staleBefore,
                        cursor.get(),
                        PageRequest.of(0, properties.batchSize()));
        cursor.set(batch.size() < properties.batchSize() ? 0 : batch.getLast().id());

        int finished = 0;
        for (PendingPaymentOperation pending : batch) {
            try {
                PaymentOperationResponse result =
                        workflowService.reconcile(pending.userId(), pending.id());
                if (result.status() == PaymentOperationStatus.SUCCEEDED
                        || result.status() == PaymentOperationStatus.FAILED) finished++;
            } catch (RuntimeException exception) {
                // 한 작업의 실패가 나머지 복구를 막지 않게 하고, PG 원문이나 키가 섞일 수 있는 메시지는 남기지 않는다.
                log.warn(
                        "결제 작업 자동 복구 보류: operationId={}, reason={}",
                        pending.id(),
                        exception instanceof BusinessException business
                                ? business.getErrorCode().name()
                                : exception.getClass().getSimpleName());
            }
        }
        return finished;
    }
}

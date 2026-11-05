package com.ceos.cgv.domain.payment.service;

import com.ceos.cgv.domain.payment.dto.response.PaymentOperationResponse;
import com.ceos.cgv.domain.payment.dto.response.PaymentOrderResponse;
import com.ceos.cgv.domain.payment.enums.PaymentOperationStatus;
import com.ceos.cgv.domain.payment.gateway.GatewayFailureType;
import com.ceos.cgv.domain.payment.gateway.GatewayPayment;
import com.ceos.cgv.domain.payment.gateway.PaymentGatewayException;
import com.ceos.cgv.domain.payment.support.PaymentIntegrationFixture;
import com.ceos.cgv.domain.reservation.dto.ReservedSeatRequest;
import com.ceos.cgv.domain.reservation.dto.SeatHoldCreateRequest;
import com.ceos.cgv.global.exception.BusinessException;
import com.ceos.cgv.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 배치 크기 1로 커서 순환까지 확인한다. 스케줄러 자체는 테스트 프로필에서 꺼져 있고 서비스를 직접 호출한다.
@SpringBootTest(properties = "cgv.payment.recovery.scheduler.batch-size=1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentRecoveryIntegrationTest extends PaymentIntegrationFixture {
    private static final Long OTHER_USER_ID = 8918L;

    @Autowired PaymentRecoveryService recovery;

    @Test
    void 오래된_승인_UNKNOWN은_자동복구로_예매를_확정하고_선점한도를_돌려준다() {
        PaymentOrderResponse order = order(1);
        PaymentOperationResponse pending = pendingApproval(USER_ID, order);
        assertThat(reservationStatus(order)).isEqualTo("PAYMENT_PENDING");
        // PAYMENT_PENDING은 TTL로 풀리지 않으므로 max-active=1 회원은 새 선점을 할 수 없다.
        assertThatThrownBy(() -> holdSeat(USER_ID, 2))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.HOLD_LIMIT_REACHED);

        GatewayPayment approved = done(order);
        when(gateway.getPayment(approved.paymentKey())).thenReturn(approved);
        // 방금 바뀐 작업은 진행 중인 요청일 수 있으므로 건드리지 않는다.
        assertThat(recovery.recoverStaleOperations()).isZero();
        verify(gateway, never()).getPayment(anyString());

        makeStale(pending.operationId());
        assertThat(recovery.recoverStaleOperations()).isEqualTo(1);

        assertThat(queries.operation(USER_ID, pending.operationId()).status())
                .isEqualTo(PaymentOperationStatus.SUCCEEDED);
        assertThat(reservationStatus(order)).isEqualTo("RESERVED");
        assertThat(queries.payment(USER_ID, order.paymentId()).activeOperationId()).isNull();
        verify(gateway, times(1)).confirm(any());
        // 확정 후에는 같은 회원이 다른 좌석을 다시 선점할 수 있다.
        assertThat(holdSeat(USER_ID, 2)).isNotNull();
    }

    @Test
    void 종결된_작업은_오래되어도_PG를_다시_조회하지_않는다() {
        PaymentOrderResponse order = order(1);
        PaymentOperationResponse approved = approve(order);
        assertThat(approved.status()).isEqualTo(PaymentOperationStatus.SUCCEEDED);
        makeStale(approved.operationId());

        assertThat(recovery.recoverStaleOperations()).isZero();

        verify(gateway, never()).getPayment(anyString());
        assertThat(queries.operation(USER_ID, approved.operationId()).status())
                .isEqualTo(PaymentOperationStatus.SUCCEEDED);
        assertThat(reservationStatus(order)).isEqualTo("RESERVED");
    }

    @Test
    void 해결되지_않는_작업이_있어도_커서가_순환해_다음_작업을_복구한다() {
        PaymentOrderResponse stuckOrder = order(1);
        PaymentOrderResponse nextOrder = orderFor(OTHER_USER_ID, 2);
        PaymentOperationResponse stuck = pendingApproval(USER_ID, stuckOrder);
        PaymentOperationResponse next = pendingApproval(OTHER_USER_ID, nextOrder);
        makeStale(stuck.operationId());
        makeStale(next.operationId());
        when(gateway.getPayment(done(stuckOrder).paymentKey()))
                .thenThrow(new PaymentGatewayException(GatewayFailureType.UNKNOWN, "TIMEOUT"));
        when(gateway.getPayment(done(nextOrder).paymentKey())).thenReturn(done(nextOrder));

        // 배치 크기 1: 첫 실행은 앞선 작업만 조회하고 결과가 불명확하면 그대로 둔다.
        assertThat(recovery.recoverStaleOperations()).isZero();
        assertThat(queries.operation(USER_ID, stuck.operationId()).status())
                .isEqualTo(PaymentOperationStatus.UNKNOWN);
        verify(gateway, never()).getPayment(done(nextOrder).paymentKey());

        // 다음 실행은 커서 뒤의 작업으로 넘어가므로 앞선 작업이 배치를 독점하지 않는다.
        assertThat(recovery.recoverStaleOperations()).isEqualTo(1);
        assertThat(queries.operation(OTHER_USER_ID, next.operationId()).status())
                .isEqualTo(PaymentOperationStatus.SUCCEEDED);
        assertThat(reservationStatus(nextOrder)).isEqualTo("RESERVED");
        assertThat(reservationStatus(stuckOrder)).isEqualTo("PAYMENT_PENDING");
    }

    private PaymentOperationResponse pendingApproval(Long userId, PaymentOrderResponse order) {
        // 이미 예외로 stub된 mock을 when(...)으로 다시 호출하지 않도록 doThrow를 사용한다.
        doThrow(new PaymentGatewayException(GatewayFailureType.UNKNOWN, "TIMEOUT"))
                .when(gateway).confirm(any());
        PaymentOperationResponse pending =
                service.confirm(userId, UUID.randomUUID(), approvalRequest(done(order)));
        assertThat(pending.status()).isEqualTo(PaymentOperationStatus.UNKNOWN);
        return pending;
    }

    private Long holdSeat(Long userId, int seatNumber) {
        return holds.create(userId, UUID.randomUUID(),
                        new SeatHoldCreateRequest(8915L, List.of(new ReservedSeatRequest("A", seatNumber))))
                .response().reservationId();
    }

    private void makeStale(Long operationId) {
        jdbc.update("UPDATE payment_operation SET updated_at=? WHERE id=?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)), operationId);
    }
}

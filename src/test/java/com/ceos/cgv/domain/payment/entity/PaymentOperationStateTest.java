package com.ceos.cgv.domain.payment.entity;

import com.ceos.cgv.domain.payment.enums.PaymentOperationStatus;
import com.ceos.cgv.domain.payment.enums.PaymentOperationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentOperationStateTest {
    PaymentOperation processing() {
        PaymentOrder order = PaymentOrder.builder().orderId("order").reservationId(1L).userId(1L)
                .orderName("영화 1석").seatPrice(10000).expiresAt(Instant.now().plusSeconds(300))
                .seats(List.of(PaymentOrderSeat.builder().reservedSeatId(1L).seatRow("A").seatNumber(1).build()))
                .build();
        return PaymentOperation.builder().userId(1L).payment(Payment.builder().order(order).build())
                .type(PaymentOperationType.CONFIRM).requestKey("key").payloadHash("hash")
                .requestAmount(10000).previousBalance(10000).seatIdsJson("[]").reason("").build();
    }

    @Test
    void 성공한_작업의_저장_응답을_실패로_덮어쓸_수_없다() {
        PaymentOperation operation = processing();
        operation.succeed("{\"saved\":true}");

        assertThatThrownBy(() -> operation.fail("LATE", "{}")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> operation.succeed("{}")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> operation.startCompensation("LATE")).isInstanceOf(IllegalStateException.class);
        assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.SUCCEEDED);
        assertThat(operation.getResultJson()).isEqualTo("{\"saved\":true}");
    }

    @Test
    void 실패한_작업을_성공으로_바꿀_수_없고_UNKNOWN_표시는_무시된다() {
        PaymentOperation operation = processing();
        operation.fail("REJECT_CARD_PAYMENT", "{}");

        assertThatThrownBy(() -> operation.succeed("{}")).isInstanceOf(IllegalStateException.class);
        operation.unknown("LATE_TIMEOUT");
        assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.FAILED);
        assertThat(operation.getFailureCode()).isEqualTo("REJECT_CARD_PAYMENT");
    }

    @Test
    void 보상취소_중인_작업은_실패로_종결할_수_있다() {
        PaymentOperation operation = processing();
        operation.startCompensation("SEAT_OWNER_MISMATCH");
        operation.startCompensation("SEAT_OWNER_MISMATCH");
        assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.COMPENSATING);

        operation.fail("APPROVAL_COMPENSATED", "{}");
        assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.FAILED);
    }
}

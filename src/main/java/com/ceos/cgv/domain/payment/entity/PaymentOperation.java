package com.ceos.cgv.domain.payment.entity;

import com.ceos.cgv.domain.payment.enums.PaymentOperationStatus;
import com.ceos.cgv.domain.payment.enums.PaymentOperationType;
import com.ceos.cgv.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(
        name = "payment_operation",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_payment_operation_request",
                        columnNames = {"user_id", "type", "request_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentOperation extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentOperationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentOperationStatus status;

    @Column(name = "request_key", nullable = false, length = 36)
    private String requestKey;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "gateway_key", nullable = false, unique = true, length = 64)
    private String gatewayKey;

    @Column(name = "compensation_key", unique = true, length = 64)
    private String compensationKey;

    @Column(name = "request_amount", nullable = false)
    private long requestAmount;

    @Column(name = "previous_balance", nullable = false)
    private long previousBalance;

    @Column(name = "seat_ids_json", nullable = false, columnDefinition = "text")
    private String seatIdsJson;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Builder
    public PaymentOperation(
            Long userId,
            Payment payment,
            PaymentOperationType type,
            String requestKey,
            String payloadHash,
            long requestAmount,
            long previousBalance,
            String seatIdsJson,
            String reason) {
        if (userId == null
                || payment == null
                || type == null
                || requestKey == null
                || payloadHash == null
                || requestAmount <= 0
                || previousBalance < requestAmount
                || seatIdsJson == null
                || reason == null)
            throw new IllegalArgumentException("Complete operation snapshot is required");
        this.userId = userId;
        this.payment = payment;
        this.type = type;
        this.requestKey = requestKey;
        this.payloadHash = payloadHash;
        this.requestAmount = requestAmount;
        this.previousBalance = previousBalance;
        this.seatIdsJson = seatIdsJson;
        this.reason = reason;
        gatewayKey = UUID.randomUUID().toString();
        status = PaymentOperationStatus.PROCESSING;
    }

    public boolean isTerminal() {
        return status == PaymentOperationStatus.SUCCEEDED
                || status == PaymentOperationStatus.FAILED;
    }

    public boolean isCompensating() {
        return compensationKey != null;
    }

    public void unknown(String code) {
        if (!isTerminal()) {
            status =
                    isCompensating()
                            ? PaymentOperationStatus.COMPENSATING
                            : PaymentOperationStatus.UNKNOWN;
            failureCode = code;
        }
    }

    public void succeed(String result) {
        requireUnfinished();
        status = PaymentOperationStatus.SUCCEEDED;
        resultJson = result;
        failureCode = null;
    }

    public void fail(String code, String result) {
        requireUnfinished();
        status = PaymentOperationStatus.FAILED;
        failureCode = code;
        resultJson = result;
    }

    public void startCompensation(String code) {
        requireUnfinished();
        if (compensationKey == null) compensationKey = UUID.randomUUID().toString();
        status = PaymentOperationStatus.COMPENSATING;
        failureCode = code;
    }

    // 완료 응답은 재전송 시 그대로 반환되므로, 종결된 작업의 결과와 상태를 덮어쓰지 않는다.
    private void requireUnfinished() {
        if (isTerminal()) throw new IllegalStateException("Finished payment operation cannot change");
    }
}

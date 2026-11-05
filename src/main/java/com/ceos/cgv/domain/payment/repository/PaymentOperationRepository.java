package com.ceos.cgv.domain.payment.repository;

import com.ceos.cgv.domain.payment.entity.PaymentOperation;
import com.ceos.cgv.domain.payment.enums.PaymentOperationStatus;
import com.ceos.cgv.domain.payment.enums.PaymentOperationType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentOperationRepository extends JpaRepository<PaymentOperation, Long> {
    Optional<PaymentOperation> findByUserIdAndTypeAndRequestKey(
            Long userId, PaymentOperationType type, String requestKey);

    @Query(
            """
            select new com.ceos.cgv.domain.payment.repository.PendingPaymentOperation(o.id, o.userId)
            from PaymentOperation o
            where o.status in :statuses and o.updatedAt < :staleBefore and o.id > :afterId
            order by o.id asc
            """)
    List<PendingPaymentOperation> findStaleUnfinished(
            Collection<PaymentOperationStatus> statuses,
            LocalDateTime staleBefore,
            Long afterId,
            Pageable pageable);
}

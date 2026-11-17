package kr.hamkkejaram.aiobs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AiUsedReceiptRepository extends JpaRepository<AiUsedReceipt, String> {

    /** 더는 통하지 않는 영수증의 행을 지운다(expires_at 이 기준 시각보다 앞선 것). */
    @Modifying
    @Query("delete from AiUsedReceipt r where r.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}

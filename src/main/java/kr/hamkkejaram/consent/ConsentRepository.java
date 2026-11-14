package kr.hamkkejaram.consent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ConsentRepository extends JpaRepository<Consent, Long> {
    List<Consent> findByUserIdOrderByAgreedAtDesc(Long userId);

    List<Consent> findByChildIdAndTypeAndRevokedAtIsNull(Long childId, ConsentType type);

    boolean existsByChildIdAndTypeAndRevokedAtIsNull(Long childId, ConsentType type);

    /** 아동에게 걸린, 아직 철회되지 않은 모든 동의(아동 삭제 시 한꺼번에 철회 시각을 남긴다). */
    List<Consent> findByChildIdAndRevokedAtIsNull(Long childId);

    /**
     * 보관 기간이 지난, 주인이 없어진 동의 기록을 지운다(ConsentService.purgeOrphanedConsents).
     * 철회 시각이 cutoff보다 앞서고, 계정이 지워졌거나(user_id 없음) 아이별 동의인데 아이가 지워진(child_id 없음) 기록.
     */
    @Modifying
    @Query("""
            delete from Consent c
            where c.revokedAt is not null and c.revokedAt < :cutoff
              and (c.userId is null or (c.childId is null and c.type in :childTypes))
            """)
    int deleteOrphanedRevokedBefore(@Param("cutoff") Instant cutoff,
                                    @Param("childTypes") Collection<ConsentType> childTypes);
}

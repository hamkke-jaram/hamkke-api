package kr.hamkkejaram.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsentRepository extends JpaRepository<Consent, Long> {
    List<Consent> findByUserIdOrderByAgreedAtDesc(Long userId);

    List<Consent> findByChildIdAndTypeAndRevokedAtIsNull(Long childId, ConsentType type);

    boolean existsByChildIdAndTypeAndRevokedAtIsNull(Long childId, ConsentType type);

    /** 아동에게 걸린, 아직 철회되지 않은 모든 동의(아동 삭제 시 한꺼번에 철회 시각을 남긴다). */
    List<Consent> findByChildIdAndRevokedAtIsNull(Long childId);
}

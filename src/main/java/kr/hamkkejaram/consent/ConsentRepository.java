package kr.hamkkejaram.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsentRepository extends JpaRepository<Consent, Long> {
    List<Consent> findByUserIdOrderByAgreedAtDesc(Long userId);

    List<Consent> findByChildIdAndTypeAndRevokedAtIsNull(Long childId, ConsentType type);

    boolean existsByChildIdAndTypeAndRevokedAtIsNull(Long childId, ConsentType type);
}

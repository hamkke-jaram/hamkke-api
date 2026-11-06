package kr.hamkkejaram.share;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InviteCode c where c.code = :code")
    Optional<InviteCode> findByCodeForUpdate(@Param("code") String code);

    boolean existsByCode(String code);

    @Query("select count(c) from InviteCode c where c.childId = :childId and c.usedAt is null and c.expiresAt > :now")
    long countUsable(@Param("childId") Long childId, @Param("now") Instant now);
}

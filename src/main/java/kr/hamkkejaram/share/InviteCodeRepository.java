package kr.hamkkejaram.share;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InviteCode c where c.code = :code")
    Optional<InviteCode> findByCodeForUpdate(@Param("code") String code);

    boolean existsByCode(String code);

    @Query("select count(c) from InviteCode c where c.childId = :childId and c.usedAt is null and c.expiresAt > :now")
    long countUsable(@Param("childId") Long childId, @Param("now") Instant now);

    /** 아직 쓸 수 있는(쓰지 않았고 만료 전) 코드, 최신 발급순. */
    @Query("select c from InviteCode c where c.childId = :childId and c.usedAt is null and c.expiresAt > :now "
            + "order by c.createdAt desc, c.id desc")
    List<InviteCode> findUsable(@Param("childId") Long childId, @Param("now") Instant now);

    /** 만료되었거나 사용된 지 오래된 코드 정리(데이터 최소화). */
    @Modifying
    @Query("delete from InviteCode c where c.expiresAt < :cutoff or (c.usedAt is not null and c.usedAt < :cutoff)")
    int deleteStaleBefore(@Param("cutoff") Instant cutoff);
}

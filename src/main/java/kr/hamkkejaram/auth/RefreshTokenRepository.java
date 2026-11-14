package kr.hamkkejaram.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * 그 사용자의 리프레시 토큰을 모두 지운다(비밀번호 변경). 폐기 표시가 아니라 삭제라서,
     * 다른 기기가 예전 토큰을 보내도 '폐기된 토큰 재사용'으로 보고 새 토큰까지 폐기하는 일이 없다(그냥 401).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.userId = :userId")
    int deleteAllForUser(@Param("userId") Long userId);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}

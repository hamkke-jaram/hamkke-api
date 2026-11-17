package kr.hamkkejaram.aiobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 저장에 쓴 영수증을 기억한다(AIO-07: 영수증 하나는 한 번만 저장).
 *
 * <p>관찰 기록의 {@code session_jti} UNIQUE 만으로는 기록을 지운 뒤가 비어 있었다: 기록을 지우면 jti 도 사라져
 * 영수증 유효 시간 안에 같은 영수증으로 다시 저장할 수 있었다(보호자가 지운 기관 기록을 선생님이 되살리는 식).
 * 그래서 쓴 jti 를 관찰 기록과 따로, 그 영수증이 더는 통하지 않는 시각까지 남긴다.
 *
 * <p>남기는 것은 jti 와 만료 시각뿐이다. 아이·사용자를 가리키는 값이 없으므로 '철회 = 삭제' 뒤에도
 * 그 아이에 대한 것은 남지 않는다. 로그에도 jti 를 적지 않는다(지운 개수만).
 */
@Component
public class UsedReceipts {
    private static final Logger log = LoggerFactory.getLogger(UsedReceipts.class);
    /** 만료 시각이 이만큼 지난 행부터 지운다. 서버 시계를 고친 경우에도 아직 통하는 영수증의 행을 지우지 않으려는 여유. */
    static final Duration PURGE_GRACE = Duration.ofHours(1);

    private final AiUsedReceiptRepository repository;
    private final Clock clock;

    public UsedReceipts(AiUsedReceiptRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 이미 저장에 쓴 영수증인지. 호출한 쪽의 트랜잭션 안에서 읽는다. */
    public boolean isUsed(String jti) {
        return repository.existsById(jti);
    }

    /**
     * 이 영수증을 썼다고 남긴다. 관찰 기록 저장과 같은 트랜잭션에서 부른다: 저장이 되돌려지면 이 행도 함께 되돌려져,
     * 저장되지 않은 영수증이 '쓴 것'으로 남지 않는다.
     *
     * @param receiptExpiresAt 영수증의 {@code exp}. 검증이 시계 차이만큼 더 받아 주므로 그만큼 더해 남긴다.
     */
    public void markUsed(String jti, Instant receiptExpiresAt) {
        repository.saveAndFlush(new AiUsedReceipt(jti, receiptExpiresAt.plus(AiReceiptVerifier.CLOCK_SKEW)));
    }

    /** 더는 통하지 않는 영수증의 행을 지운다. 매일 04:47(서버 시간대). */
    @Scheduled(cron = "0 47 4 * * *")
    @Transactional
    public int purgeExpired() {
        int deleted = repository.deleteExpiredBefore(clock.instant().minus(PURGE_GRACE));
        if (deleted > 0) {
            log.info("Purged {} used ai receipts", deleted);
        }
        return deleted;
    }
}

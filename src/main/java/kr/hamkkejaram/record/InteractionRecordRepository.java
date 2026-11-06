package kr.hamkkejaram.record;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InteractionRecordRepository extends JpaRepository<InteractionRecord, Long> {
    Optional<InteractionRecord> findByChildIdAndClientRecordId(Long childId, String clientRecordId);

    Optional<InteractionRecord> findByIdAndChildId(Long id, Long childId);

    List<InteractionRecord> findByChildIdOrderByRecordedAtDescIdDesc(Long childId, Pageable pageable);

    /** 기준값 계산용: 같은 지표 버전, 이번 기록보다 앞선 기록(최신순). */
    @Query("""
            select r from InteractionRecord r
            where r.childId = :childId and r.specVersion = :specVersion and r.recordedAt <= :before
            order by r.recordedAt desc, r.id desc
            """)
    List<InteractionRecord> findBaseline(@Param("childId") Long childId, @Param("specVersion") String specVersion,
                                         @Param("before") Instant before, Pageable pageable);

    List<InteractionRecord> findByChildIdAndRecordedAtGreaterThanEqualOrderByRecordedAtAsc(Long childId,
                                                                                         Instant from);
}

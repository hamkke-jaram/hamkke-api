package kr.hamkkejaram.aiobs;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiObservationRepository extends JpaRepository<AiObservation, Long> {

    boolean existsBySessionJti(String sessionJti);

    Optional<AiObservation> findByIdAndChildId(Long id, Long childId);

    /** 기록 시각 최신순. sources에 든 출처의 기록만. */
    @Query("select o from AiObservation o where o.childId = :childId and o.source in :sources "
            + "order by o.recordedAt desc, o.id desc")
    List<AiObservation> findRecent(@Param("childId") Long childId,
                                   @Param("sources") Collection<ObservationSource> sources, Pageable pageable);

    /** 그 아이의 AI 관찰 기록 전부 삭제(동의 철회). 연결된 카드 전달 이력은 DB의 ON DELETE CASCADE로 함께 지워진다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AiObservation o where o.childId = :childId")
    int deleteByChildId(@Param("childId") Long childId);
}

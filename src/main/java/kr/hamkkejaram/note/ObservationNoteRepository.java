package kr.hamkkejaram.note;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ObservationNoteRepository extends JpaRepository<ObservationNote, Long> {
    List<ObservationNote> findByChildIdOrderByObservedOnDescIdDesc(Long childId, Pageable pageable);

    Optional<ObservationNote> findByIdAndChildId(Long id, Long childId);

    /** 목록 커서: (observedOn, id)가 기준 메모보다 앞선(더 오래된) 메모, 같은 최신순. */
    @Query("select n from ObservationNote n where n.childId = :childId "
            + "and (n.observedOn < :on or (n.observedOn = :on and n.id < :id)) "
            + "order by n.observedOn desc, n.id desc")
    List<ObservationNote> findOlderThan(@Param("childId") Long childId, @Param("on") LocalDate on,
                                        @Param("id") Long id, Pageable pageable);
}

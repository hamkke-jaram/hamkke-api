package kr.hamkkejaram.note;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObservationNoteRepository extends JpaRepository<ObservationNote, Long> {
    List<ObservationNote> findByChildIdOrderByObservedOnDescIdDesc(Long childId, Pageable pageable);

    Optional<ObservationNote> findByIdAndChildId(Long id, Long childId);
}

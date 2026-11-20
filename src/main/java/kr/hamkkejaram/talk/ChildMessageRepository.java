package kr.hamkkejaram.talk;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChildMessageRepository extends JpaRepository<ChildMessage, Long> {
    /** 최근 것부터(처음 열 때 마지막 n건을 받아 오래된 순으로 뒤집어 보여 준다). */
    List<ChildMessage> findByChildIdOrderByIdDesc(Long childId, Pageable pageable);

    /** 이 id보다 새 이야기, 오래된 순(화면이 몇 초마다 새 것만 받아 간다). */
    List<ChildMessage> findByChildIdAndIdGreaterThanOrderByIdAsc(Long childId, Long afterId, Pageable pageable);

    Optional<ChildMessage> findTopByChildIdOrderByIdDesc(Long childId);

    @Query("select count(m) from ChildMessage m where m.childId = :childId and m.authorId <> :userId "
            + "and m.id > :afterId")
    long countUnread(@Param("childId") Long childId, @Param("userId") Long userId, @Param("afterId") Long afterId);
}

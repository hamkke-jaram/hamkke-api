package kr.hamkkejaram.card;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CardDeliveryRepository extends JpaRepository<CardDelivery, Long> {

    @Query("select d from CardDelivery d join fetch d.card where d.childId = :childId order by d.deliveredAt desc, d.id desc")
    List<CardDelivery> findRecent(@Param("childId") Long childId, Pageable pageable);

    @Query("select d from CardDelivery d join fetch d.card where d.recordId = :recordId")
    Optional<CardDelivery> findByRecordId(@Param("recordId") Long recordId);

    Optional<CardDelivery> findByIdAndChildId(Long id, Long childId);

    /** 카드별 마지막 전달 시각. [cardId, Instant] */
    @Query("select d.card.id, max(d.deliveredAt) from CardDelivery d where d.childId = :childId group by d.card.id")
    List<Object[]> lastDeliveredByCard(@Param("childId") Long childId);
}

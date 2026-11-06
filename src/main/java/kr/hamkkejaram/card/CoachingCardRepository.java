package kr.hamkkejaram.card;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CoachingCardRepository extends JpaRepository<CoachingCard, Long> {
    List<CoachingCard> findByActiveTrueOrderByIdAsc();
}

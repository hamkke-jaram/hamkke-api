package kr.hamkkejaram.note;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChildGoalRepository extends JpaRepository<ChildGoal, Long> {
    List<ChildGoal> findByChildIdOrderByCreatedAtDesc(Long childId);

    Optional<ChildGoal> findByIdAndChildId(Long id, Long childId);
}

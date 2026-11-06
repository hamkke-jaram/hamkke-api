package kr.hamkkejaram.child;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChildRepository extends JpaRepository<Child, Long> {
    List<Child> findByParentIdOrderByIdAsc(Long parentId);

    Optional<Child> findByIdAndParentId(Long id, Long parentId);

    long countByParentId(Long parentId);
}

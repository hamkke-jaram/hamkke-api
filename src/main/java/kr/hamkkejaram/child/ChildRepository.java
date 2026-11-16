package kr.hamkkejaram.child;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChildRepository extends JpaRepository<Child, Long> {
    List<Child> findByParentIdOrderByIdAsc(Long parentId);

    /**
     * 아이 한 명에 대한 쓰기를 줄 세울 때 트랜잭션 맨 처음에 부른다(행 잠금).
     * 초대 코드 3개 한도(SHR-02), AI 동의 철회와 관찰 기록 저장(AIO-03)이 서로 끼어들지 않게 한다.
     * 아이가 없으면 빈 값이고, 접근 확인은 호출한 쪽이 따로 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Child c where c.id = :id")
    Optional<Child> lockById(@Param("id") Long id);

    Optional<Child> findByIdAndParentId(Long id, Long parentId);

    long countByParentId(Long parentId);
}

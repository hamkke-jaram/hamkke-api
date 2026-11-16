package kr.hamkkejaram.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    /** 한 사용자에 대한 쓰기를 줄 세운다(행 잠금). 아이 5명 한도(CHD-05)처럼 세고 넣는 곳에서 트랜잭션 맨 처음에 부른다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(@Param("id") Long id);

    boolean existsByEmail(String email);
}

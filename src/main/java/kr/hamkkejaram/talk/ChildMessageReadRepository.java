package kr.hamkkejaram.talk;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChildMessageReadRepository extends JpaRepository<ChildMessageRead, ChildMessageRead.Key> {
}

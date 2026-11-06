package kr.hamkkejaram.share;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TeacherLinkRepository extends JpaRepository<TeacherLink, Long> {
    boolean existsByTeacherIdAndChildId(Long teacherId, Long childId);

    List<TeacherLink> findByTeacherIdOrderByLinkedAtDesc(Long teacherId);

    List<TeacherLink> findByChildIdOrderByLinkedAtAsc(Long childId);

    @Modifying
    @Query("delete from TeacherLink l where l.teacherId = :teacherId and l.childId = :childId")
    int deleteLink(@Param("teacherId") Long teacherId, @Param("childId") Long childId);
}

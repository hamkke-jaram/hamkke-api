package kr.hamkkejaram.support;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SupportFacilityRepository extends JpaRepository<SupportFacility, Long> {

    /** pattern은 소문자로 바꾸고 %·_·!를 '!'로 이스케이프한 '%검색어%'(없으면 null). 이름·주소·태그에서 찾는다. */
    @Query("""
            select f from SupportFacility f
            where (:sido is null or f.sido = :sido)
              and (:category is null or f.category = :category)
              and (:pattern is null
                   or lower(f.name) like :pattern escape '!'
                   or lower(f.address) like :pattern escape '!'
                   or lower(f.tags) like :pattern escape '!')
            """)
    Page<SupportFacility> search(@Param("sido") String sido, @Param("category") FacilityCategory category,
                                 @Param("pattern") String pattern, Pageable pageable);

    /** 유형별 수. 유형 조건은 걸지 않는다(화면의 유형 칩에 쓴다). [FacilityCategory, Long] 쌍. */
    @Query("""
            select f.category, count(f) from SupportFacility f
            where (:sido is null or f.sido = :sido)
              and (:pattern is null
                   or lower(f.name) like :pattern escape '!'
                   or lower(f.address) like :pattern escape '!'
                   or lower(f.tags) like :pattern escape '!')
            group by f.category
            """)
    List<Object[]> countByCategory(@Param("sido") String sido, @Param("pattern") String pattern);
}

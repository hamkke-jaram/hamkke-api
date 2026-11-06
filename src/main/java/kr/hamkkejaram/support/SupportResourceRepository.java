package kr.hamkkejaram.support;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportResourceRepository extends JpaRepository<SupportResource, Long> {
    List<SupportResource> findAllByOrderBySortOrderAscIdAsc();
}

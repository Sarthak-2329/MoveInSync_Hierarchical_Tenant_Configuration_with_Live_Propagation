package com.moveinsync.config.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the append-only change log.
 */
@Repository
public interface ChangeLogRepository extends JpaRepository<ChangeLogEntity, Long> {

    @Query("SELECT MAX(c.version) FROM ChangeLogEntity c")
    Optional<Long> findMaxVersion();

    List<ChangeLogEntity> findAllByOrderByVersionAsc();

    List<ChangeLogEntity> findByVersionGreaterThanOrderByVersionAsc(Long sinceVersion);

    List<ChangeLogEntity> findByVersionLessThanEqualOrderByVersionAsc(Long targetVersion);
}

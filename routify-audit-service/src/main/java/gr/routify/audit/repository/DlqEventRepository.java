package gr.routify.audit.repository;

import gr.routify.audit.domain.DlqEvent;
import gr.routify.audit.domain.DlqEventId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface DlqEventRepository extends JpaRepository<DlqEvent, DlqEventId> {

    Page<DlqEvent> findBySourceTopicOrderByFailedAtDesc(String sourceTopic, Pageable pageable);

    Page<DlqEvent> findByFailedAtBetweenOrderByFailedAtDesc(Instant from, Instant to, Pageable pageable);

    long countBySourceTopicAndFailedAtAfter(String sourceTopic, Instant since);

    /** Efficient bulk delete for retention — leverages partition pruning on failed_at. */
    @Modifying
    @Query(value = "DELETE FROM routify_audit.dlq_event WHERE failed_at < :cutoff", nativeQuery = true)
    int deleteByFailedAtBefore(@Param("cutoff") Instant cutoff);
}


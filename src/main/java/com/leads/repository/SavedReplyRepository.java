package com.leads.repository;
import com.leads.model.entity.SavedReply;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SavedReplyRepository extends JpaRepository<SavedReply, Long> {
    List<SavedReply> findByCompanyIdOrderByTitleAscIdAsc(Long companyId);
}

package com.leads.service;

import com.leads.model.entity.AiProviderConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class AiUsageLedger {
    private final JdbcTemplate jdbc;
    public record Ticket(String id, BigDecimal inputRate, BigDecimal outputRate) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Ticket start(AiProviderConfig config, String model, String feature) {
        // Module 18 P1 part 2 (D9): platform-owned rates; a model with no platform rate falls back to the old company rate.
        var rates = jdbc.query("SELECT input_per_million,output_per_million FROM platform_ai_model_rates WHERE provider=? AND model_name=?",
                (rs, row) -> new BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2)}, config.getProvider().name(), model);
        if (rates.isEmpty()) rates = jdbc.query("SELECT input_per_million,output_per_million FROM ai_model_rates WHERE company_id=? AND provider=? AND model_name=?",
                (rs, row) -> new BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2)}, config.getCompany().getId(), config.getProvider().name(), model);
        BigDecimal input = rates.isEmpty() ? null : rates.get(0)[0];
        BigDecimal output = rates.isEmpty() ? null : rates.get(0)[1];
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_call_usage(id,company_id,branch_id,provider,model_name,feature,started_at,status,input_per_million,output_per_million) VALUES(?,?,?,?,?,?,?,'PENDING',?,?)",
                id, config.getCompany().getId(), config.getBranch().getId(), config.getProvider().name(), model, feature,
                Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)), input, output);
        return new Ticket(id, input, output);
    }

    public static BigDecimal estimate(Ticket ticket, Integer input, Integer output) {
        if (input == null || output == null || input < 0 || output < 0 || ticket.inputRate() == null || ticket.outputRate() == null) return null;
        return ticket.inputRate().multiply(BigDecimal.valueOf(input)).add(ticket.outputRate().multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Ticket ticket, String status, Integer input, Integer output) {
        jdbc.update("UPDATE ai_call_usage SET status=?,input_tokens=?,output_tokens=?,estimated_usd=? WHERE id=? AND status='PENDING'",
                status, input, output, "SUCCEEDED".equals(status) ? estimate(ticket, input, output) : null, ticket.id());
    }
}

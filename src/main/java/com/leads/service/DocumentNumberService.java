package com.leads.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Year;

@Service
@RequiredArgsConstructor
public class DocumentNumberService {
    private final JdbcTemplate jdbc;
    @Transactional
    public String next(String type, Long companyId) {
        if(!java.util.Set.of("LD","QT","DL").contains(type)) throw new IllegalArgumentException("Invalid document type.");
        int year=Year.now(java.time.ZoneOffset.UTC).getValue();
        jdbc.update("INSERT INTO document_sequences(company_id, document_type, document_year, next_value) VALUES (?, ?, ?, 1) ON DUPLICATE KEY UPDATE next_value=next_value+1",companyId,type,year);
        Long value=jdbc.queryForObject("SELECT next_value FROM document_sequences WHERE company_id=? AND document_type=? AND document_year=? FOR UPDATE",Long.class,companyId,type,year);
        return String.format("%s-%d-%d-%06d",type,companyId,year,value);
    }
}

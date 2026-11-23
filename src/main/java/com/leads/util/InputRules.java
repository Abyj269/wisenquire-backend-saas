package com.leads.util;

import java.math.BigDecimal;
import java.util.Set;
import java.util.Base64;

public final class InputRules {
    private InputRules() {}
    public static void amount(BigDecimal value, String field) {
        if(value!=null && (value.signum()<0 || value.precision()-value.scale()>12 || value.scale()>2)) throw new IllegalArgumentException(field+" must be a nonnegative amount with at most two decimals.");
    }
    public static void percentage(BigDecimal value,String field) {
        amount(value,field);if(value!=null && value.compareTo(BigDecimal.valueOf(100))>0)throw new IllegalArgumentException(field+" cannot exceed 100%.");
    }
    public static void currency(String value) {
        if(value==null || !value.matches("[A-Z]{3}"))throw new IllegalArgumentException("Use a three-letter currency code.");
        try{java.util.Currency.getInstance(value);}catch(Exception e){throw new IllegalArgumentException("Unsupported currency.");}
    }
    public static void attachment(String name,String data) {
        if(data==null || data.isBlank())return;
        if(name==null || name.length()>150 || data.length()>2800000) throw new IllegalArgumentException("Attachments require a name and must be at most 2 MB.");
        if(!data.matches("(?s)^data:(image/(png|jpeg|gif|webp)|application/pdf|text/plain);base64,.*"))throw new IllegalArgumentException("Unsupported attachment type.");
        try { if(Base64.getDecoder().decode(data.substring(data.indexOf(',')+1)).length>2097152)throw new IllegalArgumentException(); }
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid or oversized attachment.");}
    }
    public static void logo(String data) {
        if(data==null || data.isBlank())return;
        if(!data.startsWith("data:image/png;base64,") && !data.startsWith("data:image/jpeg;base64,"))throw new IllegalArgumentException("Upload a PNG or JPEG logo; external URLs are not allowed.");
        attachment("logo",data);
    }
    public static void dateRange(java.time.LocalDate from,java.time.LocalDate to) {
        if(from!=null && to!=null && (from.isAfter(to) || java.time.temporal.ChronoUnit.DAYS.between(from,to)>3660))throw new IllegalArgumentException("Use an ordered date range of at most ten years.");
    }
    public static org.springframework.data.domain.Pageable page(org.springframework.data.domain.Pageable page, String... allowed) {
        Set<String> fields=Set.of(allowed);
        for(var sort:page.getSort())if(!fields.contains(sort.getProperty()))throw new IllegalArgumentException("Unsupported sort field.");
        return org.springframework.data.domain.PageRequest.of(Math.max(0,page.getPageNumber()),Math.min(100,Math.max(1,page.getPageSize())),page.getSort());
    }
}

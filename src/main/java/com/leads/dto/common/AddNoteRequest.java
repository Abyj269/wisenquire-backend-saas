package com.leads.dto.common;

import lombok.*;

/**
 * Adds a free-text note — optionally with one small file attachment — to a
 * Lead's or Customer's activity log. Shared shape since both logs work
 * identically. The attachment travels as a data: URI in a plain JSON body
 * rather than multipart/form-data, which keeps this on the same simple
 * request pattern as the rest of the API; the frontend caps the file size
 * before ever encoding it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddNoteRequest {
    private String note;
    private String attachmentName;
    private String attachmentData;
}

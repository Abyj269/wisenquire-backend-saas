package com.leads.dto.whatsapp;

import lombok.*;

import java.util.List;

/**
 * Response for GET /whatsapp/conversations. A plain {@code Page<T>} isn't
 * enough on its own because the filter pills need company/number-wide counts
 * that ignore both pagination and the active filter/search — bundling both
 * into one response avoids a second round trip on every list load.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationListResponseDto {
    private List<WhatsAppConversationDto> content;
    private long totalElements;
    private boolean hasMore;
    private ConversationCountsDto counts;
}

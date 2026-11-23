package com.leads.exception;

import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import static org.junit.jupiter.api.Assertions.*;

class UploadErrorNotificationTest {
    @Test void oversizedUploadReturnsUseful413Message() {
        var response = new GlobalExceptionHandler().handleUploadTooLarge(new MaxUploadSizeExceededException(100));
        assertEquals(413, response.getStatusCode().value());
        assertTrue(response.getBody().get("message").contains("Choose a smaller file"));
    }
    @Test void unsupportedFileValidationMessageReachesClient() {
        var response = new GlobalExceptionHandler().handleIllegalArgument(new IllegalArgumentException("This file format is not supported by WhatsApp."));
        assertEquals(400, response.getStatusCode().value());
        assertEquals("This file format is not supported by WhatsApp.", response.getBody().get("message"));
    }
}

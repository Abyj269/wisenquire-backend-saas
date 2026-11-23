package com.leads.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WhatsAppAttachmentPolicyTest {
    @Test void missingMimeUsesDocumentExtension() {
        assertEquals("application/pdf", WhatsAppAttachmentPolicy.validate(new byte[]{1}, "QUOTE.PDF", null, "Quote attached"));
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", WhatsAppAttachmentPolicy.validate(new byte[]{1}, "quote.docx", "application/octet-stream", null));
    }
    @Test void screenshotAndCaptionAreAccepted() {
        assertEquals("image/png", WhatsAppAttachmentPolicy.validate(new byte[]{1}, "image.png", "image/png", "See screenshot"));
    }
    @Test void invalidUploadsAreRejectedBeforeProviderCall() {
        assertThrows(IllegalArgumentException.class, () -> WhatsAppAttachmentPolicy.validate(new byte[0], "x.pdf", "application/pdf", null));
        assertThrows(IllegalArgumentException.class, () -> WhatsAppAttachmentPolicy.validate(new byte[]{1}, "x.exe", null, null));
        assertThrows(IllegalArgumentException.class, () -> WhatsAppAttachmentPolicy.validate(new byte[5 * 1024 * 1024 + 1], "x.png", "image/png", null));
        assertThrows(IllegalArgumentException.class, () -> WhatsAppAttachmentPolicy.validate(new byte[]{1}, "x.pdf", "application/pdf", "x".repeat(1025)));
        assertThrows(IllegalArgumentException.class, () -> WhatsAppAttachmentPolicy.validate(new byte[]{1}, "x.mp3", "audio/mpeg", "Do not silently drop this text"));
    }
}

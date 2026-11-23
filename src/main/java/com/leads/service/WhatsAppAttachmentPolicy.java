package com.leads.service;

import java.util.Locale;
import java.util.Map;

/** Validate uploads before contacting Meta, including files with missing browser MIME metadata. */
final class WhatsAppAttachmentPolicy {
    private WhatsAppAttachmentPolicy() {}
    private static final Map<String, String> TYPES = Map.ofEntries(
        Map.entry("pdf", "application/pdf"), Map.entry("txt", "text/plain"),
        Map.entry("doc", "application/msword"), Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        Map.entry("xls", "application/vnd.ms-excel"), Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        Map.entry("ppt", "application/vnd.ms-powerpoint"), Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
        Map.entry("mp4", "video/mp4"), Map.entry("3gp", "video/3gpp"), Map.entry("mp3", "audio/mpeg"),
        Map.entry("m4a", "audio/mp4"), Map.entry("aac", "audio/aac"), Map.entry("amr", "audio/amr"), Map.entry("ogg", "audio/ogg")
    );

    static String validate(byte[] bytes, String filename, String contentType, String caption) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("The attachment is empty.");
        String mime = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (mime.isEmpty() || mime.equals("application/octet-stream")) {
            String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
            mime = TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1), "");
        }
        if (!TYPES.containsValue(mime)) throw new IllegalArgumentException("This file format is not supported by WhatsApp. Use a supported image, video, audio, PDF, text or Office document.");
        int limit = mime.startsWith("image/") ? 5 : (mime.startsWith("audio/") || mime.startsWith("video/")) ? 16 : 100;
        if (bytes.length > limit * 1024 * 1024) throw new IllegalArgumentException("The attachment exceeds the " + limit + " MB limit.");
        if (caption != null && !caption.isBlank()) {
            if (mime.startsWith("audio/")) throw new IllegalArgumentException("Audio cannot include a caption. Send the text separately.");
            if (caption.codePointCount(0, caption.length()) > 1024) throw new IllegalArgumentException("Attachment captions can contain up to 1,024 characters.");
        }
        return mime;
    }
}

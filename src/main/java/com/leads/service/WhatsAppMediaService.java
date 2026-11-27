package com.leads.service;

import com.leads.exception.WhatsAppApiException;
import com.leads.model.entity.WhatsAppConfiguration;
import com.leads.model.entity.WhatsAppConversation;
import com.leads.model.entity.WhatsAppMessage;
import com.leads.repository.WhatsAppConfigurationRepository;
import com.leads.repository.WhatsAppMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves a WhatsApp message's attached media to actual bytes, lazily and
 * with local-disk caching.
 *
 * Meta's media "url" (from GET /{media-id}) is a short-lived signed link —
 * downloading eagerly at webhook time would both slow down webhook
 * processing (Meta expects a fast 200 OK per delivery, and re-tries on
 * timeout) and waste bandwidth/storage on media nobody ever opens. Instead
 * the webhook only stores Meta's media id, and this service resolves +
 * downloads + caches on the first actual view, so a re-view is a plain disk
 * read with no Graph API call at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppMediaService {
    private final org.springframework.transaction.PlatformTransactionManager transactions;
    private final TenantAccess access;

    private final WhatsAppMessageRepository messageRepo;
    private final WhatsAppConfigurationRepository configRepo;
    private final WhatsAppGraphApiClient graphApiClient;

    private static final Path CACHE_DIR = Paths.get("whatsapp-media-cache");

    public record CachedMedia(byte[] data, String mimeType, String filename) {}

    public CachedMedia resolve(Long messageId) {
        access.record(com.leads.model.entity.WhatsAppMessage.class, messageId);
        WhatsAppMessage msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        if (msg.getMediaId() == null) {
            throw new IllegalArgumentException("Message " + messageId + " has no media attached.");
        }

        Path cachedFile = cachedFilePath(messageId, msg.getMediaMimeType());
        if (Files.exists(cachedFile)) {
            try {
                return new CachedMedia(Files.readAllBytes(cachedFile), msg.getMediaMimeType(), msg.getMediaFileName());
            } catch (IOException ex) {
                log.warn("Failed reading cached media file {} — refetching from Meta.", cachedFile, ex);
            }
        }

        WhatsAppConversation conv = msg.getConversation();
        WhatsAppConfiguration config = conv.getReceivingPhoneNumberId() != null
                ? configRepo.findByPhoneNumberId(conv.getReceivingPhoneNumberId()).orElse(null)
                : null;
        if (config == null) {
            throw new WhatsAppApiException("This conversation's WhatsApp number isn't connected — can't fetch media.");
        }

        String[] mimeHolder = new String[1];
        byte[] data = graphApiClient.downloadMedia(config, msg.getMediaId(), mimeHolder);
        String mimeType = mimeHolder[0] != null ? mimeHolder[0] : msg.getMediaMimeType();

        cacheToDisk(messageId, mimeType, data);
        return new CachedMedia(data, mimeType, msg.getMediaFileName());
    }

    /**
     * Module 18 P1 (S3-b): {@link #resolve} for callers that must not hold a transaction during the Meta download
     * (voice-note transcription on the short path). The message and its number are read in a short read-only
     * transaction; the download runs with none open.
     */
    public CachedMedia resolveWithoutTransaction(Long messageId) {
        record Source(String mediaId, String mime, String fileName, WhatsAppConfiguration config) { }
        var read = new org.springframework.transaction.support.TransactionTemplate(transactions);
        read.setReadOnly(true);
        Source src = read.execute(st -> {
            access.record(com.leads.model.entity.WhatsAppMessage.class, messageId);
            WhatsAppMessage msg = messageRepo.findById(messageId)
                    .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
            if (msg.getMediaId() == null) throw new IllegalArgumentException("Message " + messageId + " has no media attached.");
            String number = msg.getConversation().getReceivingPhoneNumberId();
            WhatsAppConfiguration config = number != null ? configRepo.findByPhoneNumberId(number).orElse(null) : null;
            if (config != null) config.getAccessToken(); // loaded now: used detached for the download
            return new Source(msg.getMediaId(), msg.getMediaMimeType(), msg.getMediaFileName(), config);
        });
        Path cachedFile = cachedFilePath(messageId, src.mime());
        if (Files.exists(cachedFile)) {
            try {
                return new CachedMedia(Files.readAllBytes(cachedFile), src.mime(), src.fileName());
            } catch (IOException ex) {
                log.warn("Failed reading cached media file {} — refetching from Meta.", cachedFile, ex);
            }
        }
        if (src.config() == null) throw new WhatsAppApiException("This conversation's WhatsApp number isn't connected — can't fetch media.");
        String[] mimeHolder = new String[1];
        byte[] data = graphApiClient.downloadMedia(src.config(), src.mediaId(), mimeHolder);
        String mimeType = mimeHolder[0] != null ? mimeHolder[0] : src.mime();
        cacheToDisk(messageId, mimeType, data);
        return new CachedMedia(data, mimeType, src.fileName());
    }

    /** Called right after an outbound send, when we already hold the bytes — skips a wasted Meta round trip on first view. */
    public void cacheOutboundMedia(Long messageId, String mimeType, byte[] data) {
        cacheToDisk(messageId, mimeType, data);
    }

    private void cacheToDisk(Long messageId, String mimeType, byte[] data) {
        try {
            Files.createDirectories(CACHE_DIR);
            Files.write(cachedFilePath(messageId, mimeType), data);
        } catch (IOException ex) {
            log.warn("Failed caching media for message {} to disk — will re-fetch next time.", messageId, ex);
        }
    }

    private Path cachedFilePath(Long messageId, String mimeType) {
        return CACHE_DIR.resolve(messageId + extensionFor(mimeType));
    }

    private String extensionFor(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) return ".bin";
        String subtype = mimeType.split(";")[0].trim();
        int slash = subtype.indexOf('/');
        String ext = (slash >= 0 ? subtype.substring(slash + 1) : subtype).replaceAll("[^a-zA-Z0-9]", "");
        return ext.isBlank() ? ".bin" : "." + ext;
    }
}

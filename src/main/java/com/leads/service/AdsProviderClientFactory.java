package com.leads.service;

import com.leads.model.enums.AdProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Resolves the right {@link AdsProviderClient} for a connection's provider — Spring autowires every implementation, indexed once by {@link AdsProviderClient#getProvider()}. */
@Service
@RequiredArgsConstructor
public class AdsProviderClientFactory {

    private final List<AdsProviderClient> clients;
    private Map<AdProvider, AdsProviderClient> byProvider;

    public AdsProviderClient resolve(AdProvider provider) {
        if (byProvider == null) {
            Map<AdProvider, AdsProviderClient> map = new EnumMap<>(AdProvider.class);
            for (AdsProviderClient client : clients) {
                map.put(client.getProvider(), client);
            }
            byProvider = map;
        }
        AdsProviderClient client = byProvider.get(provider);
        if (client == null) {
            throw new IllegalStateException("No AdsProviderClient registered for provider " + provider);
        }
        return client;
    }
}

package edu.psu.giscience.igdd.memory;

import edu.psu.giscience.igdd.domain.intent.GeoIntent;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GeoIntentStore {

    private final ConcurrentHashMap<UUID, GeoIntent> store = new ConcurrentHashMap<>();

    public GeoIntent get(UUID conversationId) {
        if (conversationId == null) return null;
        return store.get(conversationId);
    }

    public void put(UUID conversationId, GeoIntent intent) {
        if (conversationId == null) return;
        if (intent == null) store.remove(conversationId);
        else store.put(conversationId, intent);
    }

    public void clear(UUID conversationId) {
        if (conversationId == null) return;
        store.remove(conversationId);
    }
}

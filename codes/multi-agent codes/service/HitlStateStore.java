package edu.psu.giscience.igdd.service;

import edu.psu.giscience.igdd.domain.graphrag.PendingHitl;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HitlStateStore {

    private final ConcurrentHashMap<UUID, PendingHitl> pending = new ConcurrentHashMap<>();

    public PendingHitl get(UUID conversationId) {
        if (conversationId == null) return null;
        return pending.get(conversationId);
    }

    public void put(UUID conversationId, PendingHitl p) {
        if (conversationId == null) return;
        if (p == null) pending.remove(conversationId);
        else pending.put(conversationId, p);
    }

    public void clear(UUID conversationId) {
        if (conversationId == null) return;
        pending.remove(conversationId);
    }
}

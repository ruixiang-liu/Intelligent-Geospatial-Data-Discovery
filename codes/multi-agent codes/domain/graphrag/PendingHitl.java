package edu.psu.giscience.igdd.domain.graphrag;

import java.util.List;

public record PendingHitl(
        HitlSlot slot,
        String question,
        List<CandidateEntity> candidates
) {}

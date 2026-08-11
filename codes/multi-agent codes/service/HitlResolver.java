package edu.psu.giscience.igdd.service;

import edu.psu.giscience.igdd.domain.graphrag.CandidateEntity;
import edu.psu.giscience.igdd.domain.graphrag.HitlSlot;
import edu.psu.giscience.igdd.domain.graphrag.PendingHitl;
import edu.psu.giscience.igdd.domain.intent.GeoIntent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class HitlResolver {

    private static final Pattern FIRST_INT = Pattern.compile("\\b(\\d{1,3})\\b");
    private static final Pattern MULTI_INT = Pattern.compile("\\b(\\d{1,3})\\b");  // For parsing multiple selections like "1,3,5" or "1 3 5"
    private static final Pattern KV = Pattern.compile("(?i)\\b(topic|space|format|license|organization|source)\\s*=\\s*(.+)$");
    
    private final SpaceTimeNormalizationService spaceTimeNormalizer;

    public HitlResolver(SpaceTimeNormalizationService spaceTimeNormalizer) {
        this.spaceTimeNormalizer = spaceTimeNormalizer;
    }

    public GeoIntent applyUserAnswer(GeoIntent intent, PendingHitl pending, String userAnswer, String apiKey, String questionId) {
        if (intent == null || pending == null) return intent;
        if (userAnswer == null) userAnswer = "";
        userAnswer = userAnswer.trim();

        HitlSlot slot = pending.slot();
        switch (slot) {
            case TOPIC -> resolveEntityDim(ensureEntity(intent, HitlSlot.TOPIC), pending.candidates(), userAnswer);
            case SPACE -> resolveSpaceDim(ensureSpace(intent), pending.candidates(), userAnswer, apiKey, questionId);
            case FORMAT -> resolveEntityDim(ensureEntity(intent, HitlSlot.FORMAT), pending.candidates(), userAnswer);
            case LICENSE -> resolveEntityDim(ensureEntity(intent, HitlSlot.LICENSE), pending.candidates(), userAnswer);
            case ORGANIZATION -> resolveEntityDim(ensureEntity(intent, HitlSlot.ORGANIZATION), pending.candidates(), userAnswer);
            case SOURCE -> resolveEntityDim(ensureEntity(intent, HitlSlot.SOURCE), pending.candidates(), userAnswer);
            case TIME -> resolveTimeDim(ensureTime(intent), userAnswer, apiKey, questionId);
        }
        return intent;
    }

    private GeoIntent.EntityDim ensureEntity(GeoIntent intent, HitlSlot slot) {
        return switch (slot) {
            case TOPIC -> {
                if (intent.getTopic() == null) intent.setTopic(new GeoIntent.EntityDim());
                yield intent.getTopic();
            }
            case FORMAT -> {
                if (intent.getFormat() == null) intent.setFormat(new GeoIntent.EntityDim());
                yield intent.getFormat();
            }
            case LICENSE -> {
                if (intent.getLicense() == null) intent.setLicense(new GeoIntent.EntityDim());
                yield intent.getLicense();
            }
            case ORGANIZATION -> {
                if (intent.getOrganization() == null) intent.setOrganization(new GeoIntent.EntityDim());
                yield intent.getOrganization();
            }
            case SOURCE -> {
                if (intent.getSource() == null) intent.setSource(new GeoIntent.EntityDim());
                yield intent.getSource();
            }
            default -> new GeoIntent.EntityDim();
        };
    }

    private GeoIntent.SpaceDim ensureSpace(GeoIntent intent) {
        if (intent.getSpace() == null) intent.setSpace(new GeoIntent.SpaceDim());
        return intent.getSpace();
    }

    private GeoIntent.TimeDim ensureTime(GeoIntent intent) {
        if (intent.getTime() == null) intent.setTime(new GeoIntent.TimeDim());
        return intent.getTime();
    }

    private void resolveEntityDim(GeoIntent.EntityDim dim, List<CandidateEntity> cand, String ansRaw) {
        if (dim == null) return;

        String ans = normalizeAnswer(ansRaw);

        // Check if user selected multiple candidates (e.g., "1,3,5" or "1 3 5" or "continue")
        String lowerAns = ans.toLowerCase(Locale.ROOT).trim();
        if (lowerAns.equals("continue") || lowerAns.isEmpty()) {
            // User clicked Continue or didn't select: use all top-5 candidates
            if (cand != null && !cand.isEmpty()) {
                List<String> nodeIds = new ArrayList<>();
                for (CandidateEntity c : cand) {
                    if (c != null && c.nodeId() != null) {
                        nodeIds.add(c.nodeId());
                    }
                }
                dim.setKgNodeIds(nodeIds);
                dim.setKgNodeId(nodeIds.isEmpty() ? null : nodeIds.get(0));  // Keep first as primary
                dim.setValue(cand.get(0).name());  // Use first candidate name
                dim.setNeedsClarification(false);
                dim.setConfidence(0.8);  // Slightly lower confidence for multi-select
                return;
            }
        }

        // Try to parse multiple selections (e.g., "1,3,5" or "1 3 5")
        List<CandidateEntity> selected = pickMultipleCandidates(cand, ans);
        if (selected != null && !selected.isEmpty()) {
            List<String> nodeIds = new ArrayList<>();
            for (CandidateEntity c : selected) {
                if (c != null && c.nodeId() != null) {
                    nodeIds.add(c.nodeId());
                }
            }
            dim.setKgNodeIds(nodeIds);
            dim.setKgNodeId(nodeIds.isEmpty() ? null : nodeIds.get(0));  // Keep first as primary
            dim.setValue(selected.get(0).name());  // Use first selected name
            dim.setNeedsClarification(false);
            dim.setConfidence(1.0);
            return;
        }

        // Try single selection (backward compatibility)
        CandidateEntity singleSelected = pickCandidate(cand, ans);
        if (singleSelected != null) {
            dim.setKgNodeId(singleSelected.nodeId());
            dim.setKgNodeIds(List.of(singleSelected.nodeId()));  // Single selection as list
            dim.setValue(singleSelected.name());
            dim.setNeedsClarification(false);
            dim.setConfidence(1.0);
            return;
        }

        // accept explicit key=value as authoritative (no more HITL loops)
        Matcher kv = KV.matcher(ansRaw.trim());
        if (kv.find()) {
            ans = kv.group(2).trim();
            dim.setValue(ans);
            dim.setKgNodeId(null);
            dim.setKgNodeIds(null);
            dim.setNeedsClarification(false);
            dim.setConfidence(Math.max(dim.getConfidence(), 0.95));
            return;
        }

        dim.setValue(ans);
        dim.setNeedsClarification(true);
    }

    private void resolveSpaceDim(GeoIntent.SpaceDim dim, List<CandidateEntity> cand, String ansRaw, String apiKey, String questionId) {
        if (dim == null) return;

        String ans = normalizeAnswer(ansRaw);

        // Try to pick from candidates first
        CandidateEntity selected = pickCandidate(cand, ans);
        if (selected != null) {
            dim.setKgNodeId(selected.nodeId());
            dim.setValue(selected.name());
            dim.setNeedsClarification(false);
            dim.setConfidence(1.0);
            return;
        }

        // Try key=value format
        Matcher kv = KV.matcher(ansRaw.trim());
        if (kv.find()) {
            ans = kv.group(2).trim();
        }

        // Normalize place name to bbox (hard filter, no embedding)
        double[] bbox = SpaceTimeNormalizationService.parseBbox(ans);
        if (bbox == null && spaceTimeNormalizer != null) {
            // Pass apiKey and questionId for proper LLM tracking
            SpaceTimeNormalizationService.NormalizedSpace ns = spaceTimeNormalizer.normalizeSpace(ans, null, apiKey, questionId);
            if (ns != null && ns.bbox() != null && ns.bbox().length == 4) {
                bbox = ns.bbox();
                if (ns.nameEn() != null && !ns.nameEn().isEmpty()) {
                    ans = ns.nameEn();
                }
            }
        }

        dim.setValue(ans);
        if (bbox != null && bbox.length == 4) {
            dim.setBbox(bbox);
            dim.setNeedsClarification(false);
            dim.setConfidence(Math.max(dim.getConfidence(), 0.95));
        } else {
            dim.setNeedsClarification(true);
        }
    }

    private void resolveTimeDim(GeoIntent.TimeDim dim, String ans, String apiKey, String questionId) {
        if (dim == null) return;

        // Try deterministic parsing first
        SpaceTimeNormalizationService.NormalizedTime nt = 
                SpaceTimeNormalizationService.parseDeterministicTime(ans);
        
        if (nt != null && nt.start() != null && nt.end() != null) {
            dim.setType(nt.type());
            dim.setStart(nt.start());
            dim.setEnd(nt.end());
            dim.setGranularity(nt.granularity());
            dim.setNeedsClarification(false);
            dim.setConfidence(Math.max(dim.getConfidence(), nt.confidence()));
        } else if (spaceTimeNormalizer != null) {
            // Try LLM fallback - pass apiKey and questionId for proper LLM tracking
            SpaceTimeNormalizationService.NormalizedTime nt2 = spaceTimeNormalizer.normalizeTime(ans, null, apiKey, questionId);
            if (nt2 != null && nt2.start() != null && nt2.end() != null) {
                dim.setType(nt2.type());
                dim.setStart(nt2.start());
                dim.setEnd(nt2.end());
                dim.setGranularity(nt2.granularity());
                dim.setNeedsClarification(false);
                dim.setConfidence(Math.max(dim.getConfidence(), nt2.confidence()));
            } else {
                dim.setRawText(ans);
                dim.setNeedsClarification(true);
            }
        } else {
            dim.setRawText(ans);
            dim.setNeedsClarification(true);
        }
    }

    private List<CandidateEntity> pickMultipleCandidates(List<CandidateEntity> cand, String ansRaw) {
        if (cand == null || cand.isEmpty()) return null;
        if (ansRaw == null) ansRaw = "";
        String ans = ansRaw.trim();

        // Parse multiple integers (e.g., "1,3,5" or "1 3 5" or "1, 3, 5")
        List<Integer> indices = new ArrayList<>();
        Matcher mi = MULTI_INT.matcher(ans);
        while (mi.find()) {
            try {
                int idx = Integer.parseInt(mi.group(1));
                if (idx >= 1 && idx <= cand.size() && !indices.contains(idx - 1)) {
                    indices.add(idx - 1);
                }
            } catch (Exception ignored) {}
        }

        if (!indices.isEmpty()) {
            List<CandidateEntity> selected = new ArrayList<>();
            for (int idx : indices) {
                selected.add(cand.get(idx));
            }
            return selected;
        }

        return null;
    }

    private CandidateEntity pickCandidate(List<CandidateEntity> cand, String ansRaw) {
        if (cand == null || cand.isEmpty()) return null;
        if (ansRaw == null) ansRaw = "";
        String ans = ansRaw.trim();

        // 1) first integer anywhere in the answer
        Matcher mi = FIRST_INT.matcher(ans);
        if (mi.find()) {
            try {
                int idx = Integer.parseInt(mi.group(1));
                if (idx >= 1 && idx <= cand.size()) return cand.get(idx - 1);
            } catch (Exception ignored) {}
        }

        // 2) try exact-ish name match
        String lower = ans.toLowerCase(Locale.ROOT);
        for (CandidateEntity c : cand) {
            if (c.name() == null) continue;
            String cn = c.name().toLowerCase(Locale.ROOT);
            if (cn.equals(lower)) return c;
        }
        for (CandidateEntity c : cand) {
            if (c.name() == null) continue;
            String cn = c.name().toLowerCase(Locale.ROOT);
            if (cn.contains(lower) || lower.contains(cn)) return c;
        }

        return null;
    }

    private String normalizeAnswer(String ans) {
        if (ans == null) return "";
        ans = ans.trim();
        // remove common UI prefix
        if (ans.toLowerCase(Locale.ROOT).startsWith("hitl selection")) {
            Matcher m = FIRST_INT.matcher(ans);
            if (m.find()) return m.group(1);
        }
        return ans;
    }
}

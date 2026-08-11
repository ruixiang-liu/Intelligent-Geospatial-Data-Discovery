package edu.psu.giscience.igdd.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.psu.giscience.igdd.llm.LlmClientService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalize user-provided Space/Time expressions into:
 *  - Space: EPSG:4326 bbox = [minLon, minLat, maxLon, maxLat]
 *  - Time:  YYYYMMDD HH:mm:ss (e.g., 18420801 00:00:00)
 *
 * Notes:
 *  - Deterministic parsing first; LLM fallback only when needed.
 *  - All outputs must be English.
 */
@Service
public class SpaceTimeNormalizationService {

    public record NormalizedSpace(String nameEn, double[] bbox, double confidence) {}
    public record NormalizedTime(String type, String start, String end, String granularity, double confidence) {}

    private final LlmClientService llm;
    private final ObjectMapper om = new ObjectMapper();

    // bbox= -75,39,-74,40   OR   -75,39,-74,40
    private static final Pattern BBOX_4 = Pattern.compile(
            "(?i)(?:bbox\\s*=\\s*)?" +
                    "([-+]?\\d+(?:\\.\\d+)?)\\s*,\\s*" +
                    "([-+]?\\d+(?:\\.\\d+)?)\\s*,\\s*" +
                    "([-+]?\\d+(?:\\.\\d+)?)\\s*,\\s*" +
                    "([-+]?\\d+(?:\\.\\d+)?)"
    );

    // time=2018-01-01 to 2020-12-31  (also supports ~, -, —)
    private static final Pattern RANGE = Pattern.compile(
            "(?i)(?:time\\s*=\\s*)?" +
                    "(\\d{4}[-/]?\\d{2}[-/]?\\d{2}|\\d{8}|\\d{4})\\s*(?:to|~|—|-)\\s*" +
                    "(\\d{4}[-/]?\\d{2}[-/]?\\d{2}|\\d{8}|\\d{4})"
    );

    private static final Pattern YEAR_ONLY = Pattern.compile("(?i)^(?:time\\s*=\\s*)?(\\d{4})$");

    private static final DateTimeFormatter ISO1 = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter ISO2 = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public SpaceTimeNormalizationService(LlmClientService llm) {
        this.llm = llm;
    }

    /** Normalize a place name or bbox text into EPSG:4326 bbox. */
    public NormalizedSpace normalizeSpace(String raw, String modelOverrideOrNull, String apiKey, String questionId) {
        raw = raw == null ? "" : raw.trim();
        if (raw.isEmpty()) return null;

        // Deterministic: user directly gave bbox
        double[] bb = parseBbox(raw);
        if (bb != null) {
            return new NormalizedSpace("", bb, 1.0);
        }

        // LLM fallback: place name -> bbox (EPSG:4326)
        // Retry up to 3 times (4 attempts total) until bbox is successfully parsed
        String promptTemplate = """
                You normalize a geographic place expression into an EPSG:4326 (WGS84) bounding box.

                Input: %s

                Output requirements:
                - Output MUST be valid JSON ONLY (no markdown).
                - Output language MUST be English.
                - bbox must be [minLon, minLat, maxLon, maxLat] in EPSG:4326.
                - You MUST provide a valid bbox. Do not return null unless absolutely impossible.

                JSON schema:
                {
                  "name_en": "<canonical English name or empty>",
                  "bbox": [minLon, minLat, maxLon, maxLat] | null,
                  "confidence": 0.0-1.0
                }
                """;

        int maxAttempts = 4; // Initial attempt + 3 retries
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String prompt = promptTemplate.formatted(raw);
                String s = llm.askPlain(prompt, modelOverrideOrNull, apiKey, questionId);
                JsonNode n = om.readTree(s);
                String nameEn = n.path("name_en").asText("");
                double conf = n.path("confidence").asDouble(0.0);

                if (n.has("bbox") && n.get("bbox").isArray() && n.get("bbox").size() == 4) {
                    double[] out = new double[4];
                    for (int i = 0; i < 4; i++) out[i] = n.get("bbox").get(i).asDouble();
                    out = sanitizeBbox(out);
                    // If bbox is successfully parsed, use it regardless of LLM's confidence
                    // Set confidence to 1.0 since we have a valid bbox
                    if (attempt > 1) {
                        System.out.println("[Space Normalization] Successfully parsed bbox on attempt " + attempt + " for: " + raw);
                    }
                    return new NormalizedSpace(nameEn, out, 1.0);
                }
                
                // If this is not the last attempt, continue to retry
                if (attempt < maxAttempts) {
                    System.out.println("[Space Normalization] Attempt " + attempt + " failed to parse bbox for: " + raw + ", retrying...");
                    continue;
                }
                
                // Last attempt failed, return with null bbox
                return new NormalizedSpace(nameEn, null, conf);
            } catch (Exception e) {
                // If this is not the last attempt, continue to retry
                if (attempt < maxAttempts) {
                    System.out.println("[Space Normalization] Attempt " + attempt + " threw exception for: " + raw + ", retrying...");
                    continue;
                }
                // Last attempt failed with exception
                return null;
            }
        }
        
        // Should not reach here, but return null as fallback
        return null;
    }

    /** Normalize time expression into closed range with standard format YYYYMMDD HH:mm:ss. */
    public NormalizedTime normalizeTime(String raw, String modelOverrideOrNull, String apiKey, String questionId) {
        raw = raw == null ? "" : raw.trim();
        if (raw.isEmpty()) return null;

        // Deterministic first
        NormalizedTime det = parseDeterministicTime(raw);
        if (det != null) return det;

        // Retry up to 3 times (4 attempts total) until time range is successfully parsed
        String promptTemplate = """
                You normalize a time expression into a CLOSED date range.

                Input: %s

                Output requirements:
                - Output MUST be valid JSON ONLY (no markdown).
                - Output language MUST be English.
                - Use sortable time strings: YYYYMMDD HH:mm:ss
                - You MUST provide both start and end dates. Do not return null unless absolutely impossible.

                JSON schema:
                {
                  "type": "range|instant|year|month|unspecified",
                  "start": "YYYYMMDD HH:mm:ss" | null,
                  "end": "YYYYMMDD HH:mm:ss" | null,
                  "granularity": "day|month|year|unspecified",
                  "confidence": 0.0-1.0
                }
                """;

        int maxAttempts = 4; // Initial attempt + 3 retries
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String prompt = promptTemplate.formatted(raw);
                String s = llm.askPlain(prompt, modelOverrideOrNull, apiKey, questionId);
                JsonNode n = om.readTree(s);

                String type = n.path("type").asText("unspecified");
                String start = n.path("start").isNull() ? null : n.path("start").asText(null);
                String end = n.path("end").isNull() ? null : n.path("end").asText(null);
                String gran = n.path("granularity").asText("unspecified");
                double conf = n.path("confidence").asDouble(0.0);

                // If time range is successfully parsed (both start and end are present), 
                // use it regardless of LLM's confidence. Set confidence to 1.0 since we have valid time range.
                if (start != null && end != null) {
                    if (attempt > 1) {
                        System.out.println("[Time Normalization] Successfully parsed time range on attempt " + attempt + " for: " + raw);
                    }
                    return new NormalizedTime(type, start, end, gran, 1.0);
                }
                
                // If this is not the last attempt, continue to retry
                if (attempt < maxAttempts) {
                    System.out.println("[Time Normalization] Attempt " + attempt + " failed to parse time range for: " + raw + ", retrying...");
                    continue;
                }
                
                // Last attempt failed, return with null start/end
                return new NormalizedTime(type, start, end, gran, conf);
            } catch (Exception e) {
                // If this is not the last attempt, continue to retry
                if (attempt < maxAttempts) {
                    System.out.println("[Time Normalization] Attempt " + attempt + " threw exception for: " + raw + ", retrying...");
                    continue;
                }
                // Last attempt failed with exception
                return null;
            }
        }
        
        // Should not reach here, but return null as fallback
        return null;
    }

    // -----------------------
    // Deterministic parsing
    // -----------------------

    public static double[] parseBbox(String s) {
        if (s == null) return null;
        Matcher m = BBOX_4.matcher(s);
        if (!m.find()) return null;
        try {
            double minLon = Double.parseDouble(m.group(1));
            double minLat = Double.parseDouble(m.group(2));
            double maxLon = Double.parseDouble(m.group(3));
            double maxLat = Double.parseDouble(m.group(4));
            return sanitizeBbox(new double[]{minLon, minLat, maxLon, maxLat});
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] sanitizeBbox(double[] bb) {
        if (bb == null || bb.length != 4) return bb;
        double minLon = bb[0], minLat = bb[1], maxLon = bb[2], maxLat = bb[3];
        if (minLon > maxLon) { double t = minLon; minLon = maxLon; maxLon = t; }
        if (minLat > maxLat) { double t = minLat; minLat = maxLat; maxLat = t; }
        return new double[]{minLon, minLat, maxLon, maxLat};
    }

    public static NormalizedTime parseDeterministicTime(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // year range: 2018-2020 or 2018 to 2020
        Matcher mr = RANGE.matcher(s);
        if (mr.find()) {
            String g1 = mr.group(1);
            String g2 = mr.group(2);
            // Check if both are years (4 digits)
            if (g1.matches("\\d{4}") && g2.matches("\\d{4}")) {
                int y1 = Integer.parseInt(g1);
                int y2 = Integer.parseInt(g2);
                if (y1 > y2) { int t = y1; y1 = y2; y2 = t; }
                String start = format(LocalDate.of(y1, 1, 1), false);
                String end = format(LocalDate.of(y2, 12, 31), true);
                return new NormalizedTime("range", start, end, "year", 1.0);
            }
            // Try parsing as full dates
            LocalDate a = parseDate(g1);
            LocalDate b = parseDate(g2);
            if (a != null && b != null) {
                if (a.isAfter(b)) { LocalDate t = a; a = b; b = t; }
                String start = format(a, false);
                String end = format(b, true);
                return new NormalizedTime("range", start, end, "day", 1.0);
            }
        }

        // year only
        Matcher my = YEAR_ONLY.matcher(s);
        if (my.matches()) {
            int y = Integer.parseInt(my.group(1));
            String start = format(LocalDate.of(y, 1, 1), false);
            String end = format(LocalDate.of(y, 12, 31), true);
            return new NormalizedTime("year", start, end, "year", 1.0);
        }

        // single date
        LocalDate d = parseDate(s);
        if (d != null) {
            String start = format(d, false);
            String end = format(d, true);
            return new NormalizedTime("instant", start, end, "day", 1.0);
        }

        return null;
    }

    private static LocalDate parseDate(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;

        if (t.matches("\\d{8}")) {
            try { return LocalDate.parse(t, COMPACT); } catch (DateTimeParseException ignored) {}
        }
        if (t.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try { return LocalDate.parse(t, ISO1); } catch (DateTimeParseException ignored) {}
        }
        if (t.matches("\\d{4}/\\d{2}/\\d{2}")) {
            try { return LocalDate.parse(t, ISO2); } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private static String format(LocalDate d, boolean endOfDay) {
        String date = String.format(Locale.ROOT, "%04d%02d%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
        return endOfDay ? (date + " 23:59:59") : (date + " 00:00:00");
    }
}

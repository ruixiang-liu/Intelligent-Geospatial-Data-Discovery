package edu.psu.giscience.igdd.domain.intent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GeoIntent {

    /**
     * Recognized dimensions (only these are allowed):
     * topic, space, time, format, license, organization, source
     *
     * Rule:
     * - if any dimension is recognized (non-null), it must be HITL-resolved (entity dims -> kg_node_id, time -> explicit range)
     */

    private EntityDim topic;
    // Dataset dimension removed
    // private EntityDim dataset;

    private SpaceDim space;
    private TimeDim time;

    private EntityDim format;
    private EntityDim license;
    private EntityDim organization;
    private EntityDim source;

    @JsonProperty("questions_for_user")
    private List<String> questionsForUser = new ArrayList<>();

    @JsonProperty("overall_confidence")
    private double overallConfidence;

    public GeoIntent() {}

    public EntityDim getTopic() { return topic; }
    public void setTopic(EntityDim topic) { this.topic = topic; }

    // Dataset dimension removed
    // public EntityDim getDataset() { return dataset; }
    // public void setDataset(EntityDim dataset) { this.dataset = dataset; }

    public SpaceDim getSpace() { return space; }
    public void setSpace(SpaceDim space) { this.space = space; }

    public TimeDim getTime() { return time; }
    public void setTime(TimeDim time) { this.time = time; }

    public EntityDim getFormat() { return format; }
    public void setFormat(EntityDim format) { this.format = format; }

    public EntityDim getLicense() { return license; }
    public void setLicense(EntityDim license) { this.license = license; }

    public EntityDim getOrganization() { return organization; }
    public void setOrganization(EntityDim organization) { this.organization = organization; }

    public EntityDim getSource() { return source; }
    public void setSource(EntityDim source) { this.source = source; }

    public List<String> getQuestionsForUser() { return questionsForUser; }
    public void setQuestionsForUser(List<String> questionsForUser) { this.questionsForUser = questionsForUser; }

    public double getOverallConfidence() { return overallConfidence; }
    public void setOverallConfidence(double overallConfidence) { this.overallConfidence = overallConfidence; }

    // =========================
    // Entity dimension
    // =========================
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EntityDim {

        @JsonProperty("raw_text")
        private String rawText;

        private String value;

        @JsonProperty("kg_node_id")
        private String kgNodeId;

        @JsonProperty("kg_node_ids")
        private List<String> kgNodeIds;  // Multiple selected candidates (for multi-select HITL)

        private double confidence;

        @JsonProperty("needs_clarification")
        private boolean needsClarification;

        public String getRawText() { return rawText; }
        public void setRawText(String rawText) { this.rawText = rawText; }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }

        public String getKgNodeId() { return kgNodeId; }
        public void setKgNodeId(String kgNodeId) { this.kgNodeId = kgNodeId; }

        public List<String> getKgNodeIds() { return kgNodeIds; }
        public void setKgNodeIds(List<String> kgNodeIds) { this.kgNodeIds = kgNodeIds; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

        public boolean isNeedsClarification() { return needsClarification; }
        public void setNeedsClarification(boolean needsClarification) { this.needsClarification = needsClarification; }
    }

    // =========================
    // Space dimension
    // =========================
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpaceDim {

        @JsonProperty("raw_text")
        private String rawText;

        private String value;

        @JsonProperty("kg_node_id")
        private String kgNodeId;

        // optional bbox if your KG stores it; used for frontend display
        private double[] bbox;

        private double confidence;

        @JsonProperty("needs_clarification")
        private boolean needsClarification;

        public String getRawText() { return rawText; }
        public void setRawText(String rawText) { this.rawText = rawText; }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }

        public String getKgNodeId() { return kgNodeId; }
        public void setKgNodeId(String kgNodeId) { this.kgNodeId = kgNodeId; }

        public double[] getBbox() { return bbox; }
        public void setBbox(double[] bbox) { this.bbox = bbox; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

        public boolean isNeedsClarification() { return needsClarification; }
        public void setNeedsClarification(boolean needsClarification) { this.needsClarification = needsClarification; }
    }

    // =========================
    // Time dimension
    // =========================
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TimeDim {

        @JsonProperty("raw_text")
        private String rawText;

        // "range|instant|year|unspecified"
        private String type;

        // ISO date strings "YYYY-MM-DD"
        private String start;
        private String end;

        // "day|month|year|unspecified"
        private String granularity;

        private double confidence;

        @JsonProperty("needs_clarification")
        private boolean needsClarification;

        public String getRawText() { return rawText; }
        public void setRawText(String rawText) { this.rawText = rawText; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getStart() { return start; }
        public void setStart(String start) { this.start = start; }

        public String getEnd() { return end; }
        public void setEnd(String end) { this.end = end; }

        public String getGranularity() { return granularity; }
        public void setGranularity(String granularity) { this.granularity = granularity; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

        public boolean isNeedsClarification() { return needsClarification; }
        public void setNeedsClarification(boolean needsClarification) { this.needsClarification = needsClarification; }
    }
}

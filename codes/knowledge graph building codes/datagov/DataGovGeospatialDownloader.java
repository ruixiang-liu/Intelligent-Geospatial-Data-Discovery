package edu.psu.giscience.graphbuilding.datagov;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class DataGovGeospatialDownloader {

    // =========================
    // CKAN API configuration
    // =========================
    private static final String BASE_URL = "https://catalog.data.gov/api/3/action/package_search";
    private static final String FILTER_QUERY = "metadata_type:geospatial";

    private static final int ROWS = 1000;
    private static final int TIMEOUT_SECONDS = 60;

    private static final String OUTPUT_JSON = "data_gov_geospatial_datasets.json";

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .readTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .writeTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

        // 1) first request: get total count
        JsonNode first = getPage(client, mapper, 0);
        int totalCount = first.path("result").path("count").asInt();
        System.out.println("Total geospatial datasets: " + totalCount);

        // 2) pagination loop
        List<JsonNode> allDatasets = new ArrayList<>(Math.max(totalCount, 0));
        int start = 0;

        long t0 = System.currentTimeMillis();
        while (start < totalCount) {
            JsonNode page = (start == 0) ? first : getPage(client, mapper, start);

            JsonNode results = page.path("result").path("results");
            if (!results.isArray()) {
                throw new RuntimeException("Unexpected response: result.results is not an array at start=" + start);
            }

            int added = 0;
            for (JsonNode ds : results) {
                allDatasets.add(ds);
                added++;
            }

            start += added;

            // progress output (tqdm-like)
            printProgress(start, totalCount, t0);
            if (added == 0) break; // safety: avoid infinite loop if API returns empty page
        }

        // 3) write to JSON (pretty)
        writePrettyJsonArray(mapper, allDatasets, new File(OUTPUT_JSON));

        System.out.println("\nSaved " + allDatasets.size() + " datasets to:");
        System.out.println(OUTPUT_JSON);
    }

    private static JsonNode getPage(OkHttpClient client, ObjectMapper mapper, int start) throws Exception {
        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("fq", FILTER_QUERY)
                .addQueryParameter("rows", String.valueOf(ROWS))
                .addQueryParameter("start", String.valueOf(start))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new RuntimeException("HTTP " + response.code() + " for " + url + "\nBody: " + body);
            }
            String json = response.body() != null ? response.body().string() : "";
            return mapper.readTree(json);
        }
    }

    private static void writePrettyJsonArray(ObjectMapper mapper, List<JsonNode> array, File out) throws Exception {
        // Write as a single JSON array, pretty printed
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(w, array);
        }
    }

    private static void printProgress(int current, int total, long t0Millis) {
        if (total <= 0) return;
        double pct = Math.min(100.0, (current * 100.0) / total);

        long elapsed = System.currentTimeMillis() - t0Millis;
        double sec = elapsed / 1000.0;
        double rate = sec > 0 ? current / sec : 0;

        int barWidth = 30;
        int filled = (int) Math.round((pct / 100.0) * barWidth);
        String bar = "[" + "#".repeat(Math.max(0, filled)) + "-".repeat(Math.max(0, barWidth - filled)) + "]";

        System.out.printf("\r%s %6.2f%%  %d/%d  %.1f it/s", bar, pct, current, total, rate);
        if (current >= total) System.out.println();
    }
}

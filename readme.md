# **Towards Intelligent geospatial data discovery: a knowledge graph-driven multi-agent framework powered by large language models**
# 1. Project Overview

This project proposes a knowledge graph-driven multi-agent framework for intelligent geospatial data discovery powered by large language models (LLMs). It aims to advance geospatial data discovery toward a more semantic, intent-aware, and intelligent paradigm, and provide methodological foundations for the next generation of intelligent and autonomous spatial data infrastructures.

The project directory is structured as follows:

```text
├── knowledge graph database/
│   ├── neo4j.dump
│   ├── ontology schema.json
├── codes/
│   ├── knowledge graph building codes/
│   │   ├── datagov/
│   │   ├── pasda/
│   │   ├── stac/
│   │   └── postprocessing2neo4j/
│   ├── multi-agent codes/
│   │   ├── config/
│   │   ├── controller/
│   │   ├── domain/
│   │   ├── exception/
│   │   ├── graph/
│   │   ├── llm/
│   │   ├── memory/
│   │   ├── service/
│   │   ├── IgddApplication.java
│   │   ├── application.yml
│   │   └── pom.xml
│   ├── frontend codes/
│   │   ├── index.html
│   │   ├── style.css
│   │   └── app.js
├── performance evaluation/
│   ├── igdd_link/
│   │   ├── L1-001.mhtml
│   │   ├── L1-002.mhtml
│   │   ├── ...
│   │   └── L4-025.mhtml
│   ├── igdd_data.gov_link/
│   │   ├── L1-001.mhtml
│   │   ├── L1-002.mhtml
│   │   ├── ...
│   │   └── L4-025.mhtml
│   ├── igdd_evaluation_summary.xlsx
│   ├── igdd_evaluation_with pool and score.xlsx
│   ├── igdd_plot_figure14a.m
│   └── igdd_plot_figure14b.m
├── prototype system video/
│   ├── igdd_case1_climate data discovery.mp4
│   ├── igdd_case2_US census data discovery.mp4
│   └── igdd_case3_DEM data discovery.mp4
├── Appendices.docx
└── README.md
```

# 2. Environment and dependencies

- Java Development Kit (JDK) 17
- Neo4j
- PostgreSQL
- Nginx (optional)

# 3. Knowledge graph building

There are two ways for the knowledge graph building: build with neo4j.dump or with knowledge graph building codes.

## 3.1 Build with neo4j.dump

1. Install Neo4j Desktop2 on Windows, or install Neo4j community edition on Linux.

2. For Windows, import neo4j.dump manually. For Linux, use:

   ```
   bin/neo4j-admin database load neo4j --from-path=/your path/ --overwrite-destination=true --verbose
   ```

​	Here we provide a version of neo4j.dump without embedding since the embedding version exceeds 18GB, which can not be uploaded to Figshare.

## 3.2 Build with knowledge graph building codes

### 3.2.1 Ingest data

1. Run codes/knowledge graph building codes/data.gov/ - DataGovGeospatialDownloader.java & StageAIngestCkan.java
2. Run codes/knowledge graph building codes/pasda/StageAIngestPasda.java
3. Run codes/knowledge graph building codes/stac/StageAIngestStac.java

### 3.2.2 Postprocessing

1. Run codes/knowledge graph building codes/postprocessing2neo4j/StageAEmbedAllEntities.java
2. Run codes/knowledge graph building codes/postprocessing2neo4j/StageB.java
3. Run codes/knowledge graph building codes/postprocessing2neo4j/StageC.java

# 4. Multi-agent pipeline

## 4.1 Start backend (API)

This project uses **Spring Boot** framework as the backend. Use **"codes/multi agent codes/"** to run.

1. Use your own LLM api key. You can rewrite the multi-agent codes/llm part to change the LLM model.

2. Configure with pom.xml to install all of the dependencies.

3. If run locally, run IgddApplication.java.

4. If run in Linux, use Maven to pack the codes into a JAR file. Then use nohup to start it:

    ```
    nohup java -jar igdd-0.0.1-SNAPSHOT.jar > igdd.log 2>&1 &
    ```

## 4.2 Start frontend (Web system)

This project uses **HTML+CSS+JS** framework as the frontend. Use **"codes/frontend codes/"** to run.

1. You can use many containers to run the frontend, such as Apache, Tomcat, Nginx.
2. You can also use Go Live in the VSCode to run it.

# 5. Implementation with reproducibility

If you build the environment, you can **reproduce** any **representative use cases** in the paper. If you do not want to use web system, API from backend also works:

```
URL: POST /api/igdd/query
Body (JSON) example:
{
  "conversationId": "",
  "apiKey": "YOUR_KEY",
  "query": "I need land cover datasets for Pennsylvania between 2018 and 2020.",
  "action": "message",
  "model": "gpt-5.2",
  "useKeywords": true
}
```

Important notes:

1. All of the links used in the implementation has the prefix https://catalog.data.gov/. During the implementation of this work, this prefix pointed to the old version of the website. At that time (Before March 2026), the prefix of the new version was https://catalog-beta.data.gov/, which is the test version.
2. However, the official website has been updated into the new version. So now this prefix points to the new version. As of June 2026, the old version was transferred to the prefix https://catalog-old.data.gov/.
3. Now (August 2026), all of the prefixes and links will be redirect to the new version. So if you want to see the official link of the dataset, you may not be able to use the links in the implementation and have to search the new website using name and other constraints. Anyway, all of the datasets are real and can be searched.

## 5.1 Figure 1-6

- Figures 1 to 6 are conceptual figures or workflows. All of the data and codes can prove them.

## 5.2 Table 1 & Figure 7

- Once the knowledge graph, backend, and frontend are set up, the system architecture and the web system can be checked immediately.

## 5.3 Representative use case 1 - Figure 8 & Table B1

1. In the web system, input the query *I’m looking for daily temperature datasets for CONUS from 1990 to 2020.*

2. Get the Top 20 datasets generated by the graph retrieval agent in the **"View Graph Retrieval Result (Top 20)"** section.
3. Get the Top 10 datasets reranked by the answer synthesis agent in the **first** section of the central panel.
4. You can also **see the video** in prototype system video/igdd_case1_climate data discovery.mp4.

## 5.4 Representative use case 2 - Figure 9 & Table B2

1. In the web system, input the query *Please find primary roads datasets provided by the U.S. Census Bureau for California for the year 2022, available in ZIP format.*

2. Get the Top 10 datasets generated by the answer synthesis agent in the **first** section of the central panel.
3. You can also **see the video** in prototype system video/igdd_case2_US census data discovery.mp4.

## 5.5 Representative use case 3 - Figure 10 & Table B3

1. In the web system, input the queries:
   - *I am looking for HIV data in District of Columbia.*
   - *Please find opioid use datasets published by the City of Tempe, licensed under the Creative Commons Attribution license, and provided in GeoJSON format.*
2. Get the Top 10 datasets generated by the answer synthesis agent in the **first** section of the central panel.
3. For **Figure 10**, use the **links of Data.gov** to get the results from the portal:
   - https://catalog.data.gov/dataset/?q=HIV&sort=views_recent+desc&metadata_type=geospatial&ext_location=District+of+Columbia&ext_bbox=-77.1223%2C38.7882%2C-76.9109%2C38.9935
   - https://catalog.data.gov/dataset/?q=opioid+use&sort=score+desc%2C+name+asc&metadata_type=geospatial&res_format=GeoJSON&organization=city-of-tempe

   New links in the new version (the results could be different using the new version):
   - https://catalog.data.gov/?q=HIV&sort=relevance&spatial_geometry=%7B%22type%22%3A%22MultiPolygon%22%2C%22coordinates%22%3A%5B%5B%5B%5B-77.1223%2C38.7882%5D%2C%5B-77.1223%2C38.9935%5D%2C%5B-76.9109%2C38.9935%5D%2C%5B-76.9109%2C38.7882%5D%2C%5B-77.1223%2C38.7882%5D%5D%5D%5D%7D&spatial_within=true&geography_label=District+of+Columbia
   - https://catalog.data.gov/?sort=relevance&q=opioid+use&sort=relevance&spatial_filter=geospatial&org_slug=tempe-az (the new version can not be searched using FORMAT)

## 5.6 Representative use case 4 - Figure 11

1. In the web system, input the queries:
   - *I want to find DEM data with global coverage.*
   - *I want to find DEM data covering Finland.*
   - *I want to find NDVIdata with global coverage.*
   - *I want to find NDVI data covering Finland.*

2. Get the Top 10 datasets generated by the answer synthesis agent in the **first** section of the central panel.

## 5.7 Representative use case 5 - Figure 12

1. In the web system, input the queries:
   - *I want to find hospital data in PA, USA.*
   - *I want to find road network data in PA, USA.*
   - *I want to find census block group boundary data in PA, USA.*
   - *I want to find cropland data in PA, USA.*

2. Get the Top 10 datasets generated by the answer synthesis agent in the **first** section of the central panel.
3. Click the **"Preview"** of the discovered data, then you can see the sub-figures of Figure 12.

## 5.8 Representative use case 6 - Figure 13

- In the web system, follow the conversations in Figure 13, and you can see the functionality of human-in-the-loop and memory module.

# 6. Performance evaluation

## 6.1 Benchmark questions

1. Find all of the benchmark questions in performance evaluation/igdd_evaluation_with pool and score.csv.
2. The scores and the rankings of each system component are included.
3. In performance evaluation/igdd_evaluation_summary.csv, you can see a concise statistical table of the benchmark questions.

## 6.2 Reproducibility for results including Table 5 & Figure 14

1. Open **performance evaluation/igdd_evaluation_with pool and score.csv**.

2. The folder **performance evaluation/igdd_link/** and **performance evaluation/igdd_data.gov_link/** are system results in the format of MHTML. **These MHTML files record all of the discovered datasets of each query in the benchmark questions. This also proves the transparency of the proposed framework and system. **

3. You can also set up the system following the previous instructions (**Section 2, Section 3, and Section 4**) **using the codes** to get these system records.

4. For EIMR, all of the ground truth for the intent constraints of each query can be found at column "Topic", "Space", "Time", "Organization", "Format", and "License". For the intent parsed by the system, see the MHTML files in the folders. It is in the right panel.

5. For NDCG@10 and Recall@20 results in Section 4.3 including Table 5 and Figure 14:

   - First, all of the retrieved datasets and their ranking can be found and reproduced **in the central panel in the MHTML files**. Data.gov results are recorded in the link format of Data,gov itself.

   - Second, **igdd_evaluation_with pool and score.csv** further records the dataset names and links, scores, and rankings of IGDD-G&A, IGDD-G, IGDD(D)-G&A, IGDD(D)-G, and Data.gov.

     | No   | Source_IGDD-G&A | Source_IGDD(D)-G&A | Source_Data.gov | Source_BM25 | Score | Rank_IGDD-G&A | Rank_IGDD-G | Rank_IGDD(D)-G&A | Rank_IGDD(D)-G | Rank_Data.gov | Rank_BM25 |
     | ---- | --------------- | ------------------ | --------------- | ----------- | ----- | ------------- | ----------- | ---------------- | -------------- | ------------- | --------- |
     | ...  | ...             | ...                | ...             | ...         | ...   | ...           | ...         | ...              | ...            | ...           | ...       |

   - Third, **igdd_evaluation_with pool and score.csv** directly computes the metrics.

     | IDCG | NDCG_IGDD-G&A | NDCG_IGDD-G | NDCG_IGDD(D)-G&A | NDCG_IGDD(D)-G | NDCG_Data.gov | NDCG_BM25 | Recall_IGDD(D)-G&A | Recall_Data.gov | Recall_BM25 |
     | ---- | ------------- | ----------- | ---------------- | -------------- | ------------- | --------- | ------------------ | --------------- | ----------- |
     | ...  | ...           | ...         | ...              | ...            | ...           | ...       | ...                | ...             | ...         |

6. **igdd_evaluation_summary.csv** provides an overview of the 100 benchmark questions generated from **igdd_evaluation_with pool and score.csv**, including EIMR, NDCG@10, and Recall@10 values.

7. igdd_plot_figure14a.m and igdd_plot_figure14b.m further shows how we plotted Figure 14 using the results from igdd_evaluation_summary.csv.


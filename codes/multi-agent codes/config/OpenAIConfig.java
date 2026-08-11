package edu.psu.giscience.igdd.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * This config class is optional.
 * LlmClientService reads these properties directly via @Value.
 */
@Configuration
public class OpenAIConfig {

    @Value("${openai.apiKey}")
    private String apiKey;

    @Value("${openai.baseUrl:https://api.openai.com}")
    private String baseUrl;

    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
}

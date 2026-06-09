package test;

import lombok.extern.slf4j.Slf4j;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

@Slf4j
public class LLMHelper {
    private static final String API_KEY = "sk-HU1Sz4wsXRdU3a8VeVJVDSvPDMS66ry7O20ylU7iJX3LPV8B";
    private static final String API_URL = "https://rabbitcodecc.com/v1/messages";

    public static String callLLM(String prompt) {
        int maxRetries = 3;
        int retryDelayMs = 2000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // 增加连接超时，允许大模型充分推理
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(120))
                        .build();

                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", "claude-sonnet-4-6");
                requestBody.addProperty("max_tokens", 4096);

                JsonArray messages = new JsonArray();
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", prompt);
                messages.add(message);

                requestBody.add("messages", messages);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .header("x-api-key", API_KEY)
                        .header("anthropic-version", "2023-06-01")
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(30)) // 30 秒请求超时，快速失败
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                        .build();

                log.info("Calling LLM API (attempt {}/{})", attempt, maxRetries);
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                // log.info("Raw response body: {}", response.body());
                if (response.statusCode() == 200) {
                    JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                    String content = jsonResponse.getAsJsonArray("content")
                            .get(0).getAsJsonObject()
                            .get("text").getAsString();

                    log.info("LLM Response received successfully");
                    return content;
                } else {
                    log.error("LLM API call failed with status code: {}", response.statusCode());
                    log.error("Response body: {}", response.body());

                    if (attempt < maxRetries) {
                        log.warn("Retrying in {}ms...", retryDelayMs);
                        Thread.sleep(retryDelayMs);
                        retryDelayMs *= 2;
                    }
                }
            } catch (java.io.IOException e) {
                log.error("IOException on attempt {}/{}: {}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        log.warn("Retrying in {}ms...", retryDelayMs);
                        Thread.sleep(retryDelayMs);
                        retryDelayMs *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            } catch (Exception e) {
                log.error("Error calling LLM API on attempt {}/{}", attempt, maxRetries, e);
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelayMs);
                        retryDelayMs *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }

        log.error("All {} retry attempts failed", maxRetries);
        return null;
    }
}
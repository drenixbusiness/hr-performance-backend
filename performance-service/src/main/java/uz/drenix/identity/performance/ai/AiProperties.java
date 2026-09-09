package uz.drenix.identity.performance.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The language model used for written feedback. */
@ConfigurationProperties(prefix = "drenix.ai")
public class AiProperties {

    /** Empty disables the feature: the endpoint answers 503 and says why. */
    private String apiKey = "";
    private String baseUrl = "https://api.openai.com";
    private String model = "gpt-4o-mini";
    private Duration timeout = Duration.ofSeconds(180);

    /**
     * Room for the whole report.
     *
     * <p>The old ceiling was the model's default, which was ample for a paragraph and truncates a
     * ten-section management report somewhere around section E. A report that stops mid-table is
     * worse than one that fails, because it still downloads and still looks finished.
     */
    private int maxTokens = 6000;

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
}

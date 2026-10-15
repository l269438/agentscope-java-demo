package dev.yiyan.agentscope.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class DemoConfig {
    public static final String SDK_VERSION = "2.0.3";
    public static final String MODEL = "qwen-plus";
    public static final String LOCAL_MODEL = "qwen2.5:0.5b";
    public static final String OLLAMA_URL = "http://127.0.0.1:11434";
    private final String provider;
    private final String modelName;
    private final String ollamaBaseUrl;
    private final String apiKey;
    private final int port;
    private final int maxIters;
    private final int timeoutSeconds;
    private final Path runsDirectory;

    public DemoConfig(String apiKey, int port, int maxIters, int timeoutSeconds, Path runsDirectory) {
        this("dashscope", MODEL, OLLAMA_URL, apiKey, port, maxIters, timeoutSeconds, runsDirectory);
    }

    public DemoConfig(String provider, String modelName, String ollamaBaseUrl, String apiKey,
                      int port, int maxIters, int timeoutSeconds, Path runsDirectory) {
        if (port < 0 || port > 65535 || maxIters < 1 || maxIters > 8
                || timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("端口、迭代次数或超时配置超出允许范围");
        }
        if (!"dashscope".equals(provider) && !"ollama".equals(provider)) {
            throw new IllegalArgumentException("DEMO_PROVIDER 仅支持 dashscope 或 ollama");
        }
        if (modelName == null || modelName.isBlank() || modelName.length() > 120) {
            throw new IllegalArgumentException("模型名称不能为空或超过120字符");
        }
        if ("ollama".equals(provider)) {
            URI uri = URI.create(ollamaBaseUrl);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || uri.getPort() < 1 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("Ollama 地址需为 http://127.0.0.1:端口，不含路径或凭据");
            }
        }
        this.provider = provider;
        this.modelName = modelName.trim();
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.port = port;
        this.maxIters = maxIters;
        this.timeoutSeconds = timeoutSeconds;
        this.runsDirectory = runsDirectory.toAbsolutePath().normalize();
    }

    public static DemoConfig load(Path projectDirectory, Map<String, String> environment) throws IOException {
        Properties local = new Properties();
        Path file = projectDirectory.resolve(".env");
        if (Files.isRegularFile(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                local.load(reader);
            }
        }
        String provider = value(environment, local, "DEMO_PROVIDER", "dashscope");
        return new DemoConfig(provider,
                value(environment, local, "DEMO_MODEL", "ollama".equals(provider) ? LOCAL_MODEL : MODEL),
                value(environment, local, "OLLAMA_BASE_URL", OLLAMA_URL),
                value(environment, local, "DASHSCOPE_API_KEY", ""),
                Integer.parseInt(value(environment, local, "DEMO_PORT", "18181")),
                Integer.parseInt(value(environment, local, "DEMO_MAX_ITERS", "8")),
                Integer.parseInt(value(environment, local, "DEMO_TIMEOUT_SECONDS", "120")),
                projectDirectory.resolve(value(environment, local, "DEMO_RUNS_DIR", "runs")));
    }

    private static String value(Map<String, String> environment, Properties local, String name, String fallback) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) value = local.getProperty(name, fallback);
        value = value.trim();
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    public String apiKey() { return apiKey; }
    public boolean keyConfigured() { return !apiKey.isBlank(); }
    public boolean modelConfigurationReady() { return provider.equals("ollama") || keyConfigured(); }
    public String provider() { return provider; }
    public String modelName() { return modelName; }
    public String ollamaBaseUrl() { return ollamaBaseUrl; }
    public int port() { return port; }
    public int maxIters() { return maxIters; }
    public int timeoutSeconds() { return timeoutSeconds; }
    public Path runsDirectory() { return runsDirectory; }
    public Map<String, Object> publicConfiguration() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("sdkVersion", SDK_VERSION);
        configuration.put("provider", provider);
        configuration.put("model", modelName);
        configuration.put("modelKeyConfigured", keyConfigured());
        configuration.put("modelConfigurationReady", modelConfigurationReady());
        configuration.put("maxIters", maxIters);
        configuration.put("maxOutputTokensPerRequest", 1024);
        configuration.put("modelRetries", 0);
        configuration.put("modelMaxAttempts", 1);
        configuration.put("timeoutSeconds", timeoutSeconds);
        configuration.put("dataType", "simulated");
        if (provider.equals("ollama")) {
            configuration.put("modelBaseUrl", ollamaBaseUrl);
            configuration.put("contextWindowTokens", 8192);
        }
        return Map.copyOf(configuration);
    }
    @Override public String toString() { return publicConfiguration().toString(); }
}

package com.threevictors.aws.priceeye.taxes.taxengine;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;

public class SharedHttpFactory {

    private static SharedHttpFactory INSTANCE;

    private final HttpClient httpClient;
    private final ExecutorService executorService;

    private SharedHttpFactory(ExecutorService executorService, int threadCount) {
        // Increase connection pool size to match the number of worker threads
        int connectionPoolSize = threadCount;
        System.setProperty("jdk.httpclient.connectionPoolSize", String.valueOf(connectionPoolSize));
        System.setProperty("jdk.httpclient.keepalive.timeout", "60");

        // Configure HTTP client with optimized settings
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.of(30, ChronoUnit.SECONDS))
                .version(HttpClient.Version.HTTP_2) // Use HTTP/2 for better performance
                .executor( executorService )
                .build();

        this.executorService = executorService;
    }

    public static SharedHttpFactory getInstance( ) {
        return INSTANCE;
    }

    public static SharedHttpFactory setupInstance(ExecutorService executorService, int threadCount) {
        INSTANCE = new SharedHttpFactory(executorService, threadCount);

        return INSTANCE;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }
}

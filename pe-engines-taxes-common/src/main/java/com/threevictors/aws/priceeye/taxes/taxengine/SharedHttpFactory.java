package com.threevictors.aws.priceeye.taxes.taxengine;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class SharedHttpFactory {

    private static final SharedHttpFactory INSTANCE = new SharedHttpFactory();

    private final HttpClient httpClient;

    private SharedHttpFactory() {

        // Increase connection pool size to match the number of worker threads
        int connectionPoolSize = Runtime.getRuntime().availableProcessors() * 8; // Double the worker thread count for better throughput
        System.setProperty("jdk.httpclient.connectionPoolSize", String.valueOf(connectionPoolSize));
        System.setProperty("jdk.httpclient.keepalive.timeout", "60");

        // Configure HTTP client with optimized settings
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.of(30, ChronoUnit.SECONDS))
                .version(HttpClient.Version.HTTP_2) // Use HTTP/2 for better performance
                .build();
    }

    public static SharedHttpFactory getInstance() {
        return INSTANCE;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }
}

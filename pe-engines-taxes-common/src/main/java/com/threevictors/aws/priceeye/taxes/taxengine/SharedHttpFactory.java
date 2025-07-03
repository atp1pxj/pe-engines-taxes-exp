package com.threevictors.aws.priceeye.taxes.taxengine;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class SharedHttpFactory {

    private static SharedHttpFactory INSTANCE;
    private static final Object LOCK = new Object();

    private final HttpClient httpClient;

    private SharedHttpFactory(int threadCount) {
        // Increase connection pool size to match the number of worker threads
        int connectionPoolSize = threadCount;
        System.setProperty("jdk.httpclient.connectionPoolSize", String.valueOf(connectionPoolSize));
        System.setProperty("jdk.httpclient.keepalive.timeout", "60");

        // Configure HTTP client with optimized settings
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.of(30, ChronoUnit.SECONDS))
                .version(HttpClient.Version.HTTP_2) // Use HTTP/2 for better performance
                .build();
    }

    public static SharedHttpFactory getInstance() {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = new SharedHttpFactory(Runtime.getRuntime().availableProcessors() * 8);
                }
            }
        }
        return INSTANCE;
    }

    public static SharedHttpFactory getInstance(int threadCount) {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = new SharedHttpFactory(threadCount);
                }
            }
        }
        return INSTANCE;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }
}

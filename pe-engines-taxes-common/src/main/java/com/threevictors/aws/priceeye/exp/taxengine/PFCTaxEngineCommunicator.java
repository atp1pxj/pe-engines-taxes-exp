package com.threevictors.aws.priceeye.exp.taxengine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.threevictors.aws.configreader.configuration.reader.heavy.ConfigurationReader;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.model.pfcengine.response.RootPFCResponse;
import com.threevictors.aws.priceeye.exp.velocity.builder.PFCEngineRequestBuilder;

/*import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;*/

//apache log4j2 dependencies
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Properties;

//TODO: Refactor this class and the TaxEngineCommunicator class to streamline the code and remove redundancy.
public class PFCTaxEngineCommunicator {

    private static final Logger log = LogManager.getLogger(PFCTaxEngineCommunicator.class);

    private HttpClient httpClient;

    private PFCEngineRequestBuilder pfcEngineRequestBuilder;
    private Gson gson;

    private String pfcTaxEngineUrl;

    public PFCTaxEngineCommunicator() {

        Properties p = ConfigurationReader.readProperties( "pe-engines-taxes.properties" );
        pfcTaxEngineUrl = p.getProperty("pfc.tax.engine.url").trim();

        // Increase connection pool size to match the number of worker threads
        int connectionPoolSize = Runtime.getRuntime().availableProcessors() * 4; // Double the worker thread count for better throughput
        System.setProperty("jdk.httpclient.connectionPoolSize", String.valueOf(connectionPoolSize));
        System.setProperty("jdk.httpclient.keepalive.timeout"
                , p.getProperty( "jdk.httpclient.keepalive.timeout", "60")); // Increase keepalive timeout

        // Configure HTTP client with optimized settings
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.of(30, ChronoUnit.SECONDS))
                .version(HttpClient.Version.HTTP_2) // Use HTTP/2 for better performance
                .build();

        pfcEngineRequestBuilder = new PFCEngineRequestBuilder();

        gson = new GsonBuilder()
                .setDateFormat("yyMMdd HH:mm")
                .create();
    }


    public RootPFCResponse sendRequest(PEItinerary itinerary, String queryId) {
        String request = pfcEngineRequestBuilder.buildRequest( itinerary );
        //log.info("LN: " + itinerary.getChannel() + " JSON Request to PFC engines: " + request);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        requestBuilder.setHeader("Content-Type", "application/json");
        requestBuilder.setHeader("Accept", "application/json");
        requestBuilder.setHeader("X-Correlation-ID", queryId);

        try {
            requestBuilder.uri(new URI(pfcTaxEngineUrl));
        }
        catch (URISyntaxException use) {
            log.error("Error creating URI for PFCTaxEngineCommunicator: " + use.getMessage());
            return null;
        }

        requestBuilder.POST(HttpRequest.BodyPublishers.ofString(request));
        requestBuilder.timeout(Duration.of(180, ChronoUnit.SECONDS));
        HttpRequest httpRequest = requestBuilder.build();

        try {
            HttpResponse<String> httpResponse = httpClient.send( httpRequest, HttpResponse.BodyHandlers.ofString());

            return gson.fromJson(httpResponse.body(), RootPFCResponse.class);
        }
        catch (IOException|InterruptedException e) {
            log.error("Failed to send request to PFCTaxEngineCommunicator: " + e.getMessage(), e);
            return null;
        }
    }
}

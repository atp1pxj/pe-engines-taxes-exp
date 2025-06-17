package com.threevictors.aws.priceeye.exp.taxengine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.model.taxengine.response.RootResponse;
import com.threevictors.aws.priceeye.exp.velocity.builder.TaxEngineRequestBuilder;

/*import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;*/

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

public class TaxEngineCommunicator {

    private static final Logger log = LogManager.getLogger(TaxEngineCommunicator.class);

    private final static String URI = "http://tax-sfe-service.engines-stg.use1.atpco.local/tax";
    private HttpClient httpClient;
    private TaxEngineRequestBuilder taxEngineRequestBuilder;
    private Gson gson;

    public TaxEngineCommunicator() {
        System.setProperty("jdk.httpclient.connectionPoolSize", String.valueOf(20));
        System.setProperty("jdk.httpclient.keepalive.timeout", "30");

        httpClient = HttpClient.newBuilder().connectTimeout(Duration.of(30, ChronoUnit.SECONDS)).build();

        taxEngineRequestBuilder = new TaxEngineRequestBuilder();

        gson = new GsonBuilder()
                .setDateFormat("yyMMdd HH:mm")
                .create();
    }


    public RootResponse sendRequest(PEItinerary itinerary, String queryId ) {

        String request = taxEngineRequestBuilder.buildRequest( itinerary );
        //log.info("LN: " + itinerary.getChannel() + " JSON Request to taxengines: " + request);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        requestBuilder.setHeader("Content-Type", "application/json");
        requestBuilder.setHeader("Accept", "application/json");
        requestBuilder.setHeader("X-Correlation-ID", queryId);

        try {
            requestBuilder.uri(new URI(URI));
        }
        catch (URISyntaxException use) {
            log.error("Error creating URI for TaxEngineCommunicator: " + use.getMessage());
            return null;
        }

        requestBuilder.POST(HttpRequest.BodyPublishers.ofString(request));
        requestBuilder.timeout(Duration.of(180, ChronoUnit.SECONDS));
        HttpRequest httpRequest = requestBuilder.build();

        try {
            HttpResponse<String> httpResponse = httpClient.send( httpRequest, HttpResponse.BodyHandlers.ofString());

            return gson.fromJson(httpResponse.body(), RootResponse.class);
        }
        catch (IOException|InterruptedException e) {
            log.error("Failed to send request to TaxEngineCommunicator: " + e.getMessage(), e);
            return null;
        }
    }
}

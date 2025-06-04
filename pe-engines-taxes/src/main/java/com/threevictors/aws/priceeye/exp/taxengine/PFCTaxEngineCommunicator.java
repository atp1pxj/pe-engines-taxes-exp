package com.threevictors.aws.priceeye.exp.taxengine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.model.pfcengine.response.RootPFCResponse;
import com.threevictors.aws.priceeye.exp.model.taxengine.response.RootResponse;
import com.threevictors.aws.priceeye.exp.velocity.builder.PFCEngineRequestBuilder;
import com.threevictors.aws.priceeye.exp.velocity.builder.TaxEngineRequestBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

//TODO: Refactor this class and the TaxEngineCommunicator class to streamline the code and remove redundancy.
public class PFCTaxEngineCommunicator {

    private final static Log log = LogFactory.getLog(PFCTaxEngineCommunicator.class);

    private final static String URI = "http://tax-sfe-service.engines-stg.use1.atpco.local/pfcEngine";
    private HttpClient httpClient;

    private PFCEngineRequestBuilder pfcEngineRequestBuilder;
    private Gson gson;

    public PFCTaxEngineCommunicator() {
        System.setProperty("jdk.httpclient.connectionPoolSize", String.valueOf(20));
        System.setProperty("jdk.httpclient.keepalive.timeout", "30");

        httpClient = HttpClient.newBuilder().connectTimeout(Duration.of(30, ChronoUnit.SECONDS)).build();

        pfcEngineRequestBuilder = new PFCEngineRequestBuilder();

        gson = new GsonBuilder()
                .setDateFormat("yyMMdd HH:mm")
                .create();
    }


    public RootPFCResponse sendRequest(PEItinerary itinerary ) {
        String request = pfcEngineRequestBuilder.buildRequest( itinerary );
        //log.info("LN: " + itinerary.getChannel() + " JSON Request to PFC engines: " + request);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        requestBuilder.setHeader("Content-Type", "application/json");

        requestBuilder.setHeader("Accept", "application/json");

        try {
            requestBuilder.uri(new URI(URI));
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

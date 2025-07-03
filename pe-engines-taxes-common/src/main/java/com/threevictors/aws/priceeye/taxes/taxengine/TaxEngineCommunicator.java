package com.threevictors.aws.priceeye.taxes.taxengine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.threevictors.aws.configreader.configuration.reader.heavy.ConfigurationReader;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.taxes.model.taxengine.response.RootResponse;
import com.threevictors.aws.priceeye.taxes.velocity.builder.TaxEngineRequestBuilder;

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
import java.util.Properties;

public class TaxEngineCommunicator {

    private static final Logger log = LogManager.getLogger(TaxEngineCommunicator.class);

    private HttpClient httpClient;
    private TaxEngineRequestBuilder taxEngineRequestBuilder;
    private Gson gson;

    private String sfeTaxEngineUrl;


    public TaxEngineCommunicator() {

        Properties p = ConfigurationReader.readProperties( "pe-engines-taxes.properties" );
        sfeTaxEngineUrl = p.getProperty("sfe.tax.engine.url").trim();

        // Configure HTTP client with optimized settings
        httpClient = SharedHttpFactory.getInstance().getHttpClient();

        taxEngineRequestBuilder = new TaxEngineRequestBuilder();

        gson = new GsonBuilder()
                .setDateFormat("yyMMdd HH:mm")
                .create();
    }


    public RootResponse sendRequest(String pointOfSale, PEItinerary itinerary, String queryId, int salesDate) {

        String request = taxEngineRequestBuilder.buildRequest( pointOfSale, itinerary, salesDate);
        //log.info("LN: " + itinerary.getChannel() + " JSON Request to taxengines: " + request);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        requestBuilder.setHeader("Content-Type", "application/json");
        requestBuilder.setHeader("Accept", "application/json");
        requestBuilder.setHeader("X-Correlation-ID", queryId);

        try {
            requestBuilder.uri(new URI(sfeTaxEngineUrl));
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

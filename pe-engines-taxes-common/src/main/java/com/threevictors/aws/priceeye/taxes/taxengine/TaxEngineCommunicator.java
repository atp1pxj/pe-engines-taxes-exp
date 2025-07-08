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


import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.google.gson.JsonParser;

public class TaxEngineCommunicator {

    private static final Logger log = LogManager.getLogger(TaxEngineCommunicator.class);
    private static final String SFE_HTTP_REQUEST_BODY_BEGIN = " SFE_JSON_REQUEST < ";
    private static final String SFE_REQUEST_END = " >; ";
    private HttpClient httpClient;
    private ExecutorService executorService;
    private TaxEngineRequestBuilder taxEngineRequestBuilder;
    private Gson gson;

    private String sfeTaxEngineUrl;

    public TaxEngineCommunicator() {
        Properties p = ConfigurationReader.readProperties( "pe-engines-taxes.properties" );
        sfeTaxEngineUrl = p.getProperty("sfe.tax.engine.url").trim();

        // Configure HTTP client with optimized settings
        httpClient = SharedHttpFactory.getInstance().getHttpClient();
        executorService = SharedHttpFactory.getInstance().getExecutorService();

        taxEngineRequestBuilder = new TaxEngineRequestBuilder();

        gson = new GsonBuilder()
                .setDateFormat("yyMMdd HH:mm")
                .create();
    }

    public CompletableFuture<RootResponse> sendRequest(String pointOfSale, PEItinerary itinerary, String queryId, int salesDate) {

        //String request = taxEngineRequestBuilder.buildRequest(pointOfSale, itinerary, salesDate);
        //Minify the JSON request
        String request = JsonParser.parseString(taxEngineRequestBuilder.buildRequest(pointOfSale, itinerary, salesDate)).toString();


        //log.info("LN: " + itinerary.getChannel() + " JSON Request to taxengines: " + request);
        //Set the HTTP request body in the fareConstruction field temp'ly for Engine debugging purposes.
        itinerary.setFareConstructionText(SFE_HTTP_REQUEST_BODY_BEGIN + request + SFE_REQUEST_END);

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

        CompletableFuture<HttpResponse<String>> httpResponse = httpClient.sendAsync( httpRequest, HttpResponse.BodyHandlers.ofString());

        return httpResponse.thenApplyAsync(response -> gson.fromJson(response.body(), RootResponse.class), executorService);
    }
}

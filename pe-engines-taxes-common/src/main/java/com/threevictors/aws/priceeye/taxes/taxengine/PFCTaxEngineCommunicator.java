package com.threevictors.aws.priceeye.taxes.taxengine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.threevictors.aws.configreader.configuration.reader.heavy.ConfigurationReader;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.taxes.model.pfcengine.response.RootPFCResponse;
import com.threevictors.aws.priceeye.taxes.velocity.builder.PFCEngineRequestBuilder;

/*import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;*/

//apache log4j2 dependencies
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

//TODO: Refactor this class and the TaxEngineCommunicator class to streamline the code and remove redundancy.
public class PFCTaxEngineCommunicator {

    private static final Logger log = LogManager.getLogger(PFCTaxEngineCommunicator.class);

    private static final String PFC_HTTP_REQUEST_BODY_BEGIN = " PFC_JSON_REQUEST < ";
    private static final String PFC_REQUEST_END = " >; ";

    private HttpClient httpClient;
    private ExecutorService executorService;

    private PFCEngineRequestBuilder pfcEngineRequestBuilder;
    private Gson gson;

    private String pfcTaxEngineUrl;

    public PFCTaxEngineCommunicator() {
        Properties p = ConfigurationReader.readProperties( "pe-engines-taxes.properties" );
        pfcTaxEngineUrl = p.getProperty("pfc.tax.engine.url").trim();

        httpClient = SharedHttpFactory.getInstance().getHttpClient();
        executorService = SharedHttpFactory.getInstance().getExecutorService();

        pfcEngineRequestBuilder = new PFCEngineRequestBuilder();

        gson = new GsonBuilder()
                .setDateFormat("yyMMdd HH:mm")
                .create();
    }


    public CompletableFuture<RootPFCResponse> sendRequest(String pointOfSale, PEItinerary itinerary, String queryId, int salesDate, PEItinerary originalItin) {
        //String request = pfcEngineRequestBuilder.buildRequest( pointOfSale, itinerary, salesDate );
        //log.info("LN: " + itinerary.getChannel() + " JSON Request to PFC engines: " + request);

        //Minify the resulting json
        String request = JsonParser.parseString( pfcEngineRequestBuilder.buildRequest( pointOfSale, itinerary, salesDate ) ).toString();

        //Added mainly for capturing the associated PFC requests. Can remove later
        String appendPFCRequestBody = new StringBuilder(originalItin.getFareConstructionText()).append(PFC_HTTP_REQUEST_BODY_BEGIN).append(request).append(PFC_REQUEST_END).toString();
        originalItin.setFareConstructionText(appendPFCRequestBody);

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

        CompletableFuture<HttpResponse<String>> httpResponse = httpClient.sendAsync( httpRequest, HttpResponse.BodyHandlers.ofString());

        return httpResponse.thenApplyAsync(response -> gson.fromJson(response.body(), RootPFCResponse.class), executorService);
    }
}

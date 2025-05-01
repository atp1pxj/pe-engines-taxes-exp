package com.threevictors.aws.priceeye.exp;



import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.text.SimpleDateFormat;

import com.threevictors.aws.data.priceeye.PEItinerary;
import net.atpco.ash.enums.LegIndicatorType;
import net.atpco.engine.common.types.TransferType;
import net.atpco.fare.domain.types.TripType;
import net.atpco.pfc.engine.configuration.PFCEngineConfiguration;
import net.atpco.service.fee.client.request.TaxServiceFeeQuery;
import net.atpco.taxes.configuration.TaxesEngineConfiguration;
import net.atpco.taxes.engine.service.TaxService;
import net.atpco.service.fee.client.request.TaxServiceFeeQuery;
import net.atpco.service.fee.client.request.TaxItinerary;
import net.atpco.service.fee.client.request.ServiceFeeInfo;
import net.atpco.service.fee.client.request.FareInfo;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;

import net.atpco.yqyr.engine.configuration.YqYrEngineConfiguration;
import org.apache.commons.lang3.tuple.ImmutablePair;
import java.math.BigDecimal;
import java.time.LocalDate;
//import java.time.LocalDateRange;
import java.util.Arrays;
import java.util.ArrayList;
import net.atpco.ash.location.vo.*;
import net.atpco.service.fee.client.request.TaxServiceFeeLeg;
import org.apache.http.client.utils.URIBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

//Worked when I used snapshot
//import net.atpco.service.fee.configuration.ServiceFeeEngineConfiguration;
//import net.atpco.service.fee.configuration.ServiceFeeEngineProdConfiguration;
//import net.atpco.service.fee.ServiceFeeEngine;


public class PEEnginesTaxesExpApplication {

    protected static Gson gson = new GsonBuilder()
        .setDateFormat("yyMMdd HH:mm")
        .create();
    protected HttpClient communicator;

    public static void main(String[] args) {

        //ServiceFeeEngine.main(args);

        /*final Class<?>[] sources = new Class<?>[] {
                ServiceFeeEngineConfiguration.class,
                ServiceFeeEngineProdConfiguration.class,
                YqYrEngineConfiguration.class,
                TaxesEngineConfiguration.class,
                PFCEngineConfiguration.class };

        final ApplicationContext ctx = SpringApplication.run(sources, args);*/


        //System.out.println("PEEnginesTaxesExpApplication started!");

        PEEnginesTaxesExpApplication currentApp = new PEEnginesTaxesExpApplication();
        TaxEngineRequestBuilder taxEngineRequestBuilder = new TaxEngineRequestBuilder();

        /*  TODO:

            (D)First, stub the TaxServiceFeeQuery object and the other booleans as per the
            required signature on how the TaxesController makes the call.

            (D)Stub with values examined from the POST request made on local.
            (D)Print the calculated taxes.


            If I am getting some successful response, then the plan is to stub a PEItinerary object
            with some values and transform it to TaxServiceFeeQuery query and make the call.
            Note that the PEItinerary does not have basefare, so start with a value of 1 or 100 and see how it goes.*/

        //TaxServiceFeeQuery query = TaxServiceFeeQuery.builder().build();
        //TaxService taxService = new TaxService();

        //TODO: Need to create the stub from the velocity template by using the TaxEngineRequestBuilder
        //TaxServiceFeeQuery tsFeeQueryStub = currentApp.createTaxServiceFeeQueryStub();
         String reqBody = taxEngineRequestBuilder.buildRequest(new PEItinerary());




        //String reqBody = gson.toJson(tsFeeQueryStub);
        //String reqBody = "{\"itinerary\":{\"taxLegs\":[{\"departs\":\"250601 08:10\",\"arrives\":\"250601 20:00\",\"legId\":1,\"origin\":{\"type\":\"P\",\"code\":\"JFK\"},\"destination\":{\"type\":\"P\",\"code\":\"LHR\"},\"marketedCarrier\":\"DL\",\"marketedFlightNo\":5996,\"operatedCarrier\":\"VA\",\"operatedFlightNo\":5996,\"rbd\":\"\",\"legIndicator\":\"F\",\"fareIndex\":0,\"transferType\":\"STOP_OVER\",\"involuntary\":false},{\"departs\":\"250608 09:35\",\"arrives\":\"250608 12:45\",\"legId\":2,\"origin\":{\"type\":\"P\",\"code\":\"LHR\"},\"destination\":{\"type\":\"P\",\"code\":\"JFK\"},\"marketedCarrier\":\"DL\",\"marketedFlightNo\":5997,\"operatedCarrier\":\"VA\",\"operatedFlightNo\":5997,\"rbd\":\"\",\"legIndicator\":\"F\",\"fareIndex\":0,\"involuntary\":false}]},\"fares\":[{\"fareBasisTicketDesignator\":\"\",\"tariff\":0,\"privateTariff\":false,\"domesticFare\":false,\"fareOwningCarrier\":\"DL\",\"tripType\":\"ROUND_TRIP\",\"fareAmount\":0.01,\"fareCurrency\":\"USD\"},{\"fareBasisTicketDesignator\":\"\",\"tariff\":0,\"privateTariff\":false,\"domesticFare\":false,\"fareOwningCarrier\":\"DL\",\"tripType\":\"ROUND_TRIP\",\"fareAmount\":0.01,\"fareCurrency\":\"USD\"}],\"ticketDate\":\"250429\",\"validatingCarrier\":\"DL\",\"pointOfSale\":{\"type\":\"N\",\"code\":\"US\"},\"pointOfTicketing\":{\"type\":\"N\",\"code\":\"US\"},\"faresTotal\":0.02,\"feesTotal\":0.00,\"ticketCurrency\":\"USD\",\"responseCurrency\":\"USD\",\"enableDiagnostics\":true,\"includeExemptSequence\":true,\"involuntary\":false}";
        //Make a call to Engines
        HttpResponse<String> response = currentApp.sendRequest(getBaseUri() + "/tax", "POST", reqBody, null);

        if(response != null){
            System.out.println("Response code: " + response.statusCode());
            System.out.println("Response body: " + response.body());
        } else {
            System.out.println("Response is null");
        }

    }//End of main method.





    public HttpResponse<String> sendRequest(String url, String method, String body, Map<String, String> queryStringParameters){
        try {
            HttpRequest request = buildRequest(url, method, body, queryStringParameters);
            return getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.out.println("Error sending request: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    protected HttpClient getHttpClient(){
        if(communicator == null){
            System.setProperty("jdk.httpclient.connectionPoolSize", String.valueOf(20));
            System.setProperty("jdk.httpclient.keepalive.timeout", "30");

            communicator = HttpClient.newBuilder().connectTimeout(Duration.of(30, ChronoUnit.SECONDS)).build();
        }
        return communicator;
    }

    private HttpRequest buildRequest(String url, String method, String body, Map<String, String> queryStringParameters) throws URISyntaxException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        requestBuilder.setHeader("Content-Type", "application/json");

        //TODO: Added Accept header
        requestBuilder.setHeader("Accept", "application/json");
        //requestBuilder.setHeader("x-api-key", getApiKey());
        //requestBuilder.setHeader( "Authorization", accessToken );
        URIBuilder uriBuilder = new URIBuilder(url);

        if(queryStringParameters != null && !queryStringParameters.isEmpty()) {
            for(Map.Entry<String, String> entry : queryStringParameters.entrySet()) {
                uriBuilder.addParameter(entry.getKey(), entry.getValue());
            }
        }

        requestBuilder.uri(uriBuilder.build());

        switch(method){
            case "GET":
                requestBuilder.GET();
                break;
            case "POST":
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
                break;
        }
        requestBuilder.timeout(Duration.of(180, ChronoUnit.SECONDS));
        return requestBuilder.build();
    }

    //TODO: this needs to be updated later
    public static String getBaseUri(){
        return "http://tax-sfe-service.engines-stg.use1.atpco.local";
    }



    private TaxServiceFeeQuery createTaxServiceFeeQueryStub() {
        TaxServiceFeeQuery taxServiceFeeQueryStub = TaxServiceFeeQuery.builder()
                .itinerary(new TaxItinerary(buildTaxServiceFeeLegs()))
                //.serviceFees()
                .fares(Arrays.asList(
                        FareInfo.builder()
                                .fareBasisTicketDesignator("")
                                //.fareTypeCode(null)
                                .tariff(0)
                                .privateTariff(false)
                                .domesticFare(false)
                                .fareOwningCarrier("DL")
                                //.rule(null)
                                .tripType(TripType.ROUND_TRIP)
                                //.passengerType(null)
                                .fareAmount(new BigDecimal("0.01"))
                                .fareCurrency("USD")
                                .build(),
                        FareInfo.builder()
                                .fareBasisTicketDesignator("")
                                //.fareTypeCode(null)
                                .tariff(0)
                                .privateTariff(false)
                                .domesticFare(false)
                                .fareOwningCarrier("DL")
                                //.rule(null)
                                .tripType(TripType.ROUND_TRIP)
                                //.passengerType(null)
                                .fareAmount(new BigDecimal("0.01"))
                                .fareCurrency("USD")
                                .build()
                ))
                .ticketDate(parseDateTime("20250429", false))
                //.equivalentFaresTotal(null)
                .validatingCarrier("DL")
                .pointOfSale(new Country("US", 1, 100))
                .pointOfTicketing(new Country("US", 1, 100))
                .faresTotal(new BigDecimal("0.02"))
                .feesTotal(new BigDecimal("0.00"))
                .ticketCurrency("USD")
                .enableDiagnostics(true)
                .includeExemptSequence(true)
                .build();

        //Setting addl fields on taxServiceFeeQueryStub that are not in the builder option
        taxServiceFeeQueryStub.setResponseCurrency("USD");
        taxServiceFeeQueryStub.setInvoluntary(false);
        System.out.println("taxServiceFeeQueryStub: " + taxServiceFeeQueryStub);

        return taxServiceFeeQueryStub;
    }


    //Helper method to create the TaxServiceFeeLeg types
    private static List<TaxServiceFeeLeg> buildTaxServiceFeeLegs(){

        List<TaxServiceFeeLeg> taxServiceFeeLegs = new ArrayList<>();

        //leg1
        TaxServiceFeeLeg leg1 = new TaxServiceFeeLeg();

        leg1.setDeparts(parseDateTime("250601 08:10", true));
        leg1.setArrives(parseDateTime("250601 20:00", true));
        leg1.setLegId(1);

        //Not working
        // Using a simpler approach to set the origin
        //leg1.setOrigin(new Airport("JFK", null, null));
        // Using a simpler approach to set the destination
        //leg1.setDestination(new Airport("LHR", null, null));

        City originCityL1 = new City("NYC", "NY", "US", 100, 1);
        leg1.setOrigin(new Airport("JFK", originCityL1, "North America"));

        City destCityL1 = new City("LON", "ENG", "GB", 304, 2);
        leg1.setDestination(new Airport("LHR", destCityL1, "Europe"));

        leg1.setMarketedCarrier("DL");
        leg1.setMarketedFlightNo(5996);
        leg1.setOperatedCarrier("VA");
        leg1.setOperatedFlightNo(5996);
        leg1.setRbd("");
        leg1.setLegIndicator(LegIndicatorType.F);
        leg1.setFareIndex(0);
        leg1.setTransferType(TransferType.STOP_OVER);
        leg1.setInvoluntary(false);

        //leg2
        TaxServiceFeeLeg leg2 = new TaxServiceFeeLeg();

        leg2.setDeparts(parseDateTime("250608 09:35", true));
        leg2.setArrives(parseDateTime("250608 12:45", true));
        leg2.setLegId(2);
        /*// Using a simpler approach to set the origin
        leg2.setOrigin(new Airport("LHR", null, null));
        // Using a simpler approach to set the destination
        leg2.setDestination(new Airport("JFK", null, null));*/


        City originCityL2 = new City("LON", "ENG", "GB", 304, 2);
        leg2.setOrigin(new Airport("LHR", originCityL2, "Europe"));

        City destCityL2 = new City("NYC", "NY", "US", 100, 1);
        leg2.setDestination(new Airport("JFK", destCityL2, "North America"));

        leg2.setMarketedCarrier("DL");
        leg2.setMarketedFlightNo(5997);
        leg2.setOperatedCarrier("VA");
        leg2.setOperatedFlightNo(5997);
        leg2.setRbd("");
        leg2.setLegIndicator(LegIndicatorType.F);
        leg2.setFareIndex(0);
        leg2.setInvoluntary(false);

        taxServiceFeeLegs.add(leg1);
        taxServiceFeeLegs.add(leg2);

        return taxServiceFeeLegs;

    }

    // Helper method to parse date string in format "YYMMDD HH:mm"
    private static Date parseDateTime(String dateTimeStr, boolean isTimeIncluded) {
        Calendar cal = Calendar.getInstance();
        int year = 2000 + Integer.parseInt(dateTimeStr.substring(0, 2));
        int month = Integer.parseInt(dateTimeStr.substring(2, 4)) - 1; // Calendar months are 0-based
        int day = Integer.parseInt(dateTimeStr.substring(4, 6));
        if (isTimeIncluded) {
            int hour = Integer.parseInt(dateTimeStr.substring(7, 9));
            int minute = Integer.parseInt(dateTimeStr.substring(10, 12));
            cal.set(year, month, day, hour, minute, 0);
        } else {
            cal.set(year, month, day, 0, 0, 0);
        }
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }


}

package com.threevictors.aws.priceeye.exp;



import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.google.gson.JsonObject;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.loader.PEItinerariesLoader;
import com.threevictors.aws.priceeye.exp.loader.PEItinsFromCSVLoader;
import com.threevictors.aws.priceeye.exp.loader.X1TaxRecordDataPointsLoader;
import com.threevictors.aws.priceeye.exp.model.*;
import com.threevictors.aws.priceeye.exp.velocity.builder.TaxEngineRequestBuilder;
import net.atpco.ash.enums.LegIndicatorType;
import net.atpco.engine.common.types.TransferType;
import net.atpco.fare.domain.types.TripType;
import net.atpco.service.fee.client.request.TaxServiceFeeQuery;
import net.atpco.service.fee.client.request.TaxItinerary;
import net.atpco.service.fee.client.request.FareInfo;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;

//import java.time.LocalDateRange;
import java.util.Arrays;
import java.util.ArrayList;
import net.atpco.ash.location.vo.*;
import net.atpco.service.fee.client.request.TaxServiceFeeLeg;
import org.apache.http.client.utils.URIBuilder;

//Worked when I used snapshot
//import net.atpco.service.fee.configuration.ServiceFeeEngineConfiguration;
//import net.atpco.service.fee.configuration.ServiceFeeEngineProdConfiguration;
//import net.atpco.service.fee.ServiceFeeEngine;


public class PEEnginesTaxesExpApplication {

    protected static Gson gson = new GsonBuilder()
        .setDateFormat("yyMMdd HH:mm")
        .create();
    protected HttpClient communicator;

    private static Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap;
    private static final String FLAT_TAX = "Flat Tax";
    private static final String PERCENT_TAX = "Percent Tax";


    public static void main(String[] args) {

        PEEnginesTaxesExpApplication currentApp = new PEEnginesTaxesExpApplication();
        TaxEngineRequestBuilder taxEngineRequestBuilder = new TaxEngineRequestBuilder();

        X1TaxRecordDataPointsLoader x1TaxRecordDataPointsLoader = new X1TaxRecordDataPointsLoader();
        x1TaxRecordDataPointsMap = x1TaxRecordDataPointsLoader.loadTaxRecordDataPoints("pe-engines-taxes/src/main/resources/xldatapoints_all_taxrecs_from_redis_all.txt");

        PEItinerariesLoader peItinerariesLoader = new PEItinerariesLoader();

        List<PEItinerary> itins = new ArrayList<>();

        //NOTE: Check if the args is added or not before each run
        //Send this args to load itins from athena's CSV data
        if(args != null && args.length > 0 && args[0].equals("loadFromCSV")){
            PEItinsFromCSVLoader.populateItinDataFromAthenaCSV();
            itins = peItinerariesLoader.readPEItineraries("pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv/PEItins_from_athena_generated_ouput.txt");
        }
        else{
            //This is when you already have the itineraries generated from a system test such as ProviderAATest in priceeye-v2
            itins = peItinerariesLoader.readPEItineraries("pe-engines-taxes/src/main/resources/itineraries.txt");
        }

        /*
            First, stub the TaxServiceFeeQuery object and the other booleans as per the
            required signature on how the TaxesController makes the call.
            Stub a PEItinerary object with some values and transform it to TaxServiceFeeQuery query and make the call.
            Print the calculated taxes.
            Note that the PEItinerary does not have basefare, so start with a value of 100 as the totalFare.
        */

        int loopCounter = 0;
        for (PEItinerary currentItin : itins) {

            String reqBody = taxEngineRequestBuilder.buildRequest(currentItin);

            JsonObject reqBodyAsJsonObject = new Gson().fromJson(reqBody, JsonObject.class);
            int taxLegsCount = reqBodyAsJsonObject.getAsJsonObject("itinerary")
                    .getAsJsonArray("taxLegs")
                    .size();

            //Make a call to Engines with the json string
            HttpResponse<String> response = currentApp.sendRequest(getBaseUri() + "/tax", "POST", reqBody, null);

            if(response != null){
                System.out.println("\n");
                System.out.println("Response code: " + response.statusCode());
                //System.out.println("Response body: " + response.body());
                //Wait 2 seconds before the next call
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                RootResponse convertedResponse = (RootResponse) convert(response.body(), RootResponse.class);
                System.out.println("Converted response for currentItin: " + convertedResponse);
                System.out.println("Examining Taxes...");

                examineTaxes(convertedResponse, currentItin, taxLegsCount);

                System.out.println("\n");
                System.out.println("Done with loopcount = " + ++loopCounter);
                System.out.println("\n\n");

            } else {
                System.out.println("Response is null");
            }

        }//End of for loop
    }//End of main method.


    /*
        This method is a placeholder for examining the taxes in the response.
        You can implement your logic here to process the taxes as needed.
        For now, it just prints the response.
     */
    private static void examineTaxes(RootResponse convertedResponse, PEItinerary currentItin, int taxLegsCount) {

        System.out.println("\nExamining taxes for currentItin: " + currentItin);

        double itinTpOriginal = currentItin.getTotalPrice();
        double currentItinTaxes = currentItin.getTaxes();

        double itinTpDeductedWithTaxes = currentItin.getTotalPrice();

        if(convertedResponse != null){
            ArrayList<ExecutionResponse> executionResponses = convertedResponse.getExecutionResponses();
            if(executionResponses != null && !executionResponses.isEmpty()){
                //foreach executionResponse in executionResponses get the taxes
                for(ExecutionResponse executionResponse : executionResponses){
                    //Get the taxes
                    ArrayList<Taxes> taxes = executionResponse.getTaxes();
                    //Map containing the flat tax and percent tax values respectively.
                    Map<String, List<Double>> flatOrPercentValuesMap = new HashMap<>();

                    //for each tax in taxes get the chargeDetails
                    for(Taxes tax : taxes){

                        ArrayList<ChargeDetail> chargeDetails = tax.getChargeDetails();
                         if(chargeDetails != null && !chargeDetails.isEmpty()){
                               for (ChargeDetail currentChargeDetail : chargeDetails) {
                                   String mapKeyLookup = currentChargeDetail.getTaxKey() + "," + currentChargeDetail.getTaxSequenceNumber();
                                   String percentOrFlatTag = x1TaxRecordDataPointsMap.get(mapKeyLookup).getPercentOrFlatTag();
                                    //{key='US,AY,001,828212.96b48c47e3dad9d2a303b870708f3740', nation='US', taxCode='AY',
                                   // percentOrFlatTag=Flat Tax, seqNo=828212, taxCarrier='YY', taxAmount=5.60, taxAmountCurrency='USD', taxPercent=0.0000, minTaxWhenPercent=0, maxTaxWhenPercent=0}

                                   //{key='IN,K3,008,62537.0499185ceda910eb0399c6bc91b5eaea', nation='IN', taxCode='K3',
                                   // percentOrFlatTag=Percent Tax, seqNo=62537, taxCarrier='QF', taxAmount=null, taxAmountCurrency='null', taxPercent=0.0000, minTaxWhenPercent=0, maxTaxWhenPercent=0}

                                   //if it's flat Tax, select taxAmount and add it to the list, but if it's percent tax, select taxPercent and add it to the list
                                   if ("Flat Tax".equals(percentOrFlatTag)) {
                                       flatOrPercentValuesMap
                                               .computeIfAbsent(FLAT_TAX, k -> new ArrayList<>())
                                               //Note: The tax amount should NOT be pulled from the X1TaxRecordDataPointsMap as the taxAmount might be in other currency.
                                               // Example JP,TK,001,100000. the tax amount is 1000 but it's in JPY.
                                               //.add(x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxAmount());
                                               .add(currentChargeDetail.getResponseCharge());

                                                //Added for debugging
                                        System.out.println("Flat tax found: " + currentChargeDetail.getResponseCharge());


                                   } else if ("Percent Tax".equals(percentOrFlatTag)) {
                                       flatOrPercentValuesMap
                                               .computeIfAbsent(PERCENT_TAX, k -> new ArrayList<>())
                                               .add(x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxPercent());
                                   }
                               }//end for on chargeDetails
                         }

                    }//End for on taxes

                    System.out.println("Total Price original (before ALL taxes subtracted) itinTpOriginal: " + itinTpOriginal);
                    //currentItinTaxes
                    System.out.println("Total Taxes amount as found on currentItin: " + currentItinTaxes);


                    //Check flatOrPercentValuesMap for flat tax and percent tax
                    if(flatOrPercentValuesMap != null && !flatOrPercentValuesMap.isEmpty()){
                        List<Double> flatTaxList = flatOrPercentValuesMap.get(FLAT_TAX);
                        //loop through the flat tax list and add the values
                        double totalFlatTaxAmount = flatTaxList.stream().mapToDouble(Double::doubleValue).sum();
                        System.out.println("totalFlatTaxAmount: " + totalFlatTaxAmount);

                        //subtract total flatTaxAmopunt
                        itinTpDeductedWithTaxes -= totalFlatTaxAmount;
                        double itinTpDeductedWithFlatTaxes = itinTpDeductedWithTaxes;
                        System.out.println("itinTpDeductedWithFlatTaxes: " + itinTpDeductedWithFlatTaxes);

                        //subtract PFC
                        System.out.println("taxLegsCount: " + taxLegsCount);
                        //PFC = 4.50 per leg. Cap it at 18.00
                        double pfcTaxes = Math.min(taxLegsCount * 4.50, 18.00);
                        System.out.println("pfcTaxes: " + pfcTaxes);
                        double itinTpDeductedWithFlatTaxesAndPFC = Math.round((itinTpDeductedWithFlatTaxes - pfcTaxes) * 100.0) / 100.0;
                        System.out.println("itinTpDeductedWithFlatTaxesAndPFC: " + itinTpDeductedWithFlatTaxesAndPFC);

                        double percentTaxTotalAmount  = 0.0;
                        double percentTaxTotal  = 0.0;

                        List<Double> percentTaxList = flatOrPercentValuesMap.get(PERCENT_TAX) != null ? flatOrPercentValuesMap.get(PERCENT_TAX) : null;

                        if(percentTaxList != null && !percentTaxList.isEmpty()){
                            //loop through the percent tax list and get each percent tax value
                            for (Double percentTax : percentTaxList) {
                                //Add all percentage points values
                                //Ex: 7.5% + 2.5% = 10%
                                percentTaxTotal+= percentTax;
                            }
                        } else {
                            System.out.println("No percent tax found");
                        }


                        /* Base Fare + Base Fare*0.075 + Flat Taxes = Total
                           Or Total Taxes = Base Fare * 0.075 + Flat Taxes.
                           So if x = basefare and there are percentages as 7.5 and 2.5
                           X + (X*0.075) + (X*0.025) = 500
                            X(1+0.075+0.025) = 500
                            X(1.1) = 500
                            X=500/1.1
                            So percentTaxTotalAmount = (500 - (500/1.1)) rounded up*/
                        System.out.println("percentTaxTotal: " + percentTaxTotal + "% will be applied on itinTpDeductedWithFlatTaxesAndPFC = " + itinTpDeductedWithFlatTaxesAndPFC
                                +  " as per the equation (itinTpDeductedWithFlatTaxes - (itinTpDeductedWithFlatTaxes / (1 + (percentTaxTotal / 100.0)))");
                        percentTaxTotalAmount = Math.round((itinTpDeductedWithFlatTaxesAndPFC - (itinTpDeductedWithFlatTaxesAndPFC / (1 + (percentTaxTotal / 100.0)))) * 100.0) / 100.0;


                        System.out.println("percentTaxTotalAmount: " + percentTaxTotalAmount);
                        System.out.println("percentTaxTotalAmount + totalFlatTaxAmount : = " + (percentTaxTotalAmount + totalFlatTaxAmount));

                        itinTpDeductedWithTaxes = Math.round((itinTpDeductedWithFlatTaxesAndPFC - percentTaxTotalAmount) * 100.0) / 100.0;

                        System.out.println("ItinTP after flattax, pfc taxes and percentTax removed : " + itinTpDeductedWithTaxes);

                    } else {
                        System.out.println("No taxes found");
                    }
                }

            }

        }
        else {
            System.out.println("Converted response is null");
        }

    }//end of examineTaxes


    public static <T> List<T> convertToList( String body, Type type ) {
        return gson.fromJson( body, type );
    }

    public static <T> T convert( String body, Type type ) {
        return gson.fromJson( body, type );
    }

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

    //TODO: this needs to be updated later. Maybe read from a config file on s3
    public static String getBaseUri(){
        return "http://tax-sfe-service.engines-stg.use1.atpco.local";
    }

}

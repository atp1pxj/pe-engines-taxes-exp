package com.threevictors.aws.priceeye.exp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.opencsv.CSVReader;
import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.dao.MetadataReader;
import com.threevictors.aws.priceeye.exp.loader.PEItinerariesLoader;
import com.threevictors.aws.priceeye.exp.loader.UniqueMktsPEItinsLoader;
import com.threevictors.aws.priceeye.exp.loader.X1TaxRecordDataPointsLoader;
import com.threevictors.aws.priceeye.exp.model.*;
import com.threevictors.aws.priceeye.exp.velocity.builder.TaxEngineRequestBuilder;
import org.apache.http.client.utils.URIBuilder;

import java.io.File;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.io.BufferedReader;
import java.io.FileReader;
import com.opencsv.CSVReader;


public class PEEnginesTaxesExpApplicationParallelAlternate {

    protected static Gson gson = new GsonBuilder()
            .setDateFormat("yyMMdd HH:mm")
            .create();
    protected HttpClient communicator;

    private static Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap;
    private static Map<String, String> airportCountryCodeMap;

    private static final String FLAT_TAX = "Flat Tax";
    private static final String PERCENT_TAX = "Percent Tax";
    //Made up string to identify the tax on tax
    private static final String TAX_ON_TAX = "tax on tax";

    //private static final int QUEUE_CAPACITY = 1000; // Tune as needed
    private static final int QUEUE_CAPACITY = 2000; // Tune as needed
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();


    public static void main(String[] args) throws Exception {

        PEEnginesTaxesExpApplicationParallelAlternate currentApp = new PEEnginesTaxesExpApplicationParallelAlternate();
        TaxEngineRequestBuilder taxEngineRequestBuilder = new TaxEngineRequestBuilder();

        X1TaxRecordDataPointsLoader x1TaxRecordDataPointsLoader = new X1TaxRecordDataPointsLoader();
        x1TaxRecordDataPointsMap = x1TaxRecordDataPointsLoader.loadTaxRecordDataPoints("pe-engines-taxes/src/main/resources/xldatapoints_all_taxrecs_from_redis_all.txt");

        //Load the airport country code map
        MetadataReader metadataReader = new MetadataReader();
        airportCountryCodeMap = metadataReader.getAirportCountryMapUSDomesticOnly();

        PEItinerariesLoader peItinerariesLoader = new PEItinerariesLoader();
        List<PEItinerary> itins = new ArrayList<>();


        //Note: This class relies on already loaded csv data in generated_output_txts/PEItins_parallel_unique_output.txt file by running UniqueMktsPEItinsLoader.java

        // *************** New way of calling Engines
        //File outputfileWithPEItinsString = new File("pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv_parallel/generated_output_txts/PEItins_parallel_unique_output.txt");
        File outputfileWithPEItinsString = new File("pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv_parallel/generated_output_txts/PEItins_parallel_unique_output_deduped.txt");

        BlockingQueue<List<String>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        AtomicInteger requestCount = new AtomicInteger(0);

        // Start worker threads
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    List<String> line = queue.take();
                    do {
                        if(line.get(0).equals("__EOF__")) {
                            break;
                        }
                        PEItinerary currentItin = PEItinerariesLoader.parsePEItineraryLine(line);

                        if (currentItin == null) {
                            continue;
                        }

                        String reqBody = taxEngineRequestBuilder.buildRequest(currentItin);
                        JsonObject reqBodyAsJsonObject = new Gson().fromJson(reqBody, JsonObject.class);
                        int taxLegsCount = reqBodyAsJsonObject.getAsJsonObject("itinerary")
                                .getAsJsonArray("taxLegs").size();

                        HttpResponse<String> response = currentApp.sendRequest(
                                getBaseUri() + "/tax", "POST", reqBody, null);

                        if (response != null) {
                            System.out.println("\n");
                            System.out.println("Response code: " + response.statusCode());

                            int currentCount = requestCount.incrementAndGet();
                            System.out.println("HTTP request count: " + currentCount);

                            RootResponse convertedResponse = (RootResponse) convert(response.body(), RootResponse.class);
                            examineTaxes(convertedResponse, currentItin, taxLegsCount);
                        } else {
                            System.err.println("Null response for itinerary: " + currentItin);
                        }
                        Thread.sleep(10); // Throttle
                        line = queue.take();
                    } while(true); //End of while
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });//end of executor.submit
        }//end of for loop on THREAD_COUNT

        // Read lines and place onto queue. This was when PEItins were read from a file.
        /*try (BufferedReader reader = new BufferedReader(new FileReader(outputfileWithPEItinsString))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                queue.put(line+", "+lineNumber);
            }
            // Signal EOF to workers
            for (int i = 0; i < THREAD_COUNT; i++) {
                queue.put("__EOF__");
            }
        }*/

        //Read the CSV directly and put it into the queue
        try (CSVReader reader = new CSVReader(new FileReader(outputfileWithPEItinsString))) {
            String line[];
            int lineNumber = 0;
            while ((line = reader.readNext()) != null) {
                lineNumber++;
                 List<String> lineList = new ArrayList<>(Arrays.asList(line));
                 lineList.add(String.valueOf(lineNumber));
                 //if(lineNumber == 2) {
                     queue.put(lineList);
                 //}

            }
            // Signal EOF to workers
            for (int i = 0; i < THREAD_COUNT; i++) {
                //queue.put("__EOF__");
                queue.put(Collections.singletonList("__EOF__"));
            }
        }

        System.out.println("!!!!!!!!!!!!!! File is being read !!!!!!!!!!!!!!!!");
        latch.await();
        System.out.println("***************** Latch is empty! *****************");
        executor.shutdown();
        System.out.println("✅ Processing complete.");

    }//End of main method.


    /*
        This method is a placeholder for examining the taxes in the response.
        You can implement your logic here to process the taxes as needed.
        For now, it just prints the response.
     */
    private static void examineTaxes(RootResponse convertedResponse, PEItinerary currentItin, int taxLegsCount) {

        System.out.println("\nExamining taxes for currentItin: " + currentItin);

        BigDecimal itinTpOriginal = BigDecimal.valueOf(currentItin.getTotalPrice())
                .setScale(2, BigDecimal.ROUND_HALF_UP);

        BigDecimal currentItinTaxes = BigDecimal.valueOf(currentItin.getTaxes()).setScale(2, BigDecimal.ROUND_HALF_UP);


        //Hold the original total price of the itinerary in this variable
        BigDecimal itinTpDeductedWithTaxes = BigDecimal.valueOf(currentItin.getTotalPrice())
                .setScale(2, BigDecimal.ROUND_HALF_UP);

        if(convertedResponse != null){
            ArrayList<ExecutionResponse> executionResponses = convertedResponse.getExecutionResponses();
            if(executionResponses != null && !executionResponses.isEmpty()){
                //foreach executionResponse in executionResponses get the taxes
                for(ExecutionResponse executionResponse : executionResponses){
                    //Get the taxes
                    ArrayList<Taxes> taxes = executionResponse.getTaxes();
                    //Map containing the flat tax and percent tax values respectively.
                    Map<String, List<BigDecimal>> flatOrPercentValuesMap = new HashMap<>();

                    //for each tax in taxes get the chargeDetails
                    for(Taxes tax : taxes){

                        ArrayList<ChargeDetail> chargeDetails = tax.getChargeDetails();
                        if(chargeDetails != null && !chargeDetails.isEmpty()){
                            for (ChargeDetail currentChargeDetail : chargeDetails) {
                                String mapKeyLookup = currentChargeDetail.getTaxKey() + "," + currentChargeDetail.getTaxSequenceNumber();

                                //String percentOrFlatTag = x1TaxRecordDataPointsMap.get(mapKeyLookup).getPercentOrFlatTag();
                                String percentOrFlatTag = Optional.ofNullable(x1TaxRecordDataPointsMap.get(mapKeyLookup))
                                        .map(X1TaxRecordDataPoints::getPercentOrFlatTag)
                                        .orElse(null);

                                   /*System.out.println("tax code - amount - type (flat, percentage, tax on tax");
                                   System.out.println(mapKeyLookup + " - " +  x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxAmount() + " - " + x1TaxRecordDataPointsMap.get(mapKeyLookup).getPercentOrFlatTag());*/
                                System.out.println("--------------------------------------------------------------------------------------\n");
                                System.out.println("tax code : taxAmount(flatTaxOnly) : taxAmtCurrency(flatTaxOnly) : percent(percentTaxonly) : type (flat, percentage, tax on tax") ;
                                //System.out.println(mapKeyLookup + " : " +  x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxAmount() + " : " + x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxAmountCurrency() + " : " + x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxPercent() + " : " + x1TaxRecordDataPointsMap.get(mapKeyLookup).getPercentOrFlatTag());

                                if(x1TaxRecordDataPointsMap.get(mapKeyLookup) != null) {
                                    System.out.println(mapKeyLookup + " : " +  x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxAmount() + " : " + x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxAmountCurrency() + " : " + x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxPercent() + " : " + x1TaxRecordDataPointsMap.get(mapKeyLookup).getPercentOrFlatTag());
                                }
                                else {
                                    System.out.println(">>>>>>>>>>>> No data found for mapKeyLookup: " + mapKeyLookup);
                                }

                                System.out.println("--------------------------------------------------------------------------------------");


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
                                            .add(currentChargeDetail.getResponseCharge().setScale(2, BigDecimal.ROUND_HALF_UP));

                                    //Added for debugging
                                    System.out.println("Flat tax found: " + currentChargeDetail.getResponseCharge().setScale(2, BigDecimal.ROUND_HALF_UP));


                                } else if ("Percent Tax".equals(percentOrFlatTag)) {

                                    //if "chargeDescription": has a string something like "xxx% of 100.00USD", then the percentage points need to be added to the map
                                    //but if it's something else like "chargeDescription": "13.0000% of 26.80USD" it means it's tax on tax. So treat it as a flat tax.
                                    System.out.println("Percent tax for MapKey: " + mapKeyLookup);
                                    System.out.println("Percent tax found on map: " + BigDecimal.valueOf(x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxPercent()) + " %");

                                    //Note:
                                    //We know that it's already a percent tax, so we need to check if the chargeDescription has a string like "xxx% of 100.00USD" to
                                    //determine if it's truly a percent tax on basefare or a tax on tax.
                                    //Since we are setting the total fare to 100, a true percent tax will be something like 7.5% of 100.00USD
                                    //But if it's a tax on tax, it will be something like 13.0000% of 26.80USD
                                    if(currentChargeDetail.getChargeDescription() != null && currentChargeDetail.getChargeDescription().contains("% of 100.00USD")){
                                        //Add the percentage points to the percent tax list
                                        flatOrPercentValuesMap
                                                .computeIfAbsent(PERCENT_TAX, k -> new ArrayList<>())
                                                .add(BigDecimal.valueOf(x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxPercent()));
                                        System.out.println("Seems a PERCENT tax on BASE FARE: " + currentChargeDetail.getChargeDescription());
                                        System.out.println("Percent tax CHARGE DESCRIPTION on response: " + currentChargeDetail.getChargeDescription());
                                        System.out.println("Percent tax AMOUNT found on response: " + currentChargeDetail.getCharge().setScale(2, BigDecimal.ROUND_HALF_UP));


                                    } else {

                                        System.out.println("Seems a PERCENT TAX on OTHER TAX: " + currentChargeDetail.getChargeDescription());
                                        System.out.println("Percent tax CHARGE DESCRIPTION on response: " + currentChargeDetail.getChargeDescription());
                                        System.out.println("Percent tax AMOUNT found on response: " + currentChargeDetail.getResponseCharge().setScale(2, BigDecimal.ROUND_HALF_UP));
                                        System.out.println("Adding to TAX ON TAX: " + currentChargeDetail.getResponseCharge().setScale(2, BigDecimal.ROUND_HALF_UP));
                                        //Add it to the flat tax list
                                        flatOrPercentValuesMap
                                                .computeIfAbsent(TAX_ON_TAX, k -> new ArrayList<>())
                                                .add(currentChargeDetail.getResponseCharge().setScale(2, BigDecimal.ROUND_HALF_UP));
                                        System.out.println("Added as Tax on tax: " + currentChargeDetail.getResponseCharge().setScale(2, BigDecimal.ROUND_HALF_UP));
                                    }
                                }
                            }//end for on chargeDetails
                        }

                    }//End for on taxes
                    System.out.println("--------------------------------------------------------------------------------------");
                    System.out.println("Total_Price original (before ALL taxes subtracted) itinTpOriginal: " + itinTpOriginal);
                    //currentItinTaxes
                    System.out.println("Total_Taxes original on currentItin: " + currentItinTaxes);
                    System.out.println("--------------------------------------------------------------------------------------\n");

                    //Check flatOrPercentValuesMap for flat tax and percent tax
                    if(flatOrPercentValuesMap != null && !flatOrPercentValuesMap.isEmpty()) {
                        List<BigDecimal> flatTaxList = flatOrPercentValuesMap.get(FLAT_TAX);
                        //loop through the flat tax list and add the values
                        BigDecimal totalFlatTaxAmount = flatTaxList.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                        System.out.println("totalFlatTaxAmount (FLAT TAX TOTAL AMT) : " + totalFlatTaxAmount);

                        //TAX ON TAX total
                        BigDecimal totalTaxOnTaxAmount = BigDecimal.ZERO;
                        //Loop through tax on tax, sum up all those values and add it to the calculated taxes
                        List<BigDecimal> taxOnTaxList = flatOrPercentValuesMap.get(TAX_ON_TAX) != null ? flatOrPercentValuesMap.get(TAX_ON_TAX) : null;
                        if(taxOnTaxList != null && !taxOnTaxList.isEmpty()){
                            totalTaxOnTaxAmount = taxOnTaxList.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                            System.out.println("totalTaxOnTaxAmount (TAX ON TAX TOTAL AMT) : " + totalTaxOnTaxAmount);
                        }

                        //subtract total flatTaxAmount and totalTaxOnTaxAmount from itinTpOriginal
                        itinTpDeductedWithTaxes = itinTpDeductedWithTaxes.subtract(totalFlatTaxAmount).subtract(totalTaxOnTaxAmount);

                        BigDecimal itinTpDeductedWithFlatTaxes = itinTpDeductedWithTaxes;
                        System.out.println("itinTpDeductedWithFlatTaxes: (ITINTP - FLAT TAX TOTAL - Tax on Tax flat amount) " + itinTpDeductedWithFlatTaxes);
                        System.out.println("--------------------------------------------------------------------------------------\n");
                        System.out.println("Total taxLegsCount (Engine request) : " + taxLegsCount);

                        //PFC taxes
                        //PFC = 4.50 per leg. Cap it at 18.00
                        //Loop through all the outbound legs and check if the origin airport is in the US ONLY
                        //If yes, then add the PFC taxes. Same for inbound legs.
                        AtomicReference<BigDecimal> pfcTaxes = new AtomicReference<>(BigDecimal.ZERO);
                        int pfcLegsCount = 0;
                        //US origin airport codes
                        for(RawLeg leg : currentItin.getOutboundLegs()){
                            if (airportCountryCodeMap.containsKey(leg.getOriginAirportCode())) {
                                pfcTaxes.set(pfcTaxes.get().add(BigDecimal.valueOf(4.50)));
                                pfcLegsCount++;
                            }
                        }
                        for(RawLeg leg : currentItin.getInboundLegs()){
                            if (airportCountryCodeMap.containsKey(leg.getOriginAirportCode())) {
                                pfcTaxes.set(pfcTaxes.get().add(BigDecimal.valueOf(4.50)));
                                pfcLegsCount++;
                            }
                        }

                        System.out.println("Taxlegs USED for pfcTaxes: " + pfcLegsCount);
                        System.out.println("Total pfcTaxes: " + pfcTaxes);

                        BigDecimal itinTpDeductedWithFlatTaxesAndPFC = itinTpDeductedWithFlatTaxes
                                .subtract(pfcTaxes.get().min(BigDecimal.valueOf(18)))
                                .setScale(2, BigDecimal.ROUND_HALF_UP);

                        System.out.println("itinTpDeductedWithFlatTaxesAndPFC: (ITINTP - FLAT TAX TOTAL - TAX ON TAX - PFC_TAXES)  " + itinTpDeductedWithFlatTaxesAndPFC);
                        System.out.println("--------------------------------------------------------------------------------------\n");

                        /*double percentTaxTotalAmount  = 0.0;
                        double percentTaxTotal  = 0.0;*/

                        BigDecimal percentTaxTotalAmount = BigDecimal.ZERO;
                        BigDecimal percentTaxTotal = BigDecimal.ZERO;


                        List<BigDecimal> percentTaxList = flatOrPercentValuesMap.get(PERCENT_TAX) != null ? flatOrPercentValuesMap.get(PERCENT_TAX) : null;

                        if(percentTaxList != null && !percentTaxList.isEmpty()){
                            //Loop through the percent tax list,get each percent tax value and sum up all percent tax values.
                            percentTaxTotal = percentTaxList.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

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
                        //percentTaxTotalAmount = Math.round((itinTpDeductedWithFlatTaxesAndPFC - (itinTpDeductedWithFlatTaxesAndPFC / (1 + (percentTaxTotal / 100.0)))) * 100.0) / 100.0;
                        //percentTaxTotalAmount calculation
                        BigDecimal hundred = new BigDecimal("100");
                        BigDecimal one = BigDecimal.ONE;

                        //VERY IMPORTANT: Use the same scale or precision of 4 (Keep it at 4 ONLY) for all the calculations.
                        BigDecimal denominator = one.add(percentTaxTotal.divide(hundred, 4, RoundingMode.HALF_UP));
                        BigDecimal fraction = itinTpDeductedWithFlatTaxesAndPFC.divide(denominator, 4, RoundingMode.HALF_UP);
                        BigDecimal difference = itinTpDeductedWithFlatTaxesAndPFC.subtract(fraction);

                        // Round final result to 2 decimal places
                        percentTaxTotalAmount = difference.setScale(2, RoundingMode.HALF_UP);
                        System.out.println("percentTaxTotalAmount: " + percentTaxTotalAmount);

                        BigDecimal calculatedTaxesTotal = percentTaxTotalAmount.add(totalFlatTaxAmount).add(pfcTaxes.get()).add(totalTaxOnTaxAmount);
                        System.out.println("calculatedTaxesTotal (percentTaxTotalAmount + totalFlatTaxAmount + PFCTaxes + totalTaxOnTaxAmount) (NO YQYR) = " + calculatedTaxesTotal);
                        System.out.println("currentItin's GIVEN Total taxes: " + currentItinTaxes);

                        //Check if the calculated taxes total is within + or - $1.00 of the currentItin's taxes
                        boolean didCalculatedTaxesMatch = currentItinTaxes.subtract(calculatedTaxesTotal)
                                .compareTo(BigDecimal.valueOf(-1.00)) >= 0
                                && currentItinTaxes.subtract(calculatedTaxesTotal)
                                .compareTo(BigDecimal.valueOf(1.00)) <= 0;

                        //Line number for debugging. Value set in channel for the time being.
                        System.out.println("currentItinTaxes(" + currentItinTaxes + ") == calculatedTaxesTotal(" + calculatedTaxesTotal + ") ?: " + didCalculatedTaxesMatch + "LN: " + currentItin.getChannel());
                        if(didCalculatedTaxesMatch){
                            System.out.println("currentItinTaxes - calculatedTaxesTotal = " + currentItinTaxes.subtract(calculatedTaxesTotal));
                        }
                        else{
                            System.out.println("currentItinTaxes - calculatedTaxesTotal difference is more than a dollar!");
                            System.out.println("currentItinTaxes - calculatedTaxesTotal = " + currentItinTaxes.subtract(calculatedTaxesTotal));
                        }

                        System.out.println("currentItin's YQYR taxes: " + currentItin.getYqyr());
                        itinTpDeductedWithTaxes = itinTpDeductedWithFlatTaxesAndPFC.subtract(percentTaxTotalAmount).setScale(2, BigDecimal.ROUND_HALF_UP);
                        System.out.println("ItinTP after flattax, taxontax, pfc taxes, percentTax removed (in that order) Base Fare : " + itinTpDeductedWithTaxes);

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

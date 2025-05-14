package com.threevictors.aws.priceeye.exp;

import com.opencsv.CSVReader;
import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.dao.MetadataReader;
import com.threevictors.aws.priceeye.exp.loader.PEItinerariesLoader;
import com.threevictors.aws.priceeye.exp.loader.X1TaxRecordDataPointsLoader;
import com.threevictors.aws.priceeye.exp.model.*;
import com.threevictors.aws.priceeye.exp.taxengine.TaxEngineCommunicator;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.io.FileReader;

public class PEEnginesTaxesExpApplicationParallelAlternate {

    private final static Log log = LogFactory.getLog(PEEnginesTaxesExpApplicationParallelAlternate.class);




    private static final String FLAT_TAX = "Flat Tax";
    private static final String PERCENT_TAX = "Percent Tax";
    //Made up string to identify the tax on tax
    private static final String TAX_ON_TAX = "tax on tax";

    //private static final int QUEUE_CAPACITY = 1000; // Tune as needed
    private static final int QUEUE_CAPACITY = 2000; // Tune as needed
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();

    private Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap;
    private Map<String, String> airportCountryCodeMap;

    private BlockingQueue<PEItinerary> queue;
    private ExecutorService executor;
    private CountDownLatch latch;

    private TaxEngineCommunicator taxEngineCommunicator;


    public PEEnginesTaxesExpApplicationParallelAlternate() {
        queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        executor = Executors.newFixedThreadPool(THREAD_COUNT);
        latch = new CountDownLatch(THREAD_COUNT);

        taxEngineCommunicator = new TaxEngineCommunicator();

        X1TaxRecordDataPointsLoader x1TaxRecordDataPointsLoader = new X1TaxRecordDataPointsLoader();
        x1TaxRecordDataPointsMap = x1TaxRecordDataPointsLoader.loadTaxRecordDataPoints("pe-engines-taxes/src/main/resources/xldatapoints_all_taxrecs_from_redis_all.txt");

        //Load the airport country code map
        MetadataReader metadataReader = new MetadataReader();
        airportCountryCodeMap = metadataReader.getAirportCountryMapUSDomesticOnly();

    }

    private void startWorkerThreads() {
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    PEItinerary itinerary = queue.take();
                    do {
                        if (itinerary.getOutboundLegs() == null ) {
                            break;
                        }

                        RootResponse rootResponse = taxEngineCommunicator.sendRequest( itinerary );

                        if (rootResponse != null) {
                            examineTaxes(rootResponse, itinerary );
                        }
                        else {
                            log.error("Null response for itinerary: " + itinerary);
                        }

                        Thread.sleep(10); // Throttle

                        itinerary = queue.take();
                    } while(true); //End of while
                } catch (Exception e) {
                    log.error("Error processing itinerary", e);
                } finally {
                    latch.countDown();
                }
            });//end of executor.submit
        }//end of for loop on THREAD_COUNT
    }



    private void readSourceFile(File source) {
        //Read the CSV directly and put it into the queue
        try (CSVReader reader = new CSVReader(new FileReader(source))) {
            String line[];
            int lineNumber = 0;

            while ((line = reader.readNext()) != null) {
                lineNumber++;
                List<String> lineList = new ArrayList<>(Arrays.asList(line));
                lineList.add(String.valueOf(lineNumber));

                PEItinerary itinerary = PEItinerariesLoader.parsePEItineraryLine(lineList);

                queue.put( itinerary );
            }

            // Signal EOF to workers
            for (int i = 0; i < THREAD_COUNT; i++) {
                queue.put(new PEItinerary());
            }
        }
        catch (Exception e) {
            log.error("Error reading source file", e);
        }

    }


    private void await()  {
        try {
            latch.await();
        } catch (InterruptedException e) {
            // Ignore
        }
        executor.shutdown();
    }

    public static void main(String[] args) throws Exception {

        PEEnginesTaxesExpApplicationParallelAlternate currentApp = new PEEnginesTaxesExpApplicationParallelAlternate();

        File source = new File("pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv_parallel/generated_output_txts/PEItins_parallel_unique_output_deduped.txt");

        currentApp.startWorkerThreads();
        currentApp.readSourceFile(source);
        currentApp.await();

        System.out.println("✅ Processing complete.");

    }//End of main method.


    /*
        This method is a placeholder for examining the taxes in the response.
        You can implement your logic here to process the taxes as needed.
        For now, it just prints the response.
     */
    private void examineTaxes(RootResponse convertedResponse, PEItinerary currentItin ) {

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

                                if(x1TaxRecordDataPointsMap.get(mapKeyLookup) == null) {
                                    log.error(">>>>>>>>>>>> No data found for mapKeyLookup: " + mapKeyLookup);
                                }

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

                                } else if ("Percent Tax".equals(percentOrFlatTag)) {

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

                                    } else {

                                        //Add it to the flat tax list
                                        flatOrPercentValuesMap
                                                .computeIfAbsent(TAX_ON_TAX, k -> new ArrayList<>())
                                                .add(currentChargeDetail.getResponseCharge().setScale(2, BigDecimal.ROUND_HALF_UP));
                                    }
                                }
                            }//end for on chargeDetails
                        }

                    }//End for on taxes

                    //Check flatOrPercentValuesMap for flat tax and percent tax
                    if(flatOrPercentValuesMap != null && !flatOrPercentValuesMap.isEmpty()) {
                        List<BigDecimal> flatTaxList = flatOrPercentValuesMap.get(FLAT_TAX);
                        //loop through the flat tax list and add the values
                        BigDecimal totalFlatTaxAmount = flatTaxList.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

                        //TAX ON TAX total
                        BigDecimal totalTaxOnTaxAmount = BigDecimal.ZERO;
                        //Loop through tax on tax, sum up all those values and add it to the calculated taxes
                        List<BigDecimal> taxOnTaxList = flatOrPercentValuesMap.get(TAX_ON_TAX) != null ? flatOrPercentValuesMap.get(TAX_ON_TAX) : null;
                        if(taxOnTaxList != null && !taxOnTaxList.isEmpty()){
                            totalTaxOnTaxAmount = taxOnTaxList.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                        }

                        //subtract total flatTaxAmount and totalTaxOnTaxAmount from itinTpOriginal
                        itinTpDeductedWithTaxes = itinTpDeductedWithTaxes.subtract(totalFlatTaxAmount).subtract(totalTaxOnTaxAmount);

                        BigDecimal itinTpDeductedWithFlatTaxes = itinTpDeductedWithTaxes;

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

                        BigDecimal itinTpDeductedWithFlatTaxesAndPFC = itinTpDeductedWithFlatTaxes
                                .subtract(pfcTaxes.get().min(BigDecimal.valueOf(18)))
                                .setScale(2, BigDecimal.ROUND_HALF_UP);

                        /*double percentTaxTotalAmount  = 0.0;
                        double percentTaxTotal  = 0.0;*/

                        BigDecimal percentTaxTotalAmount = BigDecimal.ZERO;
                        BigDecimal percentTaxTotal = BigDecimal.ZERO;


                        List<BigDecimal> percentTaxList = flatOrPercentValuesMap.get(PERCENT_TAX) != null ? flatOrPercentValuesMap.get(PERCENT_TAX) : null;

                        if(percentTaxList != null && !percentTaxList.isEmpty()){
                            //Loop through the percent tax list,get each percent tax value and sum up all percent tax values.
                            percentTaxTotal = percentTaxList.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

                        }

                        /* Base Fare + Base Fare*0.075 + Flat Taxes = Total
                           Or Total Taxes = Base Fare * 0.075 + Flat Taxes.
                           So if x = basefare and there are percentages as 7.5 and 2.5
                           X + (X*0.075) + (X*0.025) = 500
                            X(1+0.075+0.025) = 500
                            X(1.1) = 500
                            X=500/1.1
                            So percentTaxTotalAmount = (500 - (500/1.1)) rounded up*/

                        BigDecimal hundred = new BigDecimal("100");
                        BigDecimal one = BigDecimal.ONE;

                        //VERY IMPORTANT: Use the same scale or precision of 4 (Keep it at 4 ONLY) for all the calculations.
                        BigDecimal denominator = one.add(percentTaxTotal.divide(hundred, 4, RoundingMode.HALF_UP));
                        BigDecimal fraction = itinTpDeductedWithFlatTaxesAndPFC.divide(denominator, 4, RoundingMode.HALF_UP);
                        BigDecimal difference = itinTpDeductedWithFlatTaxesAndPFC.subtract(fraction);

                        // Round final result to 2 decimal places
                        percentTaxTotalAmount = difference.setScale(2, RoundingMode.HALF_UP);

                        BigDecimal calculatedTaxesTotal = percentTaxTotalAmount.add(totalFlatTaxAmount).add(pfcTaxes.get()).add(totalTaxOnTaxAmount);

                        //Check if the calculated taxes total is within + or - $1.00 of the currentItin's taxes
                        boolean didCalculatedTaxesMatch = currentItinTaxes.subtract(calculatedTaxesTotal)
                                .compareTo(BigDecimal.valueOf(-1.00)) >= 0
                                && currentItinTaxes.subtract(calculatedTaxesTotal)
                                .compareTo(BigDecimal.valueOf(1.00)) <= 0;

                        //Line number for debugging. Value set in channel for the time being.
                        System.out.println("currentItinTaxes(" + currentItinTaxes + ") == calculatedTaxesTotal(" + calculatedTaxesTotal + ") ?: " + didCalculatedTaxesMatch + "LN: " + currentItin.getChannel());

                        itinTpDeductedWithTaxes = itinTpDeductedWithFlatTaxesAndPFC.subtract(percentTaxTotalAmount).setScale(2, BigDecimal.ROUND_HALF_UP);

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

}

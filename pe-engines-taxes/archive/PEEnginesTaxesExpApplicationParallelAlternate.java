package com.threevictors.aws.priceeye.exp;

import com.opencsv.CSVReader;
import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.loader.PEItinerariesLoader;
import com.threevictors.aws.priceeye.exp.loader.PFCAmountsLoader;
import com.threevictors.aws.priceeye.exp.loader.X1TaxRecordDataPointsLoader;
import com.threevictors.aws.priceeye.exp.data.*;

import com.threevictors.aws.priceeye.exp.model.pfcengine.response.Charge;
import com.threevictors.aws.priceeye.exp.model.pfcengine.response.RootPFCResponse;
import com.threevictors.aws.priceeye.exp.model.taxengine.response.*;
import com.threevictors.aws.priceeye.exp.taxengine.PFCTaxEngineCommunicator;
import com.threevictors.aws.priceeye.exp.taxengine.TaxEngineCommunicator;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.io.FileReader;

public class PEEnginesTaxesExpApplicationParallelAlternate {

    private final static Log log = LogFactory.getLog(PEEnginesTaxesExpApplicationParallelAlternate.class);

    private static final String FLAT_TAX = "Flat Tax";
    private static final String PERCENT_TAX = "Percent Tax";
    //Made up string to identify the tax on tax
    private static final String TAX_ON_TAX = "tax on tax";

    private static final int QUEUE_CAPACITY = 2000; // Tune as needed
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();

    private Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap;

    //TODO: this might not be needed.
    private Map<String, Double> pfcTaxMap;

    private BlockingQueue<PEItinerary> queue;
    private ExecutorService executor;
    private CountDownLatch latch;

    private TaxEngineCommunicator taxEngineCommunicator;
    private PFCTaxEngineCommunicator pfcTaxEngineCommunicator;


    //25 values - previously mismatched tax line numbers
    /*List<Integer> misMatchTaxLineNumbers = Arrays.asList(
           838, 2368, 2492, 3158, 3189, 3195, 3880, 4188, 4253, 4255, 4268,
    4751, 4782, 4928, 5217, 5320, 5579, 5750, 6012, 6230, 6325, 6609,
    6615, 6618, 6642
    );*/

    //19 values - After WG, WY fix, the count got reduced to 19.
    // values resolved (5) are 838, 3158, 4188, 4253, 5320, 6325
    /*List<Integer> misMatchTaxLineNumbers = Arrays.asList(
            2368, 2492, 3189, 3195, 3880, 4255, 4268, 4751, 4782,
            4928, 5217, 5579, 5750, 6012, 6230, 6609, 6615, 6618, 6642
    );*/

    //Currently mismatched tax line numbers for WG, WY. Sent to Sarvesh
   /* List<Integer> misMatchTaxLineNumbers = Arrays.asList(
      //6325 - not a mismatch anymore after the fix for operating carrier and flight number
            // WG and WY issue LN numbers
            //3189, 5750
    );*/

    //Sandbox List to try each request individually
    List<Integer> misMatchTaxLineNumbers = Arrays.asList(
            //2368,
   //         2492,
 //           3189,
//           3195,
            //3880,
            //4255,
           // 4268,
            // 4751,
            //4782,
            //4928,
            //5217,
            //5579,
            //5750,
            //6012,
            //6230,
            //6609,
            //6615,
            //6618,
            //6642
    );




    //single line run
    //List<Integer> misMatchTaxLineNumbers = new ArrayList<>();

    public PEEnginesTaxesExpApplicationParallelAlternate() {
        queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        executor = Executors.newFixedThreadPool(THREAD_COUNT);
        latch = new CountDownLatch(THREAD_COUNT);

        taxEngineCommunicator = new TaxEngineCommunicator();
        pfcTaxEngineCommunicator = new PFCTaxEngineCommunicator();

        X1TaxRecordDataPointsLoader x1TaxRecordDataPointsLoader = new X1TaxRecordDataPointsLoader();
        log.info("Loading X1 tax record data points from redis dump file...");
        long startTime = System.currentTimeMillis();
        x1TaxRecordDataPointsMap = Collections.unmodifiableMap(
                x1TaxRecordDataPointsLoader.loadTaxRecordDataPoints("pe-engines-taxes/src/main/resources/xldatapoints_all_taxrecs_from_redis_all_20250515.txt")
        );
        log.info("DONE Loading X1 tax record data points from redis dump file. Time taken: " + (System.currentTimeMillis() - startTime) + " ms");

        //TODO: this would not be needed
        log.info("Loading PFC tax record data points from redis dump file...");
        startTime = System.currentTimeMillis();
        pfcTaxMap = Collections.unmodifiableMap(
                PFCAmountsLoader.parseAndLoadPFCTaxesRedisData("pe-engines-taxes/src/main/resources/pfcRedisDump_20250520.txt")
        );
        log.info("DONE Loading PFC tax record data points from redis dump file. Time taken: " + (System.currentTimeMillis() - startTime) + " ms");



    }

    private void startWorkerThreads() {
        // Start worker threads
        log.info("Starting " + THREAD_COUNT + " worker threads.");
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


    //TODO: Original - don't delete
    /*private void readSourceFile(File source, int specificLineNumber, String ticketDate) {
        //Read the CSV directly and put it into the queue
        try (CSVReader reader = new CSVReader(new FileReader(source))) {
            String line[];
            int lineNumber = 0;

            while ((line = reader.readNext()) != null) {
                lineNumber++;

                if (specificLineNumber != -1 && lineNumber != specificLineNumber) {
                    continue;
                }

                List<String> lineList = new ArrayList<>(Arrays.asList(line));
                lineList.add(String.valueOf(lineNumber));

                PEItinerary itinerary = PEItinerariesLoader.parsePEItineraryLine(lineList);
                //Set the ticket date on the itinerary object. Assign it to the duration field for the time being.
                itinerary.setDuration(Integer.parseInt(ticketDate));
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

    }*/


    //Send only the list of mismatched line numbers to the readSourceFile method
    private void readSourceFile(File source, String ticketDate) {
        // Read the CSV directly and put it into the queue
        try (CSVReader reader = new CSVReader(new FileReader(source))) {
            String[] line;
            int lineNumber = 0;

            while ((line = reader.readNext()) != null) {
                lineNumber++;

                // Skip lines not in the mismatch list
                if (!misMatchTaxLineNumbers.contains(lineNumber)) {
                    continue;
                }

                List<String> lineList = new ArrayList<>(Arrays.asList(line));
                lineList.add(String.valueOf(lineNumber));

                PEItinerary itinerary = PEItinerariesLoader.parsePEItineraryLine(lineList);
                // Set the ticket date on the itinerary object. Assign it to the duration field for the time being.
                itinerary.setDuration(Integer.parseInt(ticketDate));
                queue.put(itinerary);
            }

            // Signal EOF to workers
            for (int i = 0; i < THREAD_COUNT; i++) {
                queue.put(new PEItinerary());
            }
        } catch (Exception e) {
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

        if (args.length < 1) {
            throw new IllegalArgumentException("Mandatory argument 'ticketDate' is missing. Format: yyMMdd");
        }

        String ticketDate = args[0];
        if (!ticketDate.matches("\\d{6}")) {
            throw new IllegalArgumentException("Invalid 'ticketDate' format. Expected format: yyMMdd");
        }

        int lineNumber = -1; // Default value for optional argument
        if (args.length > 1) {
            try {
                lineNumber = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Optional argument 'lineNumber' must be an integer.");
            }
        }

        // Log the arguments for verification
        log.info("Ticket Date: " + ticketDate);
        log.info("Line Number: " + (lineNumber == -1 ? "Not provided" : lineNumber));

        PEEnginesTaxesExpApplicationParallelAlternate currentApp = new PEEnginesTaxesExpApplicationParallelAlternate();

        File source = new File("pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv_parallel/generated_output_txts/PEItins_parallel_unique_output.txt");

        currentApp.startWorkerThreads();
        //currentApp.readSourceFile(source, lineNumber, ticketDate);
        currentApp.readSourceFile(source, ticketDate);
        currentApp.await();

        log.info("✅ Processing complete.");

    }//End of main method.


    private void examineTaxes(RootResponse convertedResponse, PEItinerary currentItin) {
        if (convertedResponse == null) {
            log.error("Converted response is null");
            return;
        }

        BigDecimal itineraryTotal = BigDecimal.valueOf(currentItin.getTotalPrice()).setScale(2, BigDecimal.ROUND_HALF_UP);

        ArrayList<ExecutionResponse> executionResponses = convertedResponse.getExecutionResponses();
        if (executionResponses == null || executionResponses.isEmpty()) {
            log.warn("No execution responses found");
            return;
        }

        ExecutionResponse executionResponse = executionResponses.get(0);

        List<Taxes> taxes = executionResponse.getTaxes();
        if (taxes == null || taxes.isEmpty()) {
            log.warn("No taxes found in execution response");
            return;
        }

        TaxLadder taxLadder = new TaxLadder();

        processChargeDetails(taxes, taxLadder);

        if (taxLadder.isEmpty()) {
            log.warn("No valid tax details found");
            return;
        }

        BigDecimal pfcTaxes = calculatePFCTaxes(currentItin);

        if ( pfcTaxes.compareTo( BigDecimal.ZERO ) > 0) {
            taxLadder.addFlatTaxRate("XF", pfcTaxes.min(BigDecimal.valueOf(18)));
        }

        calculatePercentTax( itineraryTotal, taxLadder );

        validateCalculatedTaxes(currentItin, taxLadder );
    }


    private void processChargeDetails( List<Taxes> taxes, TaxLadder taxLadder ) {

        for (Taxes tax : taxes) {
            ArrayList<ChargeDetail> chargeDetails = tax.getChargeDetails();
            if (chargeDetails == null || chargeDetails.isEmpty()) {
                continue;
            }

            for (ChargeDetail currentChargeDetail : chargeDetails) {
                String mapKeyLookup = currentChargeDetail.getTaxKey() + "," + currentChargeDetail.getTaxSequenceNumber();
                X1TaxRecordDataPoints x1TaxRecordDataPoints = x1TaxRecordDataPointsMap.get(mapKeyLookup);

                if ( x1TaxRecordDataPoints == null ) {
                    log.error("No data found for mapKeyLookup: " + mapKeyLookup);
                    continue;
                }

                String percentOrFlatTag = x1TaxRecordDataPoints.getPercentOrFlatTag();

                String taxCode = currentChargeDetail.getTaxKey().split(",")[1];

                switch (percentOrFlatTag) {
                    case FLAT_TAX:
                        taxLadder.addFlatTaxRate(taxCode, currentChargeDetail.getResponseCharge());
                        break;

                    case PERCENT_TAX:
                        if ( (currentChargeDetail.getResponseCharge().doubleValue() == x1TaxRecordDataPoints.getTaxPercent())
                                ||
                                ( currentChargeDetail.getChargeDescription() != null &&
                                        (currentChargeDetail.getChargeDescription().contains("% of 100.00USD") ||
                                                currentChargeDetail.getChargeDescription().contains("% of 50.00USD"))
                                )
                        ) {
                            // For AS and HI based mkts, Engines use a tax rate sheet. The response charge is still treated as a percent value.
                            // Note that it differs from the X1 tax record data points (e.g., 7.5%).
                            // For example, for HI and AS mkts,  chargeDescription: "0.29% of 50.00USD" (RT per leg) or "0.29% of 100.00USD" (one way) will be showing up.
                            // This assumes fares total 100 or 50 per leg, and the responseCharge reflects the required percentage.
                            taxLadder.addPercentageTaxRate(taxCode, currentChargeDetail.getResponseCharge());
                        }
                        // Tax on Tax. so treat it as flat tax
                        else {
                            taxLadder.addFlatTaxRate(taxCode, currentChargeDetail.getResponseCharge());
                        }
                        break;

                    default:
                        throw new RuntimeException("Unexpected percentOrFlatTag: " + percentOrFlatTag);
                }
            }
        }
    }

    private void processPercentTax(ChargeDetail currentChargeDetail, String mapKeyLookup, Map<String, List<BigDecimal>> flatOrPercentValuesMap) {
        if (currentChargeDetail.getChargeDescription() != null && currentChargeDetail.getChargeDescription().contains("% of 100.00USD")) {
            flatOrPercentValuesMap.computeIfAbsent(PERCENT_TAX, k -> new ArrayList<>())
                    .add(BigDecimal.valueOf(x1TaxRecordDataPointsMap.get(mapKeyLookup).getTaxPercent()));
        } else {
            flatOrPercentValuesMap.computeIfAbsent(TAX_ON_TAX, k -> new ArrayList<>())
                    .add(currentChargeDetail.getResponseCharge().setScale(2, BigDecimal.ROUND_HALF_UP));
        }
    }

    private BigDecimal calculateFlatAndTaxOnTax(Map<String, List<BigDecimal>> flatOrPercentValuesMap, BigDecimal itinTpDeductedWithTaxes) {
        BigDecimal totalFlatTaxAmount = flatOrPercentValuesMap.getOrDefault(FLAT_TAX, Collections.emptyList())
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalTaxOnTaxAmount = flatOrPercentValuesMap.getOrDefault(TAX_ON_TAX, Collections.emptyList())
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        // Calculate the total flat tax amount and tax on tax amount
        return totalFlatTaxAmount.add(totalTaxOnTaxAmount);
    }

    private BigDecimal calculatePFCTaxes(PEItinerary currentItin) {

        AtomicReference<BigDecimal> pfcTaxes = new AtomicReference<>(BigDecimal.ZERO);

        RootPFCResponse rootPFCResponse = pfcTaxEngineCommunicator.sendRequest(currentItin);

        if (rootPFCResponse != null && rootPFCResponse.getPfcResponse() != null
                && rootPFCResponse.getPfcResponse().getCharges() != null
                && !rootPFCResponse.getPfcResponse().getCharges().isEmpty()) {
            // retrieve PFC taxes from the response and add them to the total
            List<Charge> pfcCharges = rootPFCResponse.getPfcResponse().getCharges();
            pfcCharges.forEach(airportPfcCharge -> {
                BigDecimal taxAmount = BigDecimal.valueOf(airportPfcCharge.getCharge());
                pfcTaxes.set(pfcTaxes.get().add(taxAmount));
            });
        }
        else {
            log.warn("Null or Empty PFC Response for itinerary: PFC-" + logRoute(currentItin));
        }
        return pfcTaxes.get();
    }


    private void calculatePercentTax( BigDecimal itineraryTotal, TaxLadder taxLadder ) {

        BigDecimal netOfFlatTax = itineraryTotal.subtract( taxLadder.getTotalFlatTaxRate() );

        for ( String taxCode : taxLadder.getPercentageTaxCodes()) {
            BigDecimal percentTaxRate = taxLadder.getPercentageTaxRate(taxCode);

            BigDecimal denominator = BigDecimal.ONE.add(percentTaxRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            BigDecimal fraction    = netOfFlatTax.divide(denominator, 4, RoundingMode.HALF_UP);
            BigDecimal taxValue    = netOfFlatTax.subtract(fraction);

            taxLadder.setPercentageTaxRate( taxCode, taxValue );
        }
    }

    private void validateCalculatedTaxes(PEItinerary currentItin, TaxLadder taxLadder) {

        BigDecimal itineraryTaxes = BigDecimal.valueOf( currentItin.getTaxes() );
        BigDecimal totalTax = taxLadder.getTotalFlatTaxRate().add( taxLadder.getTotalPercentageTaxRate() );

        BigDecimal taxDifference = itineraryTaxes.subtract(totalTax).abs();

        boolean didCalculatedTaxesMatch = taxDifference.doubleValue() <= 1.0;

        if (!didCalculatedTaxesMatch) {
            log.info(logRoute( currentItin ));
            log.info("Expected: " + itineraryTaxes + " Actual: " + totalTax + " Diff: " + taxDifference );

            compareTaxLadders(currentItin, taxLadder);
        }
    }

    private void compareTaxLadders(PEItinerary currentItin, TaxLadder taxLadder ) {
        if (currentItin.getTaxLadder() == null || currentItin.getTaxLadder().isEmpty()) {
            log.error("currentItin.getTaxLadder() is null or empty. Cannot compare!");
            return;
        }

        Map<String, String> currentItinTaxLadderMap = new TreeMap<>();
        for (String taxCodeText : currentItin.getTaxLadder()) {
            String[] parts = taxCodeText.split(" ");
            if (parts.length == 2) {
                currentItinTaxLadderMap.put(parts[0], parts[1]);
            }
        }
        //remove YQ and YR entries from the map.
        currentItinTaxLadderMap.remove("YQ");
        currentItinTaxLadderMap.remove("YR");


        Set<String> taxKeys = new TreeSet<>(currentItinTaxLadderMap.keySet());
        taxKeys.addAll(taxLadder.getFlatTaxCodes());
        taxKeys.addAll(taxLadder.getPercentageTaxCodes());

        boolean mismatchFound = false;
        for (String taxKey : taxKeys) {
            String expectedValue = currentItinTaxLadderMap.get(taxKey);
            BigDecimal responseValue = taxLadder.getTaxRate(taxKey);

            if ( expectedValue == null || responseValue == null || Double.parseDouble(expectedValue) != responseValue.doubleValue()) {
                String diff = "";
                if (expectedValue != null && responseValue != null) {
                    diff = String.format("%.2f", Double.parseDouble( expectedValue ) - responseValue.doubleValue());
                }
                log.error(String.format("%s: Expected: %6s Actual: %6s Diff: %6s", taxKey, expectedValue == null ? "------" : expectedValue, responseValue == null ? "------" : responseValue, diff));
                mismatchFound = true;
            }
        }

        if (mismatchFound) {
            log.info("\n");
        }

    }


    private String logRoute( PEItinerary currentItin ) {
        StringBuilder route = new StringBuilder();

        for (RawLeg leg : currentItin.getOutboundLegs()) {
            if (route.length() > 0) {
                route.append("-");
            }
            route.append(leg.getOriginAirportCode())
                    .append("(")
                        .append(leg.getMarketingCarrier())
                        .append(leg.getFlightNumber())
                        .append(" ")
                        .append(leg.getDepartDate()).append(" ").append(leg.getDepartTime()).append(":")
                        .append(leg.getArriveDate()).append(" ").append(leg.getArriveTime())
                    .append(")")
                    .append(leg.getDestinationAirportCode());
        }

        if (currentItin.getInboundLegs() != null && !currentItin.getInboundLegs().isEmpty()) {
            route.append(" / ");
            int len = route.length();
            for (RawLeg leg : currentItin.getInboundLegs()) {
                if (route.length() > len) {
                    route.append("-");
                }
                route.append(leg.getOriginAirportCode())
                        .append("(")
                        .append(leg.getMarketingCarrier())
                        .append(leg.getFlightNumber())
                        .append(" ")
                        .append(leg.getDepartDate()).append(" ").append(leg.getDepartTime()).append(":")
                        .append(leg.getArriveDate()).append(" ").append(leg.getArriveTime())
                        .append(")")
                        .append(leg.getDestinationAirportCode());
            }
        }

        return ("Route: " + route + " $" + currentItin.getTotalPrice() +  " LN: " + currentItin.getChannel());
    }


    /**
     * Extracts the tax ladder from a list of Taxes objects and returns it as a sorted map.
     * @param taxes
     * @return Map<String, String>
     */
    public Map<String, String> extractTaxLadderAsMap(List<Taxes> taxes, BigDecimal pfcTaxes) {
        Map<String, BigDecimal> taxLadderMap = new HashMap<>();

        for (Taxes tax : taxes) {
            // Get the first two characters of the tax group
            String taxGroupPrefix = tax.getTaxGroup().substring(0, 2);

            // Use the tax amount as-is without rounding
            BigDecimal taxAmount = tax.getTaxAmount();

            // Add the tax amount to the map, summing up if the key already exists
            taxLadderMap.merge(taxGroupPrefix, taxAmount, BigDecimal::add);
        }

        if (pfcTaxes.compareTo(BigDecimal.ZERO) > 0) {
            taxLadderMap.put("XF", pfcTaxes);
        }

        // Convert the map values to formatted strings with two decimal places
        Map<String, String> formattedTaxLadderMap = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> entry : taxLadderMap.entrySet()) {
            //Leave the scale to 2 decimal places NO ROUNDING.
            formattedTaxLadderMap.put(entry.getKey(), entry.getValue().setScale(2).toString());
        }

        return formattedTaxLadderMap;
    }

}

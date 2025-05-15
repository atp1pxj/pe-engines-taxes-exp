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
        x1TaxRecordDataPointsMap = Collections.unmodifiableMap(
                x1TaxRecordDataPointsLoader.loadTaxRecordDataPoints("pe-engines-taxes/src/main/resources/xldatapoints_all_taxrecs_from_redis_all_20250515.txt")
        );

        //Load the airport country code map
        MetadataReader metadataReader = new MetadataReader();

        //Loads airports from US, Puerto Rico (PR) and Virgin Islands (VI) only.
        airportCountryCodeMap = Collections.unmodifiableMap(
                metadataReader.getAirportCountryMapUSDomesticOnly()
        );
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

        log.info("✅ Processing complete.");

    }//End of main method.


    private void examineTaxes(RootResponse convertedResponse, PEItinerary currentItin) {
        if (convertedResponse == null) {
            log.error("Converted response is null");
            return;
        }

        BigDecimal itinTpOriginal = BigDecimal.valueOf(currentItin.getTotalPrice()).setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal currentItinTaxes = BigDecimal.valueOf(currentItin.getTaxes()).setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal itinTpDeductedWithTaxes = itinTpOriginal;

        ArrayList<ExecutionResponse> executionResponses = convertedResponse.getExecutionResponses();
        if (executionResponses == null || executionResponses.isEmpty()) {
            log.warn("No execution responses found");
            return;
        }

        for (ExecutionResponse executionResponse : executionResponses) {
            ArrayList<Taxes> taxes = executionResponse.getTaxes();
            if (taxes == null || taxes.isEmpty()) {
                log.warn("No taxes found in execution response");
                continue;
            }

            //populate the flatOrPercentValuesMap with the chargeDetails from each tax
            Map<String, List<BigDecimal>> flatOrPercentValuesMap = processChargeDetails(taxes);

            if (flatOrPercentValuesMap.isEmpty()) {
                log.warn("No valid tax details found");
                continue;
            }

            BigDecimal totalFlatTaxAmount = calculateFlatAndTaxOnTax(flatOrPercentValuesMap, itinTpDeductedWithTaxes);
            BigDecimal itinTpDeductedWithFlatTaxes = itinTpDeductedWithTaxes.subtract(totalFlatTaxAmount);

            BigDecimal pfcTaxes = calculatePFCTaxes(currentItin);
            BigDecimal itinTpDeductedWithFlatTaxesAndPFC = itinTpDeductedWithFlatTaxes.subtract(pfcTaxes.min(BigDecimal.valueOf(18)));

            BigDecimal percentTaxTotalAmount = calculatePercentTax(flatOrPercentValuesMap, itinTpDeductedWithFlatTaxesAndPFC);

            //log.info("Final Basefare after deducting all taxes: " + itinTpDeductedWithFlatTaxesAndPFC.subtract(percentTaxTotalAmount));
            BigDecimal calculatedTaxesTotal = percentTaxTotalAmount.add(totalFlatTaxAmount).add(pfcTaxes);
            validateCalculatedTaxes(currentItin, currentItinTaxes, calculatedTaxesTotal, taxes, pfcTaxes);
        }
    }

    private Map<String, List<BigDecimal>> processChargeDetails(ArrayList<Taxes> taxes) {
        Map<String, List<BigDecimal>> flatOrPercentValuesMap = new HashMap<>();

        for (Taxes tax : taxes) {
            ArrayList<ChargeDetail> chargeDetails = tax.getChargeDetails();
            if (chargeDetails == null || chargeDetails.isEmpty()) {
                continue;
            }

            for (ChargeDetail currentChargeDetail : chargeDetails) {
                String mapKeyLookup = currentChargeDetail.getTaxKey() + "," + currentChargeDetail.getTaxSequenceNumber();
                String percentOrFlatTag = Optional.ofNullable(x1TaxRecordDataPointsMap.get(mapKeyLookup))
                        .map(X1TaxRecordDataPoints::getPercentOrFlatTag)
                        .orElse(null);

                if (percentOrFlatTag == null) {
                    log.error("No data found for mapKeyLookup: " + mapKeyLookup);
                    continue;
                }

                if ("Flat Tax".equals(percentOrFlatTag)) {
                    flatOrPercentValuesMap.computeIfAbsent(FLAT_TAX, k -> new ArrayList<>())
                            .add(currentChargeDetail.getResponseCharge().setScale(2, BigDecimal.ROUND_HALF_UP));
                } else if ("Percent Tax".equals(percentOrFlatTag)) {
                    processPercentTax(currentChargeDetail, mapKeyLookup, flatOrPercentValuesMap);
                }
            }
        }

        return flatOrPercentValuesMap;
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

        currentItin.getOutboundLegs().stream()
                .filter(leg -> airportCountryCodeMap.containsKey(leg.getOriginAirportCode()))
                .forEach(leg -> {
                    BigDecimal taxAmount = "ANC".equals(leg.getOriginAirportCode()) ? BigDecimal.valueOf(3.00) : BigDecimal.valueOf(4.50);
                    pfcTaxes.set(pfcTaxes.get().add(taxAmount));
                });

        currentItin.getInboundLegs().stream()
                .filter(leg -> airportCountryCodeMap.containsKey(leg.getOriginAirportCode()))
                .forEach(leg -> {
                    BigDecimal taxAmount = "ANC".equals(leg.getOriginAirportCode()) ? BigDecimal.valueOf(3.00) : BigDecimal.valueOf(4.50);
                    pfcTaxes.set(pfcTaxes.get().add(taxAmount));
                });

        return pfcTaxes.get();
    }

    private BigDecimal calculatePercentTax(Map<String, List<BigDecimal>> flatOrPercentValuesMap, BigDecimal itinTpDeductedWithFlatTaxesAndPFC) {
        BigDecimal percentTaxTotalAmount = BigDecimal.ZERO;
        BigDecimal percentTaxTotal = flatOrPercentValuesMap.getOrDefault(PERCENT_TAX, Collections.emptyList())
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal denominator = BigDecimal.ONE.add(percentTaxTotal.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal fraction = itinTpDeductedWithFlatTaxesAndPFC.divide(denominator, 4, RoundingMode.HALF_UP);
        BigDecimal difference = itinTpDeductedWithFlatTaxesAndPFC.subtract(fraction);

        // Round final result to 2 decimal places
        percentTaxTotalAmount = difference.setScale(2, RoundingMode.HALF_UP);
        return percentTaxTotalAmount;

    }

    private void validateCalculatedTaxes(PEItinerary currentItin, BigDecimal currentItinTaxes, BigDecimal calculatedTaxesTotal, ArrayList<Taxes> taxes, BigDecimal pfcTaxes) {
        boolean didCalculatedTaxesMatch = currentItinTaxes.subtract(calculatedTaxesTotal)
                .compareTo(BigDecimal.valueOf(-1.00)) >= 0
                && currentItinTaxes.subtract(calculatedTaxesTotal)
                .compareTo(BigDecimal.valueOf(1.00)) <= 0;

        if (!didCalculatedTaxesMatch) {
            logRoute( currentItin );
            log.info("Expected: " + currentItinTaxes + " Actual: " + calculatedTaxesTotal + " Diff: " + currentItinTaxes.subtract(calculatedTaxesTotal));

            Map<String, String> sortedTaxLadderFromResponse = extractTaxLadderAsMap(taxes, pfcTaxes);
            compareTaxLadders(currentItin, sortedTaxLadderFromResponse);
        }
    }

    private void compareTaxLadders(PEItinerary currentItin, Map<String, String> sortedTaxLadderFromResponse) {
        if (currentItin.getTaxLadder() == null || currentItin.getTaxLadder().isEmpty()) {
            log.error("currentItin.getTaxLadder() is null or empty. Cannot compare!");
            return;
        }

        Map<String, String> currentItinTaxLadderMap = new TreeMap<>();
        for (String taxLadder : currentItin.getTaxLadder()) {
            String[] parts = taxLadder.split(" ");
            if (parts.length == 2) {
                currentItinTaxLadderMap.put(parts[0], parts[1]);
            }
        }
        //remove YQ and YR entries from the map.
        currentItinTaxLadderMap.remove("YQ");
        currentItinTaxLadderMap.remove("YR");


        Set<String> taxKeys = new TreeSet<>(currentItinTaxLadderMap.keySet());
        taxKeys.addAll(sortedTaxLadderFromResponse.keySet());

        boolean mismatchFound = false;
        for (String taxKey : taxKeys) {
            String expectedValue = currentItinTaxLadderMap.get(taxKey);
            String responseValue = sortedTaxLadderFromResponse.get(taxKey);

            if ( expectedValue == null || !expectedValue.equals(responseValue)) {
                String diff = "";
                if (expectedValue != null && responseValue != null) {
                    diff = String.format("%.2f", Double.parseDouble( expectedValue ) - Double.parseDouble( responseValue ));
                }
                log.error(String.format("%s: Expected: %6s Actual: %6s Diff: %6s", taxKey, expectedValue == null ? "------" : expectedValue, responseValue == null ? "------" : responseValue, diff));
                mismatchFound = true;
            }
        }

        if (mismatchFound) {
            log.info("\n");
        }

    }


    private void logRoute( PEItinerary currentItin ) {
        StringBuilder route = new StringBuilder();

        for (RawLeg leg : currentItin.getOutboundLegs()) {
            if (route.length() > 0) {
                route.append("-");
            }
            route.append(leg.getOriginAirportCode()).append("(").append(leg.getMarketingCarrier()).append(leg.getFlightNumber()).append(")").append(leg.getDestinationAirportCode());
        }

        if (currentItin.getInboundLegs() != null && !currentItin.getInboundLegs().isEmpty()) {
            route.append(" / ");
            int len = route.length();
            for (RawLeg leg : currentItin.getInboundLegs()) {
                if (route.length() > len) {
                    route.append("-");
                }
                route.append(leg.getOriginAirportCode()).append("(").append(leg.getMarketingCarrier()).append(leg.getFlightNumber()).append(")").append(leg.getDestinationAirportCode());
            }
        }

        log.info("Route: " + route + " $" + currentItin.getTotalPrice() +  " LN: " + currentItin.getChannel());

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

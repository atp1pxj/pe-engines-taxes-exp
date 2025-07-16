package com.threevictors.aws.priceeye.taxes.processor;

import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.taxes.data.TaxLadder;
import com.threevictors.aws.priceeye.taxes.model.pfcengine.response.Charge;
import com.threevictors.aws.priceeye.taxes.model.pfcengine.response.RootPFCResponse;
import com.threevictors.aws.priceeye.taxes.model.taxengine.response.*;
import com.threevictors.aws.priceeye.taxes.taxengine.PFCTaxEngineCommunicator;
import com.threevictors.aws.priceeye.taxes.taxengine.SharedHttpFactory;
import com.threevictors.aws.priceeye.taxes.taxengine.TaxEngineCommunicator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class handles the processing of PEItinerary objects, including tax calculations and validation.
 * It was extracted from PEEnginesTaxesExpApplicationParallelAlternate to separate concerns.
 */
public class PEItineraryTaxProcessor {

    private static final Logger log = LogManager.getLogger(PEItineraryTaxProcessor.class);

    private static final String FLAT_TAX = "Flat Tax";
    private static final String PERCENT_TAX = "Percent Tax";
    private static final String TAX_ON_TAX = "tax on tax";
    private static final String QUERY_ID_PREFIX_3V = "3v-";


    private final Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap;
    private final TaxEngineCommunicator taxEngineCommunicator;
    private final PFCTaxEngineCommunicator pfcTaxEngineCommunicator;

    public PEItineraryTaxProcessor( Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap ) {
        this.x1TaxRecordDataPointsMap = x1TaxRecordDataPointsMap;
        this.taxEngineCommunicator = new TaxEngineCommunicator();
        this.pfcTaxEngineCommunicator = new PFCTaxEngineCommunicator();
    }

    /**
     * Process an itinerary by sending it to the tax engine and examining the taxes
     *
     * @param currentItin The itinerary to process
     * @param salesDate
     * @return The TaxLadder tax engine
     */
    public CompletableFuture<TaxLadder> processPEItinerary( String pointOfSale, PEItinerary currentItin, int salesDate ) {
        String queryId = QUERY_ID_PREFIX_3V + UUID.randomUUID();

        // Record start time for SFE engine call
        long sfeStartTime = System.currentTimeMillis();

        CompletableFuture<RootResponse> rootResponse = taxEngineCommunicator.sendRequest( pointOfSale, currentItin, queryId, salesDate );

        // Measure SFE call time and update inBrandsEnriched when the response is received
        rootResponse = rootResponse.thenApply(response -> {
            long sfeEndTime = System.currentTimeMillis();
            long sfeDuration = sfeEndTime - sfeStartTime;

            // Update inBrandsEnriched with SFE call time in a thread-safe manner
            synchronized (currentItin) {
                currentItin.setInBrandsEnriched("sfe: " + sfeDuration + "ms");
            }

            return response;
        });

        CompletableFuture<BigDecimal> pfcResponse = calculatePFCTaxes(pointOfSale, currentItin, queryId, salesDate);

        // Combine both futures without blocking
        return rootResponse.thenCombineAsync(pfcResponse, 
        (response, pfcTaxes) -> examineTaxes(response, pointOfSale, currentItin, queryId, salesDate, pfcTaxes), 
        SharedHttpFactory.getInstance().getExecutorService());
    }

    private TaxLadder examineTaxes(RootResponse convertedResponse, String pointOfSale, PEItinerary currentItin, String queryId, int salesDate, BigDecimal pfcTaxes) {
        if (convertedResponse == null) {
            log.error("Converted response is null");
            return null;
        }

        BigDecimal itineraryTotal = BigDecimal.valueOf(currentItin.getTotalPrice()).setScale(2, RoundingMode.HALF_UP);

        ArrayList<ExecutionResponse> executionResponses = convertedResponse.getExecutionResponses();
        if (executionResponses == null || executionResponses.isEmpty()) {
            log.warn("No execution responses found");
            return null;
        }

        ExecutionResponse executionResponse = executionResponses.get(0);

        List<Taxes> taxes = executionResponse.getTaxes();
        if (taxes == null || taxes.isEmpty()) {
            log.warn("No taxes found in execution response");
            return null;
        }

        TaxLadder taxLadder = new TaxLadder();

        processChargeDetails(taxes, taxLadder);

        if (taxLadder.isEmpty()) {
            log.warn("No valid tax details found");
            return null;
        }

        // Use the already resolved pfcTaxes instead of blocking
        if (pfcTaxes.compareTo(BigDecimal.ZERO) > 0) {
            taxLadder.addFlatTaxRate("XF", pfcTaxes.min(BigDecimal.valueOf(18)));
        }

        calculatePercentTax(itineraryTotal, taxLadder);

        return taxLadder;
    }

    private void processChargeDetails(List<Taxes> taxes, TaxLadder taxLadder) {
        for (Taxes tax : taxes) {
            ArrayList<ChargeDetail> chargeDetails = tax.getChargeDetails();
            if (chargeDetails == null || chargeDetails.isEmpty()) {
                continue;
            }

            for (ChargeDetail currentChargeDetail : chargeDetails) {
                String mapKeyLookup = currentChargeDetail.getTaxKey() + "," + currentChargeDetail.getTaxSequenceNumber();
                X1TaxRecordDataPoints x1TaxRecordDataPoints = x1TaxRecordDataPointsMap.get(mapKeyLookup);

                if (x1TaxRecordDataPoints == null) {
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
                        if ((currentChargeDetail.getResponseCharge().doubleValue() == x1TaxRecordDataPoints.getTaxPercent())
                                ||
                                (currentChargeDetail.getChargeDescription() != null &&
                                        (currentChargeDetail.getChargeDescription().contains("% of 100.00USD") ||
                                                currentChargeDetail.getChargeDescription().contains("% of 50.00USD"))
                                )
                        ) {
                            // Use the mapKeyLookup as the key to store the percentage tax rate. This is to aid with the maxTaxWhenPercent lookup later.
                            taxLadder.getPercentageTaxKeyMap().put(mapKeyLookup, currentChargeDetail.getResponseCharge());
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

    private CompletableFuture<BigDecimal> calculatePFCTaxes(String pointOfSale, PEItinerary currentItin, String queryId, int salesDate) {
        AtomicReference<BigDecimal> pfcTaxes = new AtomicReference<>(BigDecimal.ZERO);

        //create two oneway itineraries from currentItin - one for outbound and one for inbound.
        PEItinerary obOwItin = PEItinerary.copy(currentItin);
        //Empty inbound legs for outbound itinerary
        obOwItin.setInboundLegs(List.of());

        //Adding the fareConstructionText field to continue the capture of HTTP request. This is not feasible as the value will get reflected on the original itin
        //obOwItin.setFareConstructionText(currentItin.getFareConstructionText());

        // Record start time for first PFC engine call
        long pfcCall1StartTime = System.currentTimeMillis();

        //Passing the original PEItinerary currentItin to capture the applicable PFC HTTP request body
        CompletableFuture<RootPFCResponse> rootPFCResponse = pfcTaxEngineCommunicator.sendRequest(pointOfSale, obOwItin, queryId, salesDate, currentItin);

        CompletableFuture<Void> processResult = rootPFCResponse.thenAcceptAsync(response -> {
                    // Measure PFC call 1 time and update inBrandsEnriched
                    long pfcCall1EndTime = System.currentTimeMillis();
                    long pfcCall1Duration = pfcCall1EndTime - pfcCall1StartTime;

                    // Append PFC call 1 time to inBrandsEnriched in a thread-safe manner
                    synchronized (currentItin) {
                        String currentValue = currentItin.getInBrandsEnriched();
                        if (currentValue == null || currentValue.isEmpty()) {
                            currentItin.setInBrandsEnriched("pfc-call-1: " + pfcCall1Duration + "ms");
                        } else {
                            currentItin.setInBrandsEnriched(currentValue + "; pfc-call-1: " + pfcCall1Duration + "ms");
                        }
                    }

                    processPFC(response, pfcTaxes);
                }
            , SharedHttpFactory.getInstance().getExecutorService());


        // Only create and add inbound one-way itinerary if inbound legs exist
        if (currentItin.getInboundLegs() != null && !currentItin.getInboundLegs().isEmpty()) {
            PEItinerary ibOwItin = PEItinerary.copy(currentItin);
            //set inbound legs as outbound for inbound OW PFC itinerary
            ibOwItin.setOutboundLegs(currentItin.getInboundLegs());
            ibOwItin.setInboundLegs(List.of());

            processResult = processResult.thenComposeAsync(x -> {
                // Record start time for second PFC engine call
                long pfcCall2StartTime = System.currentTimeMillis();

                return pfcTaxEngineCommunicator.sendRequest(pointOfSale, ibOwItin, queryId, salesDate, currentItin)
                    .thenAcceptAsync(response -> {
                        // Measure PFC call 2 time and update inBrandsEnriched
                        long pfcCall2EndTime = System.currentTimeMillis();
                        long pfcCall2Duration = pfcCall2EndTime - pfcCall2StartTime;

                        // Append PFC call 2 time to inBrandsEnriched in a thread-safe manner
                        synchronized (currentItin) {
                            String currentValue = currentItin.getInBrandsEnriched();
                            if (currentValue == null || currentValue.isEmpty()) {
                                currentItin.setInBrandsEnriched("pfc-call-2: " + pfcCall2Duration + "ms");
                            } else {
                                currentItin.setInBrandsEnriched(currentValue + "; pfc-call-2: " + pfcCall2Duration + "ms");
                            }
                        }

                        processPFC(response, pfcTaxes);
                    }, SharedHttpFactory.getInstance().getExecutorService());
            });
        }

        return processResult.thenApply(v -> pfcTaxes.get());
    }

    private void processPFC( RootPFCResponse response, AtomicReference<BigDecimal> pfcTaxes ) {
        if (response != null && response.getPfcResponse() != null
                && response.getPfcResponse().getCharges() != null
                && !response.getPfcResponse().getCharges().isEmpty()) {
            // retrieve PFC taxes from the response and add them to the total
            List<Charge> pfcCharges = response.getPfcResponse().getCharges();

            pfcCharges.forEach(airportPfcCharge -> {
                BigDecimal taxAmount = BigDecimal.valueOf(airportPfcCharge.getCharge());
                pfcTaxes.set(pfcTaxes.get().add(taxAmount));
            });
        }
    }

    private void calculatePercentTax(BigDecimal itineraryTotal, TaxLadder taxLadder) {
        BigDecimal netOfFlatTax = itineraryTotal.subtract(taxLadder.getTotalFlatTaxRate());

        for (String taxMapKey : taxLadder.getPercentageTaxKeyMap().keySet()) {
            BigDecimal percentTaxRate = taxLadder.getPercentageTaxKeyMap().get(taxMapKey);

            BigDecimal denominator = BigDecimal.ONE.add(percentTaxRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            BigDecimal fraction = netOfFlatTax.divide(denominator, 4, RoundingMode.HALF_UP);
            BigDecimal taxValue = netOfFlatTax.subtract(fraction);

            //Look up the maxTaxPercent value from the x1TaxRecordDataPointsMap
            // and select the minimum of the two values. Use the minimum value only when maxTaxWhenPercent is greater than 0.
            BigDecimal maxTaxWhenPercent = BigDecimal.valueOf(x1TaxRecordDataPointsMap.get(taxMapKey).getMaxTaxWhenPercent());
            if (maxTaxWhenPercent.compareTo(BigDecimal.ZERO) > 0) {
                taxValue = taxValue.min(maxTaxWhenPercent);
            }

            String taxCode = taxMapKey.split(",")[1];
            //Add up all the percentage tax values corresponding to the taxCode.
            taxLadder.addPercentageTaxRate(taxCode, taxValue);
        }
    }


}

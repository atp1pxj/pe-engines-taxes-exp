package com.threevictors.aws.priceeye.exp;

import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.data.TaxLadder;
import com.threevictors.aws.priceeye.exp.model.pfcengine.response.Charge;
import com.threevictors.aws.priceeye.exp.model.pfcengine.response.RootPFCResponse;
import com.threevictors.aws.priceeye.exp.model.taxengine.response.*;
import com.threevictors.aws.priceeye.exp.taxengine.PFCTaxEngineCommunicator;
import com.threevictors.aws.priceeye.exp.taxengine.TaxEngineCommunicator;

import com.threevictors.common.aws.s3.S3Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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

    private final Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap;
    private final TaxEngineCommunicator taxEngineCommunicator;
    private final PFCTaxEngineCommunicator pfcTaxEngineCommunicator;
    private S3Util s3Util;

    public PEItineraryTaxProcessor(
            Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap,
            TaxEngineCommunicator taxEngineCommunicator,
            PFCTaxEngineCommunicator pfcTaxEngineCommunicator) {
        this.x1TaxRecordDataPointsMap = x1TaxRecordDataPointsMap;
        this.taxEngineCommunicator = taxEngineCommunicator;
        this.pfcTaxEngineCommunicator = pfcTaxEngineCommunicator;
        //Added s3Util
        this.s3Util = new S3Util();
    }

    /**
     * Process an itinerary by sending it to the tax engine and examining the taxes
     *
     * @param currentItin The itinerary to process
     * @param queryId     The query ID to use for the request
     * @param salesDate
     * @return The root response from the tax engine
     */
    public RootResponse processPEItinerary(PEItinerary currentItin, String queryId, int salesDate) {
        RootResponse rootResponse = taxEngineCommunicator.sendRequest(currentItin, queryId, salesDate);

        if (rootResponse != null) {
            //Temp added for debugging threading issue.
            //log.info("RootResponse: " + rootResponse.toString().substring(0, 100) + " queryId:  " + queryId);
            examineTaxes(rootResponse, currentItin, queryId, salesDate);
            log.info("Completed examineTaxes call for queryId: " + queryId);
        } else {
            log.error("Null response for itinerary: " + currentItin);
        }

        return rootResponse;
    }

    public void examineTaxes(RootResponse convertedResponse, PEItinerary currentItin, String queryId, int salesDate) {
        if (convertedResponse == null) {
            log.error("Converted response is null");
            return;
        }

        BigDecimal itineraryTotal = BigDecimal.valueOf(currentItin.getTotalPrice()).setScale(2, RoundingMode.HALF_UP);

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

        BigDecimal pfcTaxes = calculatePFCTaxes(currentItin, queryId, salesDate);

        if (pfcTaxes.compareTo(BigDecimal.ZERO) > 0) {
            taxLadder.addFlatTaxRate("XF", pfcTaxes.min(BigDecimal.valueOf(18)));
        }

        calculatePercentTax(itineraryTotal, taxLadder);

        validateCalculatedTaxes(currentItin, taxLadder);
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

    private BigDecimal calculatePFCTaxes(PEItinerary currentItin, String queryId, int salesDate) {
        AtomicReference<BigDecimal> pfcTaxes = new AtomicReference<>(BigDecimal.ZERO);

        List<PEItinerary> pfcOWItineraries = new ArrayList<>();

        //create two oneway itineraries from currentItin - one for outbound and one for inbound.
        PEItinerary obOwItin = PEItinerary.copy(currentItin);
        //Empty inbound legs for outbound itinerary
        obOwItin.setInboundLegs(List.of());
        pfcOWItineraries.add(obOwItin);

        // Only create and add inbound one-way itinerary if inbound legs exist
        if (currentItin.getInboundLegs() != null && !currentItin.getInboundLegs().isEmpty()) {
            PEItinerary ibOwItin = PEItinerary.copy(currentItin);
            //set inbound legs as outbound for inbound OW PFC itinerary
            ibOwItin.setOutboundLegs(currentItin.getInboundLegs());
            ibOwItin.setInboundLegs(List.of());
            pfcOWItineraries.add(ibOwItin);
        }

        for (PEItinerary owItin : pfcOWItineraries) {
            RootPFCResponse rootPFCResponse = pfcTaxEngineCommunicator.sendRequest(owItin, queryId, salesDate);

            if (rootPFCResponse != null && rootPFCResponse.getPfcResponse() != null
                    && rootPFCResponse.getPfcResponse().getCharges() != null
                    && !rootPFCResponse.getPfcResponse().getCharges().isEmpty()) {
                // retrieve PFC taxes from the response and add them to the total
                List<Charge> pfcCharges = rootPFCResponse.getPfcResponse().getCharges();
                pfcCharges.forEach(airportPfcCharge -> {
                    BigDecimal taxAmount = BigDecimal.valueOf(airportPfcCharge.getCharge());
                    pfcTaxes.set(pfcTaxes.get().add(taxAmount));
                });
            } else {
                //Temp'ly commented out the log statement to avoid cluttering the logs with empty PFC responses due to the split.
                //log.warn("Null or Empty PFC Response for itinerary: PFC-" + logRoute(owItin));
            }
        }

        return pfcTaxes.get();
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

    private void validateCalculatedTaxes(PEItinerary currentItin, TaxLadder taxLadder) {
        BigDecimal itineraryTaxes = BigDecimal.valueOf(currentItin.getTaxes());
        BigDecimal totalTax = taxLadder.getTotalFlatTaxRate().add(taxLadder.getTotalPercentageTaxRate());

        BigDecimal taxDifference = itineraryTaxes.subtract(totalTax).abs();

        boolean didCalculatedTaxesMatch = taxDifference.doubleValue() <= 1.0;

        if (!didCalculatedTaxesMatch) {
            log.info(logRoute(currentItin));
            //Call method to write to the csv file here
            writeMismatchedItinDataToCSV(currentItin, taxLadder);
            log.info("Expected: " + itineraryTaxes + " Actual: " + totalTax + " Diff: " + taxDifference + " LN: " + currentItin.getChannel());
            compareTaxLadders(currentItin, taxLadder);
        }
    }

    private void compareTaxLadders(PEItinerary currentItin, TaxLadder taxLadder) {
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

            if (expectedValue == null || responseValue == null || Double.parseDouble(expectedValue) != responseValue.doubleValue()) {
                String diff = "";
                if (expectedValue != null && responseValue != null) {
                    diff = String.format("%.2f", Double.parseDouble(expectedValue) - responseValue.doubleValue());
                }
                log.error(String.format("%s: Expected: %6s Actual: %6s Diff: %6s LN: %s", taxKey, expectedValue == null ? "------" : expectedValue, responseValue == null ? "------" : responseValue, diff, currentItin.getChannel()));
                mismatchFound = true;
            }
        }

        if (mismatchFound) {
            log.info("\n");
        }
    }

    private String logRoute(PEItinerary currentItin) {
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

        return ("Route: " + route + " $" + currentItin.getTotalPrice() + " LN: " + currentItin.getChannel());
    }


    // Use a static object for synchronization
    private static final Object CSV_LOCK = new Object();

    private String writeMismatchedItinDataToCSV(PEItinerary currentItin, TaxLadder taxLadder) {
        // Create a StringBuilder to build the CSV line
        StringBuilder csvLine = new StringBuilder();

        // Build the route information in CSV format
        StringBuilder route = new StringBuilder();

        // Process outbound legs
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

        // Process inbound legs if they exist
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

        // Add route information to CSV line
        csvLine.append("\"").append(route).append("\",");

        // Add total price and channel
        csvLine.append(currentItin.getTotalPrice()).append(",");
        csvLine.append(currentItin.getChannel()).append(",");

        // Add tax ladder values
        csvLine.append("\"USD:");

        // Get flat tax codes
        Set<String> flatTaxCodes = taxLadder.getFlatTaxCodes();
        // Get percentage tax codes
        Set<String> percentageTaxCodes = taxLadder.getPercentageTaxCodes();

        // Combine all tax codes
        Set<String> allTaxCodes = new TreeSet<>();
        allTaxCodes.addAll(flatTaxCodes);
        allTaxCodes.addAll(percentageTaxCodes);

        // Build the tax ladder string
        boolean first = true;
        for (String taxCode : allTaxCodes) {
            BigDecimal taxRate = taxLadder.getTaxRate(taxCode);
            if (taxRate != null) {
                if (!first) {
                    csvLine.append("|");
                }
                csvLine.append(taxCode).append(" ").append(taxRate.setScale(2, RoundingMode.HALF_UP));
                first = false;
            }
        }
        csvLine.append("\",");

        // Add current timestamp
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        csvLine.append(now.format(formatter));

        // Write to CSV file
        try {
            // Create resources directory if it doesn't exist
            File resourcesDir = new File("pe-engines-taxes-core/src/main/resources");
            if (!resourcesDir.exists()) {
                resourcesDir.mkdirs();
            }

            // Create the CSV file
            File csvFile = new File(resourcesDir, "mismatched_itineraries.csv");

            // Synchronize file access to prevent concurrent writes
            synchronized (CSV_LOCK) {
                boolean fileExists = csvFile.exists();

                // Create FileWriter in append mode
                FileWriter writer = new FileWriter(csvFile, true);

                // Write header if file doesn't exist
                if (!fileExists) {
                    writer.write("Route,Itin_TotalPrice,Channel,Calculated_TaxLadder,Timestamp\n");
                }

                // Write the CSV line
                writer.write(csvLine.toString() + "\n");
                writer.close();
            }

            log.info("Wrote mismatched itinerary data to CSV file: " + csvFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("Error writing to CSV file", e);
        }

        return csvLine.toString();
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

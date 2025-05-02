package com.threevictors.aws.priceeye.exp.loader;

import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.data.aws.RawLeg;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class is responsible for loading and parsing PEItinerary objects from a file.
 */
public class PEItinerariesLoader {

    private static PEItinerary parsePEItineraryLine(String line) {
        // Remove "PEItinerary(" from start and ")" from end
        String content = line.substring(12, line.length() - 1);

        // Split by comma while respecting nested structures
        Map<String, String> fieldMap = new HashMap<>();
        int depth = 0;
        StringBuilder currentKey = new StringBuilder();
        StringBuilder currentValue = new StringBuilder();
        boolean parsingKey = true;

        for (char c : content.toCharArray()) {
            if (c == '=' && depth == 0) {
                parsingKey = false;
                continue;
            }
            if (c == ',' && depth == 0) {
                String key = currentKey.toString().trim();
                String value = currentValue.toString().trim();
                fieldMap.put(key, value);
                currentKey = new StringBuilder();
                currentValue = new StringBuilder();
                parsingKey = true;
                continue;
            }
            if (c == '[' || c == '(') depth++;
            if (c == ']' || c == ')') depth--;

            if (parsingKey) {
                currentKey.append(c);
            } else {
                currentValue.append(c);
            }
        }
        // Add the last field
        if (currentKey.length() > 0) {
            fieldMap.put(currentKey.toString().trim(), currentValue.toString().trim());
        }


        PEItinerary itinerary = new PEItinerary();

        itinerary.setCurrency(fieldMap.get("currency"));
        itinerary.setOutBrands(fieldMap.get("outBrands"));
        itinerary.setInBrands(fieldMap.get("inBrands"));

        itinerary.setDuration(Integer.parseInt(fieldMap.get("duration")));
        itinerary.setTotalPrice(Double.parseDouble(fieldMap.get("totalPrice")));
        itinerary.setTaxes(Double.parseDouble(fieldMap.get("taxes")));
        itinerary.setYqyr(Double.parseDouble(fieldMap.get("yqyr")));
        itinerary.setOutboundPrice(Double.parseDouble(fieldMap.get("outboundPrice")));
        itinerary.setInboundPrice(Double.parseDouble(fieldMap.get("inboundPrice")));
        itinerary.setInOutPriceIncludesTax(Boolean.parseBoolean(fieldMap.get("inOutPriceIncludesTax")));

        itinerary.setRefundable(Boolean.parseBoolean(fieldMap.get("refundable")));
        itinerary.setOutboundLegs(parseRawLegList(fieldMap.get("outboundLegs")));
        itinerary.setInboundLegs(parseRawLegList(fieldMap.get("inboundLegs")));
        itinerary.setObservationTimestamp(Long.parseLong(fieldMap.get("observationTimestamp")));
        itinerary.setTotalPriceEnriched(Double.parseDouble(fieldMap.get("totalPriceEnriched")));
        itinerary.setTaxesEnriched(Double.parseDouble(fieldMap.get("taxesEnriched")));

        itinerary.setOutboundPriceEnriched(Double.parseDouble(fieldMap.get("outboundPriceEnriched")));
        itinerary.setInboundPriceEnriched(Double.parseDouble(fieldMap.get("inboundPriceEnriched")));
        itinerary.setOutboundLegsEnriched(null);
        itinerary.setInboundLegsEnriched(null);
        itinerary.setOutBrandsEnriched(null);
        itinerary.setInBrandsEnriched(null);

        itinerary.setFareConstructionText(fieldMap.get("fareConstructionText"));
        itinerary.setTaxLadder(null);
        itinerary.setChangeFee(Double.parseDouble(fieldMap.get("changeFee")));
        itinerary.setChannel(fieldMap.get("channel"));

        return itinerary;
    }

    /**
     * Parses a string representation of a list of RawLeg objects into a List<RawLeg>.
     *
     * @param rawLegsString the string representation of the list of RawLeg objects
     * @return a List<RawLeg> containing the parsed RawLeg objects
     */
    private static List<RawLeg> parseRawLegList(String rawLegsString) {
        if (rawLegsString == null || rawLegsString.equals("null")) {
            return null;
        }

        List<RawLeg> legs = new ArrayList<>();
        String[] rawLegs = rawLegsString.substring(1, rawLegsString.length() - 1).split("\\), RawLeg\\(");

        for (String leg : rawLegs) {
            if (!leg.trim().isEmpty()) {
                legs.add(parseRawLeg(leg));
            }
        }

        return legs;
    }

    /**
     * Parses a string representation of a RawLeg object into a RawLeg.
     *
     * @param legString the string representation of the RawLeg object
     * @return a RawLeg object containing the parsed data
     */
    private static RawLeg parseRawLeg(String legString) {
        Map<String, String> fieldMap = new HashMap<>();
        String[] fields = legString.split(", ");

        for (String field : fields) {
            String[] parts = field.split("=");
            if (parts.length == 2) {
                fieldMap.put(parts[0], parts[1]);
            }
        }

        RawLeg leg = new RawLeg();

        leg.setNumberOfStops(fieldMap.get("numberOfStops")== null ? 0 : Integer.parseInt(fieldMap.get("numberOfStops")));
        leg.setDurationInMinutes(Integer.parseInt(fieldMap.get("durationInMinutes")));
        leg.setOriginAirportCode(fieldMap.get("originAirportCode"));
        leg.setDestinationAirportCode(fieldMap.get("destinationAirportCode"));

        leg.setDepartDate(Integer.parseInt(fieldMap.get("departDate")));
        leg.setDepartTime(Integer.parseInt(fieldMap.get("departTime")));
        leg.setArriveDate(Integer.parseInt(fieldMap.get("arriveDate")));
        leg.setArriveTime(Integer.parseInt(fieldMap.get("arriveTime")));

        leg.setDepartTerminal(fieldMap.get("departTerminal").equals("null") ? null : fieldMap.get("departTerminal"));
        leg.setArriveTerminal(fieldMap.get("arriveTerminal").equals("null") ? null : fieldMap.get("arriveTerminal"));
        leg.setMarketingCarrier(fieldMap.get("marketingCarrier"));
        leg.setOperatingCarrier(fieldMap.get("operatingCarrier"));
        //leg.setFlightNumber(fieldMap.get("flightNumber"));
        leg.setFlightNumber(Integer.parseInt(fieldMap.get("flightNumber")));
        leg.setBookingCode(fieldMap.get("bookingCode"));
        leg.setFareClass(fieldMap.get("fareClass"));
        leg.setCabin(fieldMap.get("cabin"));
        leg.setEquipmentCode(fieldMap.get("equipmentCode"));
        leg.setNumberOfSeats(Integer.parseInt(fieldMap.get("numberOfSeats")));

        leg.setIntermediateAirports(fieldMap.get("intermediateAirports") == null ? null : List.of(fieldMap.get("intermediateAirports")));
        //leg.setIntermediateAirports("null".equals(fieldMap.get("intermediateAirports")) ? null : fieldMap.get("intermediateAirports"));

        leg.setPcc(fieldMap.get("pcc").equals("null") ? null : fieldMap.get("pcc"));
        leg.setAvailabilitySource(fieldMap.get("availabilitySource").equals("null") ? null : fieldMap.get("availabilitySource"));
        leg.setBrandId(fieldMap.get("brandId").equals("null") ? null : fieldMap.get("brandId"));

        return leg;
    }

    /**
     * Reads PEItineraries from a file and returns a list of PEItinerary objects.
     *
     * @param filePath the path to the file containing PEItineraries
     * @return a List<PEItinerary> containing the parsed PEItinerary objects
     */
    public List<PEItinerary> readPEItineraries(String filePath) {
        List<PEItinerary> itineraries = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("PEItinerary(")) {
                    PEItinerary itinerary = parsePEItineraryLine(line);
                    itineraries.add(itinerary);
                }
            }
        }
        catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            e.printStackTrace();
        }
        catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }

        return itineraries;
    }




}

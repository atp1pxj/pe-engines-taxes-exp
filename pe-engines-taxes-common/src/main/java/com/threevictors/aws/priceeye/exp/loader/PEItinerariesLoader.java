package com.threevictors.aws.priceeye.exp.loader;

import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.data.aws.RawLeg;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * This class is responsible for loading and parsing PEItinerary objects from a file.
 */
public class PEItinerariesLoader {

    //itin_validatingcarrier,OBL_originairportcode,OBL_destinationairportcode,OBL_flightNumber,OBL_departdate,OBL_departtime,OBL_arrivedate,OBL_arrivetime,IBL_originairportcode,IBL_destinationairportcode,IBL_flightnumber,IBL_departdate,IBL_departtime,IBL_arrivedate,IBL_arrivetime,itin_cabin,itin_bookingcode,itin_totalamount,taxbreakdown,yq_val,yr_val,itin_true_tax_amount
    //"UA","EWR","YYZ","3622","20251001","1500","20251001","1659","YYZ","EWR","8714","20251015","630","20251015","809","E","G","414.14","USD:AY 5.60|US 23.25|XA 3.71|XF 4.50|XY 7.00|YC 7.20|CA 12.20|SQ 26.80|ZP 10.40|RC 3.48","0.0","0.0","104.14"
    public static PEItinerary parsePEItineraryLineNonStop(List<String> line) {
        // Remove "PEItinerary(" from start and ")" from end
        //String content = line.substring(12, line.length() - 1);

        String itinValidatingCarrier = line.get(0);
        String oblOriginAirportCode = line.get(1);
        String oblDestinationAirportCode = line.get(2);
        String oblFlightNumber = line.get(3);
        String oblDepartDate = line.get(4);
        String oblDepartTime = line.get(5);
        String oblArriveDate = line.get(6);
        String oblArriveTime = line.get(7);
        String iblOriginAirportCode = line.get(8);
        String iblDestinationAirportCode = line.get(9);
        String iblFlightNumber = line.get(10);
        String iblDepartDate = line.get(11);
        String iblDepartTime = line.get(12);
        String iblArriveDate = line.get(13);
        String iblArriveTime = line.get(14);
        String itinCabin = line.get(15);
        String itinBookingCode = line.get(16);
        String itinTotalAmount = line.get(17);
        String taxBreakdown = line.get(18);
        String yqVal = line.get(19);
        String yrVal = line.get(20);
        String itinTrueTaxAmount = line.get(21);
        String lineNumber = line.get(22);



        PEItinerary itinerary = new PEItinerary();

        itinerary.setCurrency("USD");
        itinerary.setOutBrands("");
        itinerary.setInBrands("");

        itinerary.setDuration(0);

        //itinerary.setTotalPrice(Double.parseDouble(fieldMap.get("totalPrice")));
        itinerary.setTotalPrice(Double.parseDouble(itinTotalAmount));

        itinerary.setTaxes(Double.parseDouble(itinTrueTaxAmount));

       // itinerary.setYqyr(Double.parseDouble(fieldMap.get("yqyr").replace("\"", "")));
        itinerary.setYqyr(Double.parseDouble(yqVal) + Double.parseDouble(yrVal));

        itinerary.setOutboundPrice(0);
        itinerary.setInboundPrice(0);
        itinerary.setInOutPriceIncludesTax(false);

        itinerary.setRefundable(false);

        List<RawLeg> obLegList = List.of(parseRawLeg(oblOriginAirportCode, oblDestinationAirportCode, Integer.parseInt(oblDepartDate), Integer.parseInt(oblDepartTime), Integer.parseInt(oblArriveDate), Integer.parseInt(oblArriveTime), Integer.parseInt(oblFlightNumber), itinValidatingCarrier, itinCabin, itinBookingCode));
        itinerary.setOutboundLegs(obLegList);

        List<RawLeg>  ibLegList = List.of(parseRawLeg(iblOriginAirportCode, iblDestinationAirportCode, Integer.parseInt(iblDepartDate), Integer.parseInt(iblDepartTime), Integer.parseInt(iblArriveDate), Integer.parseInt(iblArriveTime), Integer.parseInt(iblFlightNumber), itinValidatingCarrier, itinCabin, itinBookingCode));
        itinerary.setInboundLegs(ibLegList);

        itinerary.setObservationTimestamp(System.currentTimeMillis() / 1000L);
        itinerary.setTotalPriceEnriched(100);
        itinerary.setTaxesEnriched(0);

        itinerary.setOutboundPriceEnriched(0);
        itinerary.setInboundPriceEnriched(0);
        itinerary.setOutboundLegsEnriched(null);
        itinerary.setInboundLegsEnriched(null);
        itinerary.setOutBrandsEnriched(null);
        itinerary.setInBrandsEnriched(null);

        itinerary.setFareConstructionText("");

        if (taxBreakdown != null && !taxBreakdown.isEmpty()) {
            int colon = taxBreakdown.indexOf(":");
            String currency  = taxBreakdown.substring(0, colon);
            String taxLadder = taxBreakdown.substring(colon + 1);

            List<String> taxLadderList = Arrays.asList(taxLadder.split("\\|"));

            itinerary.setTaxLadder(taxLadderList);
            itinerary.setCurrency(currency);
        }
        else {
            itinerary.setTaxLadder(List.of(""));
        }

        itinerary.setChangeFee(0);

        //Temp stuffing of lineNumber in channel for debugging
        itinerary.setChannel(lineNumber);


        return itinerary;
    }


    /**
     * Parses a string representation of a list of RawLeg objects into a RawLeg type
     * @param originAirportCode
     * @param destinationAirportCode
     * @param departDate
     * @param departTime
     * @param arriveDate
     * @param arriveTime
     * @param flightNumber
     * @param marketingCarrier
     * @param cabin
     * @param bookingCode
     * @return a RawLeg object
     */
    private static RawLeg parseRawLeg(String originAirportCode, String destinationAirportCode, int departDate, int departTime, int arriveDate, int arriveTime, int flightNumber, String marketingCarrier, String cabin, String bookingCode) {
        RawLeg rawLeg = new RawLeg();
        rawLeg.setOriginAirportCode(originAirportCode);
        rawLeg.setDestinationAirportCode(destinationAirportCode);
        rawLeg.setDepartDate(departDate); // Example date in yyMMdd format
        rawLeg.setDepartTime(departTime);   // Example time in HHmm format
        rawLeg.setArriveDate(arriveDate);
        rawLeg.setArriveTime(arriveTime);
        rawLeg.setFlightNumber(flightNumber);
        rawLeg.setMarketingCarrier(marketingCarrier);
        rawLeg.setCabin(cabin);
        rawLeg.setBookingCode(bookingCode);

        return rawLeg;
    }


    /**
     * Parses a line of PEItinerary data and returns a PEItinerary object.
     *
     * @param line the line of data to parse
     * @return a PEItinerary object containing the parsed data
     */
    //itin_validatingcarrier,OBL1_originairportcode,OBL1_destinationairportcode,OBL1_mkt_carrier,OBL1_flightNumber,OBL1_departdate,OBL1_departtime,OBL1_arrivedate,OBL1_arrivetime,OBL2_originairportcode,OBL2_destinationairportcode,OBL2_mkt_carrier,OBL2_flightNumber,OBL2_departdate,OBL2_departtime,OBL2_arrivedate,OBL2_arrivetime,IBL1_originairportcode,IBL1_destinationairportcode,IBL1_mkt_carrier,IBL1_flightnumber,IBL1_departdate,IBL1_departtime,IBL1_arrivedate,IBL1_arrivetime,IBL2_originairportcode,IBL2_destinationairportcode,IBL2_mkt_carrier,IBL2_flightnumber,IBL2_departdate,IBL2_departtime,IBL2_arrivedate,IBL2_arrivetime,itin_cabin,itin_totalamount,taxbreakdown,yq_val,yr_val,itin_true_tax_amount
    //"LH","JFK","MUC","LH","411","20250812","1730","20250813","730","MUC","BCN","LH","1810","20250813","905","20250813","1115","BCN","FRA","LH","1139","20250820","650","20250820","900","FRA","JFK","LH","404","20250820","1715","20250820","2005","E","687.21","USD:AY 5.60|US 45.80|XA 3.71|XF 4.50|XY 7.00|YC 7.20|RA 52.50|JD 15.00|OG 0.70|QV 4.20|YQ 351.00|YR 25.00","351.0","25.0","146.21000000000004"
    public static PEItinerary parsePEItineraryLineWithConnections(List<String> line) {
        PEItinerary itinerary = new PEItinerary();

        String itinValidatingCarrier = line.get(0);
        String itinCabin = line.get(33);
        String itinTotalAmount = line.get(34);
        String taxBreakdown = line.get(35);
        String yqVal = line.get(36);
        String yrVal = line.get(37);
        String itinTrueTaxAmount = line.get(38);
        String lineNumber = line.size() > 39 ? line.get(39) : "0";

        itinerary.setCurrency("USD");
        itinerary.setOutBrands("");
        itinerary.setInBrands("");
        itinerary.setDuration(0);
        itinerary.setTotalPrice(Double.parseDouble(itinTotalAmount));
        itinerary.setTaxes(Double.parseDouble(itinTrueTaxAmount));
        itinerary.setYqyr(Double.parseDouble(yqVal) + Double.parseDouble(yrVal));
        itinerary.setOutboundPrice(0);
        itinerary.setInboundPrice(0);
        itinerary.setInOutPriceIncludesTax(false);
        itinerary.setRefundable(false);

        // Outbound Legs
        List<RawLeg> obLegList = List.of(
                parseRawLeg(line.get(1), line.get(2), Integer.parseInt(line.get(5)), Integer.parseInt(line.get(6)), Integer.parseInt(line.get(7)), Integer.parseInt(line.get(8)), Integer.parseInt(line.get(4)), line.get(3), itinCabin, itinCabin),
                parseRawLeg(line.get(9), line.get(10), Integer.parseInt(line.get(13)), Integer.parseInt(line.get(14)), Integer.parseInt(line.get(15)), Integer.parseInt(line.get(16)), Integer.parseInt(line.get(12)), line.get(11), itinCabin, itinCabin)
        );
        itinerary.setOutboundLegs(obLegList);

        // Inbound Legs
        List<RawLeg> ibLegList = List.of(
                parseRawLeg(line.get(17), line.get(18), Integer.parseInt(line.get(21)), Integer.parseInt(line.get(22)), Integer.parseInt(line.get(23)), Integer.parseInt(line.get(24)), Integer.parseInt(line.get(20)), line.get(19), itinCabin, itinCabin),
                parseRawLeg(line.get(25), line.get(26), Integer.parseInt(line.get(29)), Integer.parseInt(line.get(30)), Integer.parseInt(line.get(31)), Integer.parseInt(line.get(32)), Integer.parseInt(line.get(28)), line.get(27), itinCabin, itinCabin)
        );
        itinerary.setInboundLegs(ibLegList);

        itinerary.setObservationTimestamp(System.currentTimeMillis() / 1000L);
        itinerary.setTotalPriceEnriched(100);
        itinerary.setTaxesEnriched(0);
        itinerary.setOutboundPriceEnriched(0);
        itinerary.setInboundPriceEnriched(0);
        itinerary.setOutboundLegsEnriched(null);
        itinerary.setInboundLegsEnriched(null);
        itinerary.setOutBrandsEnriched(null);
        itinerary.setInBrandsEnriched(null);
        itinerary.setFareConstructionText("");

        if (taxBreakdown != null && !taxBreakdown.isEmpty()) {
            int colon = taxBreakdown.indexOf(":");
            String currency = taxBreakdown.substring(0, colon);
            String taxLadder = taxBreakdown.substring(colon + 1);
            List<String> taxLadderList = Arrays.asList(taxLadder.split("\\|"));
            itinerary.setTaxLadder(taxLadderList);
            itinerary.setCurrency(currency);
        } else {
            itinerary.setTaxLadder(List.of(""));
        }

        itinerary.setChangeFee(0);
        itinerary.setChannel(lineNumber); // debug info

        return itinerary;
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
                    //PEItinerary itinerary = parsePEItineraryLine(line);
                    PEItinerary itinerary = parsePEItineraryLineNonStop(List.of(line));
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

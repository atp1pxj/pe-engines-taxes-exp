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
    public static PEItinerary parsePEItineraryLine(List<String> line) {
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
        itinerary.setTaxLadder(List.of(""));
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



    private static double evalExpression(String expression) {
        String[] parts = expression.split("\\+");
        double sum = 0;
        for (String part : parts) {
            sum += Double.parseDouble(part.trim());
        }
        return sum;
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
                    PEItinerary itinerary = parsePEItineraryLine(List.of(line));
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

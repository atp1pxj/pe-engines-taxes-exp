package com.threevictors.aws.priceeye.taxes.utils;

import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.dates.DateTime;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.data.priceeye.PECommonOutput;
import com.threevictors.aws.data.priceeye.Pair;
import com.threevictors.aws.priceeye.taxes.dao.RedshiftCommonOutputReader;
import lombok.Data;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.*;

//Class taken from spark-v3 historical module.
@Data
public class CommonOutputConverter  implements Serializable {

    private static final Logger log = LogManager.getLogger(RedshiftCommonOutputReader.class);

    private Map<String, String> airportToTimezoneMap;

    public CommonOutputConverter(Map<String, String> airportToTimezoneMap) {
        this.airportToTimezoneMap = airportToTimezoneMap;
    }

    private String validate( String string ) {
        if ( string == null ) {
            return null;
        }

        String trimmedString = string.trim();

        if ( trimmedString.isEmpty() ) {
            return null;
        }

        return trimmedString;
    }

    // legacy code calls this method like this: convertToRawSearch( searchWithItineraries, null, null, false, null, 1, "ADT" );

    public Pair<String, PEItinerary> convertCommonOutputToPeItinerary(PECommonOutput commonOutput ) {
        try {
            PEItinerary itinerary = new PEItinerary();

            itinerary.setObservationTimestamp(buildTimestamp(commonOutput.getObservation_date(), commonOutput.getObservation_time()));

            itinerary.setCurrency(commonOutput.getCurrency());

            itinerary.setOutBrands(commonOutput.getOutbound_fare_family());
            itinerary.setInBrands(commonOutput.getInbound_fare_family());

            itinerary.setOutboundLegs(buildOutboundLegList(commonOutput));
            itinerary.setInboundLegs(buildInboundLegList(commonOutput));


            if (commonOutput.getOutbound_total_flight_duration() == 0) {
                commonOutput.setOutbound_total_flight_duration(calculateTotalFlightDuration(itinerary.getOutboundLegs()));
            }

            if (itinerary.getInboundLegs() != null && !itinerary.getInboundLegs().isEmpty()) {
                commonOutput.setInbound_total_flight_duration(calculateTotalFlightDuration(itinerary.getInboundLegs()));
            }

            int totalDuration = commonOutput.getOutbound_total_flight_duration();

            //TODO: check this condition
            if (commonOutput.getInbound_total_flight_duration() > 0) {
                totalDuration += commonOutput.getInbound_total_flight_duration();
            }

            itinerary.setDuration(totalDuration);

            itinerary.setTotalPrice(commonOutput.getPrice_inc());
            itinerary.setTaxes(commonOutput.getTax());
            itinerary.setYqyr(commonOutput.getYqyr());
            itinerary.setOutboundPrice(commonOutput.getPrice_outbound());
            itinerary.setInboundPrice(commonOutput.getPrice_inbound());
            itinerary.setInOutPriceIncludesTax(commonOutput.getIs_tax_inc_outin() == 1);
            itinerary.setRefundable(commonOutput.isRefundable());

            itinerary.setChannel(commonOutput.getSource());

            //itinerary.setFareConstructionText( ? );
            //itinerary.setTaxLadder( ? );

            itinerary.setChangeFee(commonOutput.getChange_fee());


            // leave enriched fields unset
            //double totalPriceEnriched;
            //double taxesEnriched;
            //double outboundPriceEnriched;
            //double inboundPriceEnriched;
            //java.util.List<com.threevictors.aws.data.aws.RawLeg> outboundLegsEnriched;
            //java.util.List<com.threevictors.aws.data.aws.RawLeg> inboundLegsEnriched;
            //java.lang.String outBrandsEnriched;
            //java.lang.String inBrandsEnriched;

            return new Pair<>( commonOutput.getPos(), itinerary );
        }
        catch (Exception e) {
            log.error("Error converting common output to pe itinerary", e);
            return null;
        }
    }


    private int calculateTotalFlightDuration( List<RawLeg> legs ) {
        int duration = legs.get(0).getDurationInMinutes();

        for (int idx = 1; idx < legs.size(); idx++) {
            RawLeg previousLeg = legs.get(idx - 1);
            RawLeg nextLeg = legs.get(idx);

            DateTime previousArrive = new DateTime( previousLeg.getArriveDate(), previousLeg.getArriveTime() );
            DateTime nextDepart     = new DateTime( nextLeg.getDepartDate(), nextLeg.getDepartTime() );

            int layover = nextDepart.getElapsedMinutes( previousArrive );

            duration += layover + legs.get(idx).getDurationInMinutes();
        }

        return duration;
    }

    private List<RawLeg> buildOutboundLegList( PECommonOutput commonOutput ) {
        int outboundLegCount = countPipes( commonOutput.getOutbound_marketing_carrier_list() ) + 1;

        List<RawLeg> outboundLegList = new ArrayList<>();

        if ( outboundLegCount == 1 ) {
            //
            // single leg
            //
            RawLeg rawLeg = new RawLeg();

            rawLeg.setNumberOfStops( getStops( commonOutput.getOutbound_travel_stop_over() ) );
            rawLeg.setDurationInMinutes( getDuration( commonOutput.getOutbound_flight_duration() ) );
            rawLeg.setOriginAirportCode( commonOutput.getOrigin() );
            rawLeg.setDestinationAirportCode( commonOutput.getDestination() );
            rawLeg.setDepartDate( buildDate( commonOutput.getOutbound_departure_date() ) );
            rawLeg.setDepartTime( buildTime( commonOutput.getOutbound_departure_time() ) );

            rawLeg.setArriveDate( buildDate( commonOutput.getOutbound_arrival_date() ) );
            rawLeg.setArriveTime( buildTime(commonOutput.getOutbound_arrival_time()));

            rawLeg.setMarketingCarrier( commonOutput.getOutbound_dominant_marketing_carrier() );
            rawLeg.setOperatingCarrier( commonOutput.getOutbound_operating_carrier_list() );
            rawLeg.setFlightNumber( getFlightNumber( commonOutput.getOutbound_flight_no() ) );
            rawLeg.setBookingCode( commonOutput.getOutbound_booking_class() );
            rawLeg.setFareClass( commonOutput.getOutbound_fare_basis() );
            rawLeg.setCabin( commonOutput.getOutbound_cabins() );
            rawLeg.setNumberOfSeats( getSeatCount( commonOutput.getOutbound_available_seats() ) );
            rawLeg.setIntermediateAirports( buildList( commonOutput.getOutbound_travel_stop_over() ) );
            rawLeg.setBrandId( commonOutput.getOutbound_fare_family() );

            outboundLegList.add( rawLeg );
            return outboundLegList;
        }

        //
        // multiple legs
        //
        String [] durations = commonOutput.getOutbound_flight_duration().split("\\|");
        String [] marketingCarriers = commonOutput.getOutbound_marketing_carrier_list().split("\\|");
        String [] operatingCarriers = commonOutput.getOutbound_operating_carrier_list().split("\\|");
        String [] flightNumbers = commonOutput.getOutbound_flight_no().split("\\|");
        String [] bookingCodes = commonOutput.getOutbound_booking_class().split("\\|");
        String [] fareClasses = commonOutput.getOutbound_fare_basis().split("\\|");
        String [] cabins = commonOutput.getOutbound_cabins().split("\\|");
        String [] seats = commonOutput.getOutbound_available_seats().split("\\|");
        String [] brands = commonOutput.getOutbound_booking_class().split("\\|");

        for ( int legIndex = 0; legIndex < outboundLegCount; ++legIndex ) {
            RawLeg rawLeg = new RawLeg();

            rawLeg.setNumberOfStops( 0 );
            rawLeg.setDurationInMinutes( extractInt( durations, legIndex) );

            // assume only nonstop and one stops and no land segment between flights .. this is dumb
            if ( legIndex == 0 ) { //first leg
                rawLeg.setOriginAirportCode( commonOutput.getOrigin() );
                rawLeg.setDestinationAirportCode( commonOutput.getOutbound_travel_stop_over() );
                rawLeg.setDepartDate( buildDate( commonOutput.getOutbound_departure_date() ) );
                rawLeg.setDepartTime( buildTime( commonOutput.getOutbound_departure_time() ) );

                //This method sets the arrival Time and arrival Date on the leg
                calculateDateTimeWithDuration(rawLeg, true );
            }
            else { //subsequent legs
                rawLeg.setOriginAirportCode( commonOutput.getOutbound_travel_stop_over() );
                rawLeg.setDestinationAirportCode( commonOutput.getDestination() );

                rawLeg.setArriveDate( buildDate( commonOutput.getOutbound_arrival_date() ) );
                rawLeg.setArriveTime( buildTime( commonOutput.getOutbound_arrival_time() ) );

                //sets depart time and depart date on the leg
                calculateDateTimeWithDuration(rawLeg, false );
            }

            rawLeg.setMarketingCarrier( extract( marketingCarriers, legIndex ) );
            rawLeg.setOperatingCarrier( extract( operatingCarriers, legIndex ) );
            rawLeg.setFlightNumber( extractFlightNumber( flightNumbers, legIndex, outboundLegCount ) );

            rawLeg.setBookingCode( extract( bookingCodes, legIndex ) );
            rawLeg.setFareClass( extract( fareClasses, legIndex ) );
            rawLeg.setCabin( extract( cabins, legIndex ) );
            rawLeg.setNumberOfSeats( extractInt( seats, legIndex ) );
            rawLeg.setBrandId( extract( brands, legIndex ) );

            outboundLegList.add( rawLeg );
        }

        return outboundLegList;
    }

    private List<RawLeg> buildInboundLegList( PECommonOutput commonOutput ) {
        if ( commonOutput.getInbound_marketing_carrier_list() == null || commonOutput.getInbound_marketing_carrier_list().isEmpty() ) {
            return null;
        }


        int inboundLegCount = countPipes( commonOutput.getInbound_marketing_carrier_list() ) + 1;

        List<RawLeg> inboundLegList = new ArrayList<>();

        if ( inboundLegCount == 1 ) {
            //
            // single leg
            //
            RawLeg rawLeg = new RawLeg();

            rawLeg.setNumberOfStops( getStops( commonOutput.getInbound_travel_stop_over() ) );
            rawLeg.setDurationInMinutes( getDuration( commonOutput.getInbound_flight_duration() ) );
            rawLeg.setOriginAirportCode( commonOutput.getDestination() );
            rawLeg.setDestinationAirportCode( commonOutput.getOrigin() );
            rawLeg.setDepartDate( buildDate( commonOutput.getInbound_departure_date() ) );
            rawLeg.setDepartTime( buildTime( commonOutput.getInbound_departure_time() ) );

            rawLeg.setArriveDate( buildDate( commonOutput.getInbound_arrival_date() ) );
            rawLeg.setArriveTime( buildTime(commonOutput.getInbound_arrival_time()));

            rawLeg.setMarketingCarrier( commonOutput.getInbound_dominant_marketing_carrier() );
            rawLeg.setOperatingCarrier( commonOutput.getInbound_operating_carrier_list() );
            rawLeg.setFlightNumber( getFlightNumber( commonOutput.getInbound_flight_no() ) );
            rawLeg.setBookingCode( commonOutput.getInbound_booking_class() );
            rawLeg.setFareClass( commonOutput.getInbound_fare_basis() );
            rawLeg.setCabin( commonOutput.getInbound_cabins() );
            rawLeg.setNumberOfSeats( getSeatCount( commonOutput.getInbound_available_seats() ) );
            rawLeg.setIntermediateAirports( buildList( commonOutput.getInbound_travel_stop_over() ) );
            rawLeg.setBrandId( commonOutput.getInbound_fare_family() );

            inboundLegList.add( rawLeg );

            return inboundLegList;
        }

        //
        // multiple legs
        //
        String [] durations = commonOutput.getInbound_flight_duration().split("\\|");
        String [] marketingCarriers = commonOutput.getInbound_marketing_carrier_list().split("\\|");
        String [] operatingCarriers = commonOutput.getInbound_operating_carrier_list().split("\\|");
        String [] flightNumbers = commonOutput.getInbound_flight_no().split("\\|");
        String [] bookingCodes = commonOutput.getInbound_booking_class().split("\\|");
        String [] fareClasses = commonOutput.getInbound_fare_basis().split("\\|");
        String [] cabins = commonOutput.getInbound_cabins().split("\\|");
        String [] seats = commonOutput.getInbound_available_seats().split("\\|");
        String [] brands = commonOutput.getInbound_booking_class().split("\\|");

        for ( int legIndex = 0; legIndex < inboundLegCount; ++legIndex ) {
            RawLeg rawLeg = new RawLeg();

            rawLeg.setNumberOfStops( 0 );
            rawLeg.setDurationInMinutes( extractInt( durations, legIndex) );

            // assume only nonstop and one stops and no land segment between flights .. this is dumb
            if ( legIndex == 0 ) { //first leg
                rawLeg.setOriginAirportCode( commonOutput.getDestination() );
                rawLeg.setDestinationAirportCode( commonOutput.getInbound_travel_stop_over() );
                rawLeg.setDepartDate( buildDate( commonOutput.getInbound_departure_date() ) );
                rawLeg.setDepartTime( buildTime( commonOutput.getInbound_departure_time() ) );

                //This method sets the arrival Time and arrival Date on the leg
                calculateDateTimeWithDuration(rawLeg, true );
            }
            else { //subsequent legs
                rawLeg.setOriginAirportCode( commonOutput.getInbound_travel_stop_over() );
                rawLeg.setDestinationAirportCode( commonOutput.getOrigin() );

                rawLeg.setArriveDate( buildDate( commonOutput.getInbound_arrival_date() ) );
                rawLeg.setArriveTime( buildTime( commonOutput.getInbound_arrival_time() ) );

                //sets depart time and depart date on the leg
                calculateDateTimeWithDuration(rawLeg, false );
            }

            rawLeg.setMarketingCarrier( extract( marketingCarriers, legIndex ) );
            rawLeg.setOperatingCarrier( extract( operatingCarriers, legIndex ) );
            rawLeg.setFlightNumber( extractFlightNumber( flightNumbers, legIndex, inboundLegCount ) );

            rawLeg.setBookingCode( extract( bookingCodes, legIndex ) );
            rawLeg.setFareClass( extract( fareClasses, legIndex ) );
            rawLeg.setCabin( extract( cabins, legIndex ) );
            rawLeg.setNumberOfSeats( extractInt( seats, legIndex ) );
            rawLeg.setBrandId( extract( brands, legIndex ) );

            inboundLegList.add( rawLeg );
        }

        return inboundLegList;
    }





    private int extractInt(String [] tokens, int index) {
        if (index >= tokens.length) return -1;

        if ( tokens[index] == null || tokens[index].isEmpty() ) return 0;

        return Integer.parseInt( tokens[index] );
    }

    private String extract( String [] tokens, int index) {
        if (index >= tokens.length) return "";

        return tokens[index];
    }

    private int extractFlightNumber( String[] tokens, int index, int outboundLegCount) {
        if (tokens.length > outboundLegCount) {
            // Special behavior
            return Integer.parseInt( tokens[2 * index + 1] );
        }

        return Integer.parseInt( tokens[index].substring(2) );
    }

    /**
     * Method to recalculate arrival date and arrival time or departure date and departure time as per duration.
     * @param rawLeg
     * @param isSetArrivalDateTime
     */
    private void calculateDateTimeWithDuration(RawLeg rawLeg, boolean isSetArrivalDateTime ) {

        if (isSetArrivalDateTime) {
            String departDateTime = String.format("%d%04d", rawLeg.getDepartDate(), rawLeg.getDepartTime());
            int durationInMins = rawLeg.getDurationInMinutes();
            String originAirportCode = rawLeg.getOriginAirportCode();
            String originTimeZone = airportToTimezoneMap.get(originAirportCode);

            //int arriveDate = rawLeg.getArriveDate();
            String destinationAirportCode = rawLeg.getDestinationAirportCode();
            String destinationTimeZone = airportToTimezoneMap.get(destinationAirportCode);

            if (originTimeZone == null ) {
                throw new RuntimeException("Origin timezone is null for airport code: " + originAirportCode);
            }

            if (destinationTimeZone == null ) {
                throw new RuntimeException("Destination timezone is null for airport code: " + destinationAirportCode);
            }

            DateTime destinationDateTime = convertSourceTZDateTimeToDestTZDateTimePlusDuration(departDateTime, originTimeZone, durationInMins, destinationTimeZone);

            rawLeg.setArriveDate( destinationDateTime.getDate() );
            rawLeg.setArriveTime( destinationDateTime.getTime() );
        }
        else { // set departDate and departTime on the rawleg
            String arriveDateTime = String.format("%d%04d", rawLeg.getArriveDate(), rawLeg.getArriveTime());

            int durationInMins = rawLeg.getDurationInMinutes();
            String destinationAirportCode = rawLeg.getDestinationAirportCode();
            String destinationTimeZone = airportToTimezoneMap.get(destinationAirportCode);

            //int departDate = rawLeg.getDepartDate();
            String originAirportCode = rawLeg.getOriginAirportCode();
            String originTimeZone = airportToTimezoneMap.get(originAirportCode);

            DateTime originDateTime = convertSourceTZDateTimeToDestTZDateTimePlusDuration(arriveDateTime, destinationTimeZone, -durationInMins, originTimeZone);
            //set recalculated departure date and departure time values
            rawLeg.setDepartDate( originDateTime.getDate() );
            rawLeg.setDepartTime( originDateTime.getTime() );
        }

    }

    /**
     * Method to convert source timezone date and time to destination timezone date and time plus duration
     * @param sourceDateTime
     * @param sourceTimeZone
     * @param addMinutes
     * @param destinationTimeZone
     * @return String
     */
    public DateTime convertSourceTZDateTimeToDestTZDateTimePlusDuration(String sourceDateTime, String sourceTimeZone, int addMinutes, String destinationTimeZone) {
        //Create a date and time object as per the timezone
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(TimeZone.getTimeZone(sourceTimeZone).toZoneId());
        TemporalAccessor sourceDateTimePerZone = formatter.parse(sourceDateTime);
        //sourceDateTimePerZone will be something like {InstantSeconds=1730336400},ISO,America/New_York resolved to 2024-10-30T21:00

        //Add minutes to the date and time (-addMinutes to calculate depart date and time from arrival date and time)
        ZonedDateTime zonedDateTime1 = ZonedDateTime.from(sourceDateTimePerZone);
        ZonedDateTime zonedDateTime2 = zonedDateTime1.plusMinutes(addMinutes);

        //Convert zonedDateTime2 to destination timezone
        ZonedDateTime destinationDateTimePerTZ = zonedDateTime2.withZoneSameInstant(TimeZone.getTimeZone(destinationTimeZone).toZoneId());

        int date = destinationDateTimePerTZ.getYear() * 10000 +
                destinationDateTimePerTZ.getMonthValue() * 100
                + destinationDateTimePerTZ.getDayOfMonth();

        int time = destinationDateTimePerTZ.getHour() * 100 + destinationDateTimePerTZ.getMinute();

        return new DateTime( date, time );
    }


    private long buildTimestamp( String commonOutputDate, String commonOutputTime ) {
        DateTime dt = new DateTime();

        dt.setDate( Integer.parseInt( commonOutputDate.replaceAll( "-", "" ) ) );
        dt.setTime( Integer.parseInt( commonOutputTime.replaceAll( ":", "" ) ) );

        return dt.timezoneToMillis("UTC");
    }

    private int buildDate( String commonOutputDate ) {
        int date = 0;
        try {
            date = Integer.parseInt( commonOutputDate.replaceAll( "-", "" ) );
        }
        catch ( Exception ignore ) {}
        return date;
    }

    private int buildTime( String commonOutputTime ) {
        int time = 0;
        try {
            time = Integer.parseInt( commonOutputTime.replaceAll( ":", "" ) );
        }
        catch ( Exception ignore ) {}
        return time;
    }

    private int countPipes( String field ) {
        if ( field == null || !field.contains( "|" ) ) {
            return 0;
        }

        return field.length() - field.replace("|", "").length();
    }

    private int getStops( String stopOverField ) {
        if ( stopOverField == null || stopOverField.isEmpty() ) {
            return 0;
        }

        if ( stopOverField.length() == 3 ) {
            return 1;
        }

        // this is probably good enough since we should have only 0 and 1 stop
        return 2;
    }

    private int getSeatCount( String seatCount ) {
        int iSeatCount = -1;

        try {
            iSeatCount = Integer.parseInt( seatCount );
        }
        catch ( Exception ignore ) {}

        return iSeatCount;
    }

    private int getDuration( String duration ) {
        int iDuration = -1;

        try {
            iDuration = Integer.parseInt( duration );
        }
        catch ( Exception ignore ) {}

        return iDuration;
    }

    private List<String> buildList( String s ) {
        List<String> list = new ArrayList<>();

        if ( s == null || s.isEmpty() ) {
            return list;
        }

        if ( s.contains( "|" ) ) {
            String[] values = StringUtils.splitPreserveAllTokens( s, "|" );
            Collections.addAll( list, values );
            return list;
        }

        list.add( s );
        return list;
    }

    private int getFlightNumber( String s ) {
        if ( s.contains( "|" ) ) {
            String [] tokens = s.split( "\\|" );

            return Integer.parseInt( tokens[1].substring( 2 ) );
        }
        int flightNumber = -1;
        try {
            flightNumber = Integer.parseInt( s.substring( 2 ) );
        }
        catch ( Exception ignore ) {}
        return flightNumber;
    }

    private String getDelimitedValue( @SuppressWarnings( "SameParameterValue" ) String delimiter, int position, String field ) {
        if ( field == null || field.isBlank() ) {
            return "";
        }

        if ( !field.contains( delimiter ) ) {
            return field;
        }

        try {
            String[] values = StringUtils.splitPreserveAllTokens( field, delimiter );
            return values[ position ];
        }
        catch ( Exception e ) {
            log.warn( "Error getting delimited value: delimiter=" + delimiter + ", position=" + position + ", field=" + field, e );
            return field;
        }
    }


}

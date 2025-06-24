package com.threevictors.aws.priceeye.exp.velocity.builder;

import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.priceeye.*;
import com.threevictors.aws.data.wtf.SkinnyCacheFlightRecord;
import com.threevictors.aws.priceeye.common.PEOagRecordCache2;
import com.threevictors.aws.priceeye.exp.velocity.data.TaxEngineReqVelocityData;
import com.threevictors.aws.priceeye.exp.velocity.data.TaxLegVelocityData;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class TaxEngineRequestBuilder extends AbstractEngineRequestBuilder<TaxEngineReqVelocityData> {

    private PEOagRecordCache2 oagRecordCache2;

    public TaxEngineRequestBuilder() {
        super();
        oagRecordCache2 = new PEOagRecordCache2(1000); // Initialize with a cache size of 1000
        oagRecordCache2.initialize();
    }

    @Override
    public String buildRequest(PEItinerary peItinerary) {
        return buildJSONRequest(peItinerary);
    }

    private String buildJSONRequest(PEItinerary peItinerary) {

        TaxEngineReqVelocityData ctx = new TaxEngineReqVelocityData();

        stubTaxEngineReqVelocityData(ctx, peItinerary);
        String velocityData = runVelocity(ctx, "tax_engine_req.json.vm");
        return velocityData;
    }

    //Create and initialize a static map with cabin keys and cabin values
    private static final Map<String, String> CABIN_MAP = Map.of(
            "F", "FIRST",
            "B", "BUSINESS",
            "E", "ECONOMY",
            "P", "PREMIUM_ECONOMY"
    );

    private TaxLegVelocityData buildLegData( RawLeg leg, boolean lastLeg) {

        TaxLegVelocityData legData = new TaxLegVelocityData();

        legData.setDepartsDateTime(String.format("%06d %02d:%02d", (leg.getDepartDate() % 1000000), leg.getDepartTime() / 100, leg.getDepartTime() % 100));
        legData.setArrivesDateTime(String.format("%06d %02d:%02d", (leg.getArriveDate() % 1000000), leg.getArriveTime() / 100, leg.getArriveTime() % 100));

        legData.setOriginCode(leg.getOriginAirportCode());
        legData.setDestinationCode(leg.getDestinationAirportCode());
        legData.setMarketedCarrier(leg.getMarketingCarrier());
        legData.setMarketedFlightNo(leg.getFlightNumber());

        //Operating carrier and flight number are obtained from the SkinnyCacheFlightRecord
        //String originAirportCode, String destinationAirportCode, String carrierCode, int flightNumber, int travelDate
        SkinnyCacheFlightRecord skinnyCacheFlightRecord = oagRecordCache2.getApplicableFlightRecord(legData.getOriginCode(),
                legData.getDestinationCode(),
                legData.getMarketedCarrier(),
                legData.getMarketedFlightNo(),
                leg.getDepartDate()); // Note: travelDate is the depart date in the RawLeg and is sent as 20250522 instead of 250522

        /* IMPORTANT NOTE: If the skinnyCacheFlightRecord is null, default the operating carrier to "" and operatingFlight number to 0 as this will give the SAME responses as
           passing correct values for operating carrier and flight number.
        * DON'T default to marketedcarrier and marketedFlight number as it is INCORRECT. Will result in erroneous tax values. This has been verified through postman*/
        if (skinnyCacheFlightRecord == null) {
            legData.setOperatedCarrier("");
            legData.setOperatedFlightNo(0);
        }
        else {
            if ( skinnyCacheFlightRecord.getCodeshare() == 1 && skinnyCacheFlightRecord.getOperatingCarrierCode() != null && !skinnyCacheFlightRecord.getOperatingCarrierCode().isEmpty() ) {
                legData.setOperatedCarrier( skinnyCacheFlightRecord.getOperatingCarrierCode() );
                legData.setOperatedFlightNo(skinnyCacheFlightRecord.getOperatingCarrierFlightNumber());
            }
            else {//If codeshare is 0 or operating carrier code is null or empty? Engines will need values for operated carrier and flight number.
                    //Default it to marketed carrier and flight number from the skinnyCacheFlightRecord.
                    //Don't set it to blank operated carrier and 0 operated flight number as it will result in incorrect tax values (tested already).
                    legData.setOperatedCarrier(skinnyCacheFlightRecord.getCarrierCode());
                    legData.setOperatedFlightNo(skinnyCacheFlightRecord.getFlightNumber());
            }
        }

        if (lastLeg) {
            legData.setTransferTypeLeg(true);
            legData.setTransferType("STOP_OVER");
        }
        else {
            legData.setTransferTypeLeg(true);
            legData.setTransferType("CONNECTION");
        }

        //Note: Empty cabin causes engine response to fail.
        if ( leg.getCabin() != null && !leg.getCabin().isEmpty()) {
            legData.setCabin(CABIN_MAP.get(leg.getCabin().trim()));
        }

        return legData;
    }


    private void stubTaxEngineReqVelocityData(TaxEngineReqVelocityData ctx, PEItinerary peItinerary) {

        List<TaxLegVelocityData> legs = new ArrayList<>();

        for (int i = 0; i < peItinerary.getOutboundLegs().size(); i++) {
            RawLeg leg = peItinerary.getOutboundLegs().get(i);
            TaxLegVelocityData legData = buildLegData(leg, i == peItinerary.getOutboundLegs().size() - 1);
            //OB legs have a fareIndex of 0
            legData.setFareIndex(0);

            legs.add(legData);
        }

        for (int i = 0; i < peItinerary.getInboundLegs().size(); i++) {
            RawLeg leg = peItinerary.getInboundLegs().get(i);

            boolean lastLeg = i == peItinerary.getInboundLegs().size() - 1;
            TaxLegVelocityData legData = buildLegData(leg, lastLeg );

            if (lastLeg) legData.setTransferTypeLeg(false);

            legData.setFareIndex(1);
            legs.add(legData);
        }

        ctx.setLegs(legs);

        //Non-leg data
        //TODO: What field on the PEItinerary is the TripType? Not able to find it on the legs either
        //ctx.setTripType("ROUND_TRIP");

        //Newly added logic to determine trip type based on inbound legs
        if(peItinerary.getInboundLegs()!= null && !peItinerary.getInboundLegs().isEmpty()) {
            ctx.setTripType("ROUND_TRIP");
        } else {
            ctx.setTripType("ONE_WAY");
        }

        ctx.setFareOwningCarrier(peItinerary.getOutboundLegs().get(0).getMarketingCarrier());

        //TODO: What field on the PEItinerary is the ticketDate? Not able to find it on the legs either. Should it be today's date?
        //TicketDate should be in format yyMMdd. Set to today's date.
        //ctx.setTicketDate(LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")));

        //TicketDate should be in format yyMMdd. Set to the date when the stubbed itin data was created.
        //Note that this value is obtained from the runtime argument of the main method, which is set on the duration field
        //in the PEItinerary class as there is no other int field to hold the value.
        ctx.setTicketDate(String.valueOf(peItinerary.getDuration()));

        //Note: Setting first outbound leg's marketing carrier as the validating carrier.
        ctx.setValidatingCarrier(peItinerary.getOutboundLegs().get(0).getMarketingCarrier());
        ctx.setCurrency(peItinerary.getCurrency());
        ctx.setTotalPrice(peItinerary.getTotalPrice());
    }

}

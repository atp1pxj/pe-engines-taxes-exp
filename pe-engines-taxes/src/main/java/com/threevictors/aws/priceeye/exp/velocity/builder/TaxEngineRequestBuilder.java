package com.threevictors.aws.priceeye.exp.velocity.builder;

import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.priceeye.*;
import com.threevictors.aws.priceeye.exp.velocity.data.TaxEngineReqVelocityData;
import com.threevictors.aws.priceeye.exp.velocity.data.TaxLegVelocityData;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TaxEngineRequestBuilder extends AbstractEngineRequestBuilder<TaxEngineReqVelocityData> {


    public TaxEngineRequestBuilder() {
        super();
    }

    @Override
    public String buildRequest(PEItinerary peItinerary) {
        return buildJSONRequest(peItinerary);
    }

    private String buildJSONRequest(PEItinerary peItinerary) {

        TaxEngineReqVelocityData ctx = new TaxEngineReqVelocityData();
        //stubPEItinerary(peItinerary);
        stubTaxEngineReqVelocityData(ctx, peItinerary);

        String velocityData = runVelocity(ctx, "tax_engine_req.json.vm");
        return velocityData;
        //return runVelocity(ctx, "tax_engine_req.json.vm");
    }

    private void stubPEItinerary(PEItinerary peItinerary) {

        List<RawLeg> outboundLegs;
        outboundLegs = new ArrayList<>();

        RawLeg outboundLeg = new RawLeg();
        outboundLeg.setDepartDate(250601);
        outboundLeg.setDepartTime(810);
        outboundLeg.setArriveDate(250601);
        outboundLeg.setArriveTime(2000);
        outboundLeg.setOriginAirportCode("JFK");
        outboundLeg.setDestinationAirportCode("LAX");
        outboundLeg.setMarketingCarrier("DL");
        outboundLeg.setOperatingCarrier("DL");
        outboundLeg.setFlightNumber(1);
        outboundLegs.add(outboundLeg);

        List<RawLeg> inboundLegs;
        inboundLegs = new ArrayList<>();
        RawLeg inboundLeg = new RawLeg();
        inboundLeg.setDepartDate(250608);
        inboundLeg.setDepartTime(935);
        inboundLeg.setArriveDate(250608);
        inboundLeg.setArriveTime(1245);
        inboundLeg.setOriginAirportCode("LAX");
        inboundLeg.setDestinationAirportCode("JFK");
        inboundLeg.setMarketingCarrier("DL");
        inboundLeg.setOperatingCarrier("DL");
        inboundLeg.setFlightNumber(2);
        inboundLegs.add(inboundLeg);

        //For PEItinerary - you need currency, totalPrice, outboundLegs, inboundLegs
        peItinerary.setCurrency("USD");
        peItinerary.setTotalPrice(1000.00);
        peItinerary.setOutboundLegs(outboundLegs);
        peItinerary.setInboundLegs(inboundLegs);
    }


    private void stubTaxEngineReqVelocityData(TaxEngineReqVelocityData ctx, PEItinerary peItinerary) {

        List<TaxLegVelocityData> legs = new ArrayList<>();

        //for (RawLeg leg : peItinerary.getOutboundLegs()) {
        for (int i = 0; i < peItinerary.getOutboundLegs().size(); i++) {
            RawLeg leg = peItinerary.getOutboundLegs().get(i);
            TaxLegVelocityData legData = new TaxLegVelocityData();
            legData.setDepartsDateTime(leg.getDepartDate() + " " + String.format("%02d:%02d", leg.getDepartTime()/100, leg.getDepartTime()%100));
            legData.setArrivesDateTime(leg.getArriveDate() + " " + String.format("%02d:%02d", leg.getArriveTime()/100, leg.getArriveTime()%100));
            legData.setOriginCode(leg.getOriginAirportCode());
            legData.setDestinationCode(leg.getDestinationAirportCode());
            legData.setMarketedCarrier(leg.getMarketingCarrier());
            legData.setOperatedCarrier(leg.getOperatingCarrier());
            legData.setMarketedFlightNo(leg.getFlightNumber());
            if (i < peItinerary.getOutboundLegs().size() - 1) {
                legData.setTransferTypeLeg(true);
                legData.setTransferType("CONNECTION");
            }
            //NOTE: For outbound, last leg, we set transferType to STOP_OVER
            if (i == peItinerary.getOutboundLegs().size()-1) {
                legData.setTransferTypeLeg(true);
                legData.setTransferType("STOP_OVER");
            }

            legs.add(legData);
        }

       // for (RawLeg leg : peItinerary.getInboundLegs()) {
        for (int i = 0; i < peItinerary.getInboundLegs().size(); i++) {
            RawLeg leg = peItinerary.getInboundLegs().get(i);
            TaxLegVelocityData legData = new TaxLegVelocityData();
            legData.setDepartsDateTime(leg.getDepartDate() + " " + String.format("%02d:%02d", leg.getDepartTime()/100, leg.getDepartTime()%100));
            legData.setArrivesDateTime(leg.getArriveDate() + " " + String.format("%02d:%02d", leg.getArriveTime()/100, leg.getArriveTime()%100));
            legData.setOriginCode(leg.getOriginAirportCode());
            legData.setDestinationCode(leg.getDestinationAirportCode());
            legData.setMarketedCarrier(leg.getMarketingCarrier());
            legData.setOperatedCarrier(leg.getOperatingCarrier());
            legData.setMarketedFlightNo(leg.getFlightNumber());
            if (i < peItinerary.getInboundLegs().size() - 1) {
                legData.setTransferTypeLeg(true);
                legData.setTransferType("CONNECTION");
            }
            //NOTE: For inbound, last leg, we dont set transferType
            legs.add(legData);
        }

        ctx.setLegs(legs);

        //TODO: What field on the PEItinerary is the TripType? Not able to find it on the legs either
        ctx.setTripType("ROUND_TRIP");
        ctx.setFareOwningCarrier(peItinerary.getOutboundLegs().get(0).getMarketingCarrier());
        //TODO: What field on the PEItinerary is the ticketDate? Not able to find it on the legs either. Should it be today's date?
        ctx.setTicketDate("250502");
        ctx.setValidatingCarrier(peItinerary.getOutboundLegs().get(0).getMarketingCarrier());
        ctx.setCurrency(peItinerary.getCurrency());
        ctx.setTotalPrice(peItinerary.getTotalPrice());


        //Non-leg data
        /*ctx.setTripType("ROUND_TRIP");
        ctx.setFareOwningCarrier("DL");
        ctx.setTicketDate("250429");
        ctx.setValidatingCarrier("DL");
        ctx.setCurrency(peItinerary.getCurrency());
        ctx.setTotalPrice(peItinerary.getTotalPrice());*/
    }




    //This stubbing is for 2 legs each way JFK - CHA  and back
    private void stubTaxEngineReqVelocityDataOld(TaxEngineReqVelocityData ctx) {

        List<TaxLegVelocityData> legs = new ArrayList<>();

        TaxLegVelocityData leg1 = new TaxLegVelocityData();
        leg1.setDepartsDateTime("250601 06:00");
        leg1.setArrivesDateTime("250601 18:00");
        //legId
        leg1.setLegId(1);
        leg1.setOriginCode("JFK");
        leg1.setDestinationCode("ATL");
        leg1.setMarketedCarrier("DL");
        leg1.setMarketedFlightNo(2286);
        leg1.setOperatedCarrier("DL");
        leg1.setOperatedFlightNo(2286);
        leg1.setFareIndex(0);
        leg1.setTransferTypeLeg(true);
        leg1.setTransferType("CONNECTION");
        legs.add(leg1);

        TaxLegVelocityData leg2 = new TaxLegVelocityData();
        leg2.setDepartsDateTime("250601 09:10");
        leg2.setArrivesDateTime("250601 10:01");
        leg2.setLegId(2);
        leg2.setOriginCode("ATL");
        leg2.setDestinationCode("CHA");
        leg2.setMarketedCarrier("DL");
        leg2.setMarketedFlightNo(5227);
        leg2.setOperatedCarrier("DL");
        leg2.setOperatedFlightNo(5227);
        leg2.setFareIndex(0);
        leg1.setTransferTypeLeg(true);
        leg2.setTransferType("STOP_OVER");
        legs.add(leg2);

        TaxLegVelocityData leg3 = new TaxLegVelocityData();
        leg3.setDepartsDateTime("250608 05:30");
        leg3.setArrivesDateTime("250608 06:31");
        leg3.setLegId(3);
        leg3.setOriginCode("CHA");
        leg3.setDestinationCode("ATL");
        leg3.setMarketedCarrier("DL");
        leg3.setMarketedFlightNo(2406);
        leg3.setOperatedCarrier("DL");
        leg3.setOperatedFlightNo(2406);
        leg3.setFareIndex(1);
        leg3.setTransferTypeLeg(true);
        leg3.setTransferType("CONNECTION");
        legs.add(leg3);

        TaxLegVelocityData leg4 = new TaxLegVelocityData();
        leg4.setDepartsDateTime("250608 07:00");
        leg4.setArrivesDateTime("250608 08:00");
        leg4.setLegId(4);
        leg4.setOriginCode("ATL");
        leg4.setDestinationCode("JFK");
        leg4.setMarketedCarrier("DL");
        leg4.setMarketedFlightNo(2420);
        leg4.setOperatedCarrier("DL");
        leg4.setOperatedFlightNo(2420);
        leg4.setFareIndex(1);
        leg1.setTransferTypeLeg(false);
        legs.add(leg4);

        ctx.setLegs(legs);

        //Non-leg data
        ctx.setTripType("ROUND_TRIP");
        ctx.setFareOwningCarrier("DL");
        ctx.setTicketDate("250429");
        ctx.setValidatingCarrier("DL");
    }

}

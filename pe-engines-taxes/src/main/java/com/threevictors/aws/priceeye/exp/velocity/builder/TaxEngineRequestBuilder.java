package com.threevictors.aws.priceeye.exp.velocity.builder;

import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.priceeye.*;
import com.threevictors.aws.priceeye.exp.velocity.data.TaxEngineReqVelocityData;
import com.threevictors.aws.priceeye.exp.velocity.data.TaxLegVelocityData;
import lombok.Data;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

        stubTaxEngineReqVelocityData(ctx, peItinerary);
        String velocityData = runVelocity(ctx, "tax_engine_req.json.vm");
        return velocityData;
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
            //OB legs have a fareIndex of 0
            legData.setFareIndex(0);
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
            //IB legs have a fareIndex of 1
            legData.setFareIndex(1);
            legs.add(legData);
        }

        ctx.setLegs(legs);

        //Non-leg data
        //TODO: What field on the PEItinerary is the TripType? Not able to find it on the legs either
        ctx.setTripType("ROUND_TRIP");
        ctx.setFareOwningCarrier(peItinerary.getOutboundLegs().get(0).getMarketingCarrier());
        //TODO: What field on the PEItinerary is the ticketDate? Not able to find it on the legs either. Should it be today's date?
        //Setting ticketDate to today's date in format yyMMdd
        ctx.setTicketDate(LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")));

        ctx.setValidatingCarrier(peItinerary.getOutboundLegs().get(0).getMarketingCarrier());
        ctx.setCurrency(peItinerary.getCurrency());
        ctx.setTotalPrice(peItinerary.getTotalPrice());
    }

}

package com.threevictors.aws.priceeye.exp.velocity.builder;

import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.velocity.data.PFCEngineReqVelocityData;
import com.threevictors.aws.priceeye.exp.velocity.data.PFCTaxLegVelocityData;
import com.threevictors.aws.priceeye.exp.velocity.data.TaxEngineReqVelocityData;
import com.threevictors.aws.priceeye.exp.velocity.data.TaxLegVelocityData;
import lombok.Data;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class PFCEngineRequestBuilder extends AbstractEngineRequestBuilder<PFCEngineReqVelocityData> {


    public PFCEngineRequestBuilder() {
        super();
    }

    @Override
    public String buildRequest(PEItinerary peItinerary) {
        return buildJSONRequest(peItinerary);
    }

    private String buildJSONRequest(PEItinerary peItinerary) {

        PFCEngineReqVelocityData ctx = new PFCEngineReqVelocityData();

        stubPFCEngineReqVelocityData(ctx, peItinerary);
        String velocityData = runVelocity(ctx, "pfc_engine_req.json.vm");
        return velocityData;
    }

    private PFCTaxLegVelocityData buildLegData( RawLeg leg, boolean lastLeg) {

        PFCTaxLegVelocityData legData = new PFCTaxLegVelocityData();

        legData.setOriginCode(leg.getOriginAirportCode());
        legData.setDestinationCode(leg.getDestinationAirportCode());
        legData.setMarketedCarrier(leg.getMarketingCarrier());
        legData.setMarketedFlightNo(leg.getFlightNumber());
        legData.setOperatedFlightNo(legData.getMarketedFlightNo());

        if ( legData.getOperatedCarrier() == null || legData.getOperatedCarrier().isEmpty() ) {
            legData.setOperatedCarrier(legData.getMarketedCarrier());
        }
        else {
            legData.setOperatedCarrier(leg.getOperatingCarrier());
        }

        return legData;
    }


    private void stubPFCEngineReqVelocityData(PFCEngineReqVelocityData ctx, PEItinerary peItinerary) {

        List<PFCTaxLegVelocityData> legs = new ArrayList<>();

        for (int i = 0; i < peItinerary.getOutboundLegs().size(); i++) {
            RawLeg leg = peItinerary.getOutboundLegs().get(i);
            PFCTaxLegVelocityData legData = buildLegData(leg, i == peItinerary.getOutboundLegs().size() - 1);

            legs.add(legData);
        }

        for (int i = 0; i < peItinerary.getInboundLegs().size(); i++) {
            RawLeg leg = peItinerary.getInboundLegs().get(i);
            boolean lastLeg = i == peItinerary.getInboundLegs().size() - 1;
            PFCTaxLegVelocityData legData = buildLegData(leg, lastLeg );

            legs.add(legData);
        }
        ctx.setLegs(legs);

        //Non-leg data
        //TODO: What field on the PEItinerary is the ticketDate? Not able to find it on the legs either. Should it be today's date?
        //Setting ticketDate to today's date in format yyMMdd
        //ctx.setTicketDate(LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")));

        //TicketDate should be in format yyMMdd. Set to the date when the stubbed itin data was created.
        //Note that this value is obtained from the runtime argument of the main method, which is set on the duration field
        //in the PEItinerary class as there is no other int field to hold the value. So it was set on duration field.
        ctx.setTicketDate(String.valueOf(peItinerary.getDuration()));

        //Note: Use the first leg's marketing carrier as the ticketing carrier. Even with carrier's like KG it should be fine.
        ctx.setTicketingCarrier(peItinerary.getOutboundLegs().get(0).getMarketingCarrier());
    }

}

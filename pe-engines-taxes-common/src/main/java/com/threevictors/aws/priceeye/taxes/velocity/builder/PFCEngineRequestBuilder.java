package com.threevictors.aws.priceeye.taxes.velocity.builder;

import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.taxes.velocity.data.PFCEngineReqVelocityData;
import com.threevictors.aws.priceeye.taxes.velocity.data.PFCTaxLegVelocityData;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PFCEngineRequestBuilder extends AbstractEngineRequestBuilder<PFCEngineReqVelocityData> {

    public PFCEngineRequestBuilder() {
        super();
    }

    @Override
    public String buildRequest(String pointOfSale, PEItinerary peItinerary, int salesDate) {

        PFCEngineReqVelocityData ctx = new PFCEngineReqVelocityData();
        stubPFCEngineReqVelocityData(ctx, pointOfSale, peItinerary);

        //Convert salesDate from yyyyMMdd to yyMMdd by taking last 6 digits as Engines needs it that way
        String formattedSalesDate = String.valueOf(salesDate % 1000000);
        //Set it on ticketDate
        ctx.setTicketDate(formattedSalesDate);

        return runVelocity(ctx, "pfc_engine_req.json.vm");
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


    private void stubPFCEngineReqVelocityData(PFCEngineReqVelocityData ctx, String pointOfSale, PEItinerary peItinerary) {

        List<PFCTaxLegVelocityData> legs = new ArrayList<>();

        // Add null check for outbound legs
        if (peItinerary.getOutboundLegs() != null && !peItinerary.getOutboundLegs().isEmpty()) {
            for (int i = 0; i < peItinerary.getOutboundLegs().size(); i++) {
                RawLeg leg = peItinerary.getOutboundLegs().get(i);
                PFCTaxLegVelocityData legData = buildLegData(leg, i == peItinerary.getOutboundLegs().size() - 1);
                legs.add(legData);
            }

            // Set ticketing carrier only if outbound legs exist
            ctx.setTicketingCarrier(peItinerary.getOutboundLegs().get(0).getMarketingCarrier());
        }

        // Null-safety for one way
        if (peItinerary.getInboundLegs() != null && !peItinerary.getInboundLegs().isEmpty()) {
            for (int i = 0; i < peItinerary.getInboundLegs().size(); i++) {
                RawLeg leg = peItinerary.getInboundLegs().get(i);
                boolean lastLeg = i == peItinerary.getInboundLegs().size() - 1;
                PFCTaxLegVelocityData legData = buildLegData(leg, lastLeg);
                legs.add(legData);
            }
        }

        ctx.setLegs(legs);

        // Non-leg data
        // TODO: What field on the PEItinerary is the ticketDate? Not able to find it on the legs either. Should it be today's date?
        // Setting ticketDate to today's date in format yyMMdd
        // ctx.setTicketDate(LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")));

        // TicketDate should be in format yyMMdd. Set to the date when the stubbed itin data was created.
        // Note that this value is obtained from the runtime argument of the main method, which is set on the duration field
        // in the PEItinerary class as there is no other int field to hold the value. So it was set on duration field.
        ctx.setTicketDate(String.valueOf(peItinerary.getDuration()));
        ctx.setPos(pointOfSale);
        ctx.setCurrency(peItinerary.getCurrency());
    }

}

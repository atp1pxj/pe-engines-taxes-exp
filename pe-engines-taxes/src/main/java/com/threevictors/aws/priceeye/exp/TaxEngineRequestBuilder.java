package com.threevictors.aws.priceeye.exp;

import com.threevictors.aws.data.priceeye.PEExpandedInputRequest;
import com.threevictors.aws.data.priceeye.PEItinerary;
import lombok.Data;

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
        //TODO: Eventually use the peItinerary object values to populate the ctx object
        TaxEngineReqVelocityData ctx = new TaxEngineReqVelocityData();
        stubTaxEngineReqVelocityData(ctx);
        String velocityData = runVelocity(ctx, "tax_engine_req.json.vm");
        return velocityData;
        //return runVelocity(ctx, "tax_engine_req.json.vm");
    }

    //TODO: Temp method. Need to build one based on loops
    private void stubTaxEngineReqVelocityData(TaxEngineReqVelocityData ctx) {
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
        leg1.setTransferType("CONNECTION");
        ctx.setLeg1(leg1);

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
        leg2.setTransferType("STOP_OVER");
        ctx.setLeg2(leg2);

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
        leg3.setTransferType("CONNECTION");
        ctx.setLeg3(leg3);

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
        ctx.setLeg4(leg4);

        //Non-leg data
        ctx.setFareOwningCarrier("DL");
        ctx.setTicketDate("250429");
        ctx.setValidatingCarrier("DL");
    }

}

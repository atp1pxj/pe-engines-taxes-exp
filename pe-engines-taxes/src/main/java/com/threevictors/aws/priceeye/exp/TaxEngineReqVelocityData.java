package com.threevictors.aws.priceeye.exp;

import lombok.Data;

@Data
public class TaxEngineReqVelocityData {

    //TODO
    //For now create 4 legs and see
    //Later change to looping
    private TaxLegVelocityData leg1;
    private TaxLegVelocityData leg2;
    private TaxLegVelocityData leg3;
    private TaxLegVelocityData leg4;

    private String fareOwningCarrier;
    private String ticketDate;
    private String validatingCarrier;
}

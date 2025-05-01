package com.threevictors.aws.priceeye.exp;

import lombok.Data;

@Data
public class TaxLegVelocityData {

    private String departsDateTime;
    private String arrivesDateTime;

    //TODO: this needs to be dyamically determined when determining the order of the leg through looping
    private int legId;

    private String originCode;
    private String destinationCode;
    private String marketedCarrier;
    private int marketedFlightNo;
    private String operatedCarrier;
    private int operatedFlightNo;
    private int fareIndex;
    //TODO: this needs to be dynamically determined when determining the order of the leg through looping
    private String transferType;

    //"${ctx.leg1.arrivesDateTime}"
    //${ctx.leg1.legId}


}

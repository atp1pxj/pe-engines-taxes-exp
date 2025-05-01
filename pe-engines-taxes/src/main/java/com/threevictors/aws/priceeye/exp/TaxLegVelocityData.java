package com.threevictors.aws.priceeye.exp;

import lombok.Data;

@Data
public class TaxLegVelocityData {

    private String departsDateTime;
    private String arrivesDateTime;

    //This will be dynamically determined through looping
    private int legId;

    private String originCode;
    private String destinationCode;
    private String marketedCarrier;
    private int marketedFlightNo;
    private String operatedCarrier;
    private int operatedFlightNo;
    private int fareIndex;
    private boolean isTransferTypeLeg;
    private String transferType;
}

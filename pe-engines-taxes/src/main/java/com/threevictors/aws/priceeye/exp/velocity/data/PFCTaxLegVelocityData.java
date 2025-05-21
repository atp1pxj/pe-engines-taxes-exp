package com.threevictors.aws.priceeye.exp.velocity.data;

import lombok.Data;

@Data
public class PFCTaxLegVelocityData {

    private String originCode;
    private String destinationCode;

    private String marketedCarrier;
    private int marketedFlightNo;
    private String operatedCarrier;
    private int operatedFlightNo;
}

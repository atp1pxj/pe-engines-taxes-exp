package com.threevictors.aws.priceeye.taxes.velocity.data;

import lombok.Data;

import java.util.List;

@Data
public class PFCEngineReqVelocityData {

    private List<PFCTaxLegVelocityData> legs;

    private String ticketDate;
    private String ticketingCarrier;
    private String pos;
    private String currency;

}

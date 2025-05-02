package com.threevictors.aws.priceeye.exp;

import lombok.Data;

import java.util.List;

@Data
public class TaxEngineReqVelocityData {

    private List<TaxLegVelocityData> legs;
    private String fareOwningCarrier;
    private String tripType;//ONE_WAY, ROUND_TRIP

    private String ticketDate;
    private String validatingCarrier;

    private String currency;
    private String fareBasisCode;
    private double totalPrice;


}

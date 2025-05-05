package com.threevictors.aws.priceeye.exp.model;

import lombok.Data;

@Data
public class ChargeDetail {
    public int legId;
    public String taxPoint;
    public String taxKey;
    public Object serviceKey;
    public Object portionId;
    public int taxSequenceNumber;
    public String taxCarrier;
    public double charge;
    public String chargeCurrency;
    public double responseCharge;
    public String responseCurrency;
    public int exchangeRate;
    public String chargeDescription;
    public boolean interlineable;
    public String taxAppliesToTag;
    public Object refundableTaxTag;
}

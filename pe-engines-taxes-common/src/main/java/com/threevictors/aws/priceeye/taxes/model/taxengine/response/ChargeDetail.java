package com.threevictors.aws.priceeye.taxes.model.taxengine.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ChargeDetail {
    public int legId;
    public String taxPoint;
    public String taxKey;
    public Object serviceKey;
    public Object portionId;
    public int taxSequenceNumber;
    public String taxCarrier;
    //public double charge;
    public BigDecimal charge;

    public String chargeCurrency;
    //public double responseCharge;
    public BigDecimal responseCharge;

    public String responseCurrency;
    //public double exchangeRate;
    public BigDecimal exchangeRate;

    public String chargeDescription;
    public boolean interlineable;
    public String taxAppliesToTag;
    public Object refundableTaxTag;
}

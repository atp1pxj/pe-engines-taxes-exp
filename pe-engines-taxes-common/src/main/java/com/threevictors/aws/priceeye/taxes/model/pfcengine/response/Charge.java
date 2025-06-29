package com.threevictors.aws.priceeye.taxes.model.pfcengine.response;

import lombok.Data;

@Data
public class Charge{
    public int chargePoint;
    public String airport;
    public double charge;
    public String chargeCurrency;
    public double responseCharge;
}
package com.threevictors.aws.priceeye.exp.model;

import lombok.Data;

import java.util.ArrayList;

@Data
public class Taxes {
    public String taxGroup;
    public String description;
    public double taxAmount;
    public String taxAmountCurrency;
    public ArrayList<ChargeDetail> chargeDetails;
}

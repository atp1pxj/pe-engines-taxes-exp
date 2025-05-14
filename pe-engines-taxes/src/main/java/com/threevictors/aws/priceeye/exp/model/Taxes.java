package com.threevictors.aws.priceeye.exp.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;

@Data
public class Taxes {
    public String taxGroup;
    public String description;
    public BigDecimal taxAmount;
    public String taxAmountCurrency;
    public ArrayList<ChargeDetail> chargeDetails;
}

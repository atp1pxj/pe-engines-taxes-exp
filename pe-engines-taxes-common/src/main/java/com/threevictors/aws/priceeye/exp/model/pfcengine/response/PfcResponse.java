package com.threevictors.aws.priceeye.exp.model.pfcengine.response;

import lombok.Data;

import java.util.List;

@Data
public class PfcResponse{
    public List<Charge> charges;
    public String responseCurrency;
    public int exchangeRate;
}

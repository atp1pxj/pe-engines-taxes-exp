package com.threevictors.aws.priceeye.taxes.data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class TaxLadder {

    private Map<String, BigDecimal> flatTaxRates;
    private Map<String, BigDecimal> percentageTaxRates;

    //Added to hold onto the percent tax keys for later use to look up maxTaxWhenPercent
    private Map<String, BigDecimal> percentageTaxKeyMap;

    public TaxLadder() {
        flatTaxRates = new TreeMap<>();
        percentageTaxRates = new TreeMap<>();
        percentageTaxKeyMap = new TreeMap<>();
    }

    public boolean isEmpty() {
        return flatTaxRates.isEmpty() && percentageTaxRates.isEmpty();
    }

    public BigDecimal getTotalFlatTaxRate() {
        return flatTaxRates.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getTotalPercentageTaxRate() {
        return percentageTaxRates.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void addFlatTaxRate(String taxCode, BigDecimal taxRate) {
        BigDecimal existing = flatTaxRates.computeIfAbsent(taxCode, k -> new BigDecimal(0));
        flatTaxRates.put(taxCode, existing.add(taxRate));
    }

    public void addPercentageTaxRate(String taxCode, BigDecimal taxRate) {
        BigDecimal existing = percentageTaxRates.computeIfAbsent(taxCode, k -> new BigDecimal(0));
        percentageTaxRates.put(taxCode, existing.add(taxRate));
    }

    public void setPercentageTaxRate(String taxCode, BigDecimal taxRate) {
        percentageTaxRates.put(taxCode, taxRate);
    }

    public Set<String> getFlatTaxCodes() {
        return flatTaxRates.keySet();
    }

    public Set<String> getPercentageTaxCodes() {
        return percentageTaxRates.keySet();
    }

    public BigDecimal getFlatTaxRate(String taxCode) {
        return flatTaxRates.get(taxCode);
    }

    public BigDecimal getPercentageTaxRate(String taxCode) {
        return percentageTaxRates.get(taxCode);
    }

    public BigDecimal getTaxRate(String taxCode) {
        BigDecimal taxRate = flatTaxRates.get(taxCode);
        if (taxRate == null) {
            taxRate = percentageTaxRates.get(taxCode);
        }
        return taxRate;
    }

    //Add getter for percentageTaxKeyMap
    public Map<String, BigDecimal> getPercentageTaxKeyMap() {
        return percentageTaxKeyMap;
    }

}

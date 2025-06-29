package com.threevictors.aws.priceeye.taxes.model.taxengine.response;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class X1TaxRecordDataPoints {
    private String key;
    private String nation;
    private String taxCode;
    private String percentOrFlatTag;
    private int seqNo;
    private String taxCarrier;
    //Note: Don't convert this to BigDecimal. Keep it as double only.
    private Double taxAmount;
    private String taxAmountCurrency;
    private Double taxPercent;
    private Double minTaxWhenPercent;
    private Double maxTaxWhenPercent;
}

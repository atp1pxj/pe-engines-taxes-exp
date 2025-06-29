package com.threevictors.aws.priceeye.taxes.model.taxengine.response;
import lombok.Data;

import java.util.ArrayList;

@Data
public class ExecutionResponse {
    public ArrayList<String> summary;
    public ArrayList<Taxes> taxes;
    public Diagnostics diagnostics;
    public Object dateRange;
}

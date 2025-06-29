package com.threevictors.aws.priceeye.taxes.model.taxengine.response;

import lombok.Data;

import java.util.ArrayList;

@Data
public class Diagnostics {
    public int executionTimeMs;
    public ArrayList<String> exclusionTrace;
}


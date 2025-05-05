package com.threevictors.aws.priceeye.exp.model;

import lombok.Data;

import java.util.ArrayList;

@Data
public class Diagnostics {
    public int executionTimeMs;
    public ArrayList<String> exclusionTrace;
}


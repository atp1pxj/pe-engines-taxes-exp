package com.threevictors.aws.priceeye.exp.model;

import lombok.Data;

import java.util.ArrayList;

@Data
public class RootResponse {
    public ArrayList<ExecutionResponse> executionResponses;
}

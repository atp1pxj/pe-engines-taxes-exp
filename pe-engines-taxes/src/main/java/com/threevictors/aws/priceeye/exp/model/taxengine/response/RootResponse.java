package com.threevictors.aws.priceeye.exp.model.taxengine.response;

import lombok.Data;

import java.util.ArrayList;

@Data
public class RootResponse {
    public ArrayList<ExecutionResponse> executionResponses;
}

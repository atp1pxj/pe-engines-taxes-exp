package com.threevictors.aws.priceeye.taxes.model.taxengine.response;

import lombok.Data;

import java.util.ArrayList;

@Data
public class RootResponse {
    public ArrayList<ExecutionResponse> executionResponses;
}

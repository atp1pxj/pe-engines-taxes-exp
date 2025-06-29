package com.threevictors.aws.priceeye.taxes.fn;

import com.threevictors.aws.data.priceeye.PEItinerary;

@FunctionalInterface
public interface PEItineraryProcessor {
    void process(PEItinerary itinerary, int salesDate);
}

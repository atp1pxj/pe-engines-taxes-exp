package com.threevictors.aws.priceeye.exp.loader;

import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.dates.DateTime;
import com.threevictors.aws.data.priceeye.PECommonOutput;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.dao.MetadataReader;
import com.threevictors.aws.priceeye.exp.dao.RedshiftCommonOutputReader;
import com.threevictors.aws.priceeye.exp.utils.CommonOutputConverter;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loader class responsible for loading Price Eye itineraries from Common Output data stored in Redshift.
 */
@Data
public class CommonOutputPEItinsLoader implements Serializable {

    private RedshiftCommonOutputReader redshiftCommonOutputReader;
    private MetadataReader metadataReader;
    private Map<String, String> airportToTimezoneMap;
    private CommonOutputConverter commonOutputConverter;


    public CommonOutputPEItinsLoader() {

        metadataReader = new MetadataReader();
        airportToTimezoneMap = metadataReader.getAirportToTimezoneMap();
        metadataReader.shutdown();

        redshiftCommonOutputReader = new RedshiftCommonOutputReader();
        commonOutputConverter = new CommonOutputConverter();
    }

    public List<PEItinerary> loadPEItins(int salesDate, String customer, int limit ) {

        List<PEItinerary> peItineraries = new ArrayList<>();

        List<PECommonOutput> commonOutputList = redshiftCommonOutputReader.getCommonOutput(salesDate, customer, "", limit);

        if(commonOutputList != null && !commonOutputList.isEmpty()) {
            commonOutputList.parallelStream().forEach(peCommonOutput -> {
                PEItinerary peItinerary = commonOutputConverter.convertCommonOutputToPeItinerary(peCommonOutput, airportToTimezoneMap);
                peItineraries.add(peItinerary);
            });
        }
        return peItineraries;
    }
}

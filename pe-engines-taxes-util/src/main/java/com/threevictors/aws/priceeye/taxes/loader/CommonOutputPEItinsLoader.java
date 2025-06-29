package com.threevictors.aws.priceeye.taxes.loader;

import com.threevictors.aws.data.priceeye.PECommonOutput;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.taxes.dao.RedshiftCommonOutputReader;
import com.threevictors.aws.priceeye.taxes.utils.CommonOutputConverter;
import com.threevictors.common.database.dao.aurora.metadata.AuroraMetadataReader;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Loader class responsible for loading Price Eye itineraries from Common Output data stored in Redshift.
 */
@Data
public class CommonOutputPEItinsLoader implements Serializable {

    private RedshiftCommonOutputReader redshiftCommonOutputReader;
    private AuroraMetadataReader metadataReader;
    private Map<String, String> airportToTimezoneMap;
    private CommonOutputConverter commonOutputConverter;


    public CommonOutputPEItinsLoader() {

        metadataReader = new AuroraMetadataReader();
        metadataReader.initialize(1);
        airportToTimezoneMap = metadataReader.getAirportToTimezoneMap();
        metadataReader.shutdown();

        redshiftCommonOutputReader = new RedshiftCommonOutputReader();
        commonOutputConverter = new CommonOutputConverter();
    }

    public List<PEItinerary> loadPEItins(int salesDate, String customer, String schemaSuffix, int limit ) {

        List<PEItinerary> peItineraries = new ArrayList<>();
        List<PECommonOutput> commonOutputList = redshiftCommonOutputReader.getCommonOutput(salesDate, customer, schemaSuffix, limit);

        if(commonOutputList != null && !commonOutputList.isEmpty()) {
            commonOutputList.parallelStream().forEach(peCommonOutput -> {
                PEItinerary peItinerary = commonOutputConverter.convertCommonOutputToPeItinerary(peCommonOutput, airportToTimezoneMap);
                peItineraries.add(peItinerary);
            });
        }
        return peItineraries;
    }



    public void streamPEItins(int salesDate, String customer, String schemaSuffix, int limit,
                              Consumer<PEItinerary> processor) {

        redshiftCommonOutputReader.streamCommonOutput(salesDate, customer, schemaSuffix, limit,
                peCommonOutput -> {
                    PEItinerary peItinerary = commonOutputConverter.convertCommonOutputToPeItinerary(
                            peCommonOutput, airportToTimezoneMap);
                    processor.accept(peItinerary);
                });
    }
}
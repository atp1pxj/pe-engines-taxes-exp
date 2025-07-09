package com.threevictors.aws.priceeye.taxes.loader;

import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.data.priceeye.Pair;
import com.threevictors.aws.priceeye.taxes.dao.RedshiftCommonOutputReader;
import com.threevictors.aws.priceeye.taxes.utils.CommonOutputConverter;
import com.threevictors.common.database.dao.aurora.metadata.AuroraMetadataReader;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Loader class responsible for loading Price Eye itineraries from Common Output data stored in Redshift.
 */
@Data
public class CommonOutputPEItinsLoader implements Serializable {

    private RedshiftCommonOutputReader redshiftCommonOutputReader;
    private CommonOutputConverter commonOutputConverter;


    public CommonOutputPEItinsLoader() {

        AuroraMetadataReader metadataReader = new AuroraMetadataReader();
        metadataReader.initialize(1);
        Map<String, String> airportToTimezoneMap = metadataReader.getAirportToTimezoneMap();
        metadataReader.shutdown();

        redshiftCommonOutputReader = new RedshiftCommonOutputReader();
        commonOutputConverter = new CommonOutputConverter( airportToTimezoneMap);
    }


    public void streamPEItins(int salesDate, String customer, String pos, String schemaSuffix, int limit,
                              Consumer<Pair<String, PEItinerary>> processor) {

        redshiftCommonOutputReader.streamCommonOutput(salesDate, customer, pos, schemaSuffix, limit,
                peCommonOutput -> {
                    Pair<String, PEItinerary> peItinerary = commonOutputConverter.convertCommonOutputToPeItinerary( peCommonOutput );

                    if ( peItinerary != null) processor.accept(peItinerary);
                });
    }
}

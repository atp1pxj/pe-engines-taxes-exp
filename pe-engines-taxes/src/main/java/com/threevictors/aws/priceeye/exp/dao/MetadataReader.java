package com.threevictors.aws.priceeye.exp.dao;

import com.threevictors.aws.data.dates.DateTime;
import com.threevictors.common.database.dao.common.exceptions.DatabaseReaderException;
import lombok.Data;
import com.threevictors.common.database.dao.aurora.metadata.AuroraMetadataReader;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

@Data
public class MetadataReader extends AuroraMetadataReader {


    public MetadataReader() {
        super();
        initialize(1);
    }

    public Map<String, String> getAirportCountryMapUSDomesticOnly() {
        TreeMap airportToCountryMap = new TreeMap();
        String query = "select a.airportCode, c.countryCode from airportlocation a join citylocation c on a.cityCode = c.cityCode WHERE c.countryCode IN ('US', 'PR', 'VI');";

        try {
            try (
                    Connection connection = this.getConnection();
                    Statement stmt = connection.createStatement();
            ) {
                ResultSet resultSet = stmt.executeQuery(query);

                while(resultSet.next()) {
                    String airportCode = resultSet.getString(1);
                    String countryCode = resultSet.getString(2);
                    airportToCountryMap.put(airportCode, countryCode);
                }
            }

            return airportToCountryMap;
        } catch (Exception e) {
            System.out.println("Exception while building airport country map: query=" + query);
            throw new DatabaseReaderException("Error building airport country map: query=" + query, e);
        }
    }

}

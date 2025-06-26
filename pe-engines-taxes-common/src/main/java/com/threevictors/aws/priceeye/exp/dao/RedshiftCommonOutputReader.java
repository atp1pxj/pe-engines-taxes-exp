package com.threevictors.aws.priceeye.exp.dao;


import com.threevictors.aws.data.priceeye.PECommonOutput;
import com.threevictors.common.database.dao.common.DatabaseReader;
import com.threevictors.common.database.dao.common.exceptions.DatabaseReaderException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class RedshiftCommonOutputReader extends DatabaseReader {

    private static final Logger log = LogManager.getLogger(RedshiftCommonOutputReader.class);

    @Override
    protected String getDefaultPropertiesFilename() {
        return "database-redshift-common-output-reader-tax-engine";
    }

    public RedshiftCommonOutputReader() {
        super();
        initialize(1);
    }

    public List<PECommonOutput> getCommonOutput(int salesDate, String customer, String schemaSuffix, int limit ) {
        List<PECommonOutput> commonOutputList = new ArrayList<>();

        //Note: customer_collection_name column not present anymore in the table
        String query = "select feed, observation_date, observation_time, reference, source, pos, origin, destination, outbound_gcm, los, outbound_travel_stop_over, " +
                "inbound_travel_stop_over, carrier, dominant_marketing_carrier, outbound_dominant_marketing_carrier, outbound_marketing_carrier_list, " +
                "inbound_dominant_marketing_carrier, inbound_marketing_carrier_list, outbound_operating_carrier_list, inbound_operating_carrier_list, " +
                "outbound_dominant_segment, inbound_dominant_segment, outbound_codeshare, inbound_codeshare, outbound_codeshare_carrier, inbound_codeshare_carrier, " +
                "outbound_flight_no, outbound_departure_date, outbound_departure_time, outbound_arrival_date, outbound_arrival_time, outbound_flight_duration, " +
                "outbound_flights_seats, outbound_effective_seats, inbound_flight_no, inbound_departure_date, inbound_departure_time, inbound_arrival_date, " +
                "inbound_arrival_time, inbound_flight_duration, inbound_flights_seats, inbound_effective_seats, search_class, cabin, outbound_cabins, " +
                "inbound_cabins, outbound_fare_family, outbound_booking_class, outbound_fare_basis, inbound_fare_family, inbound_booking_class, inbound_fare_basis, " +
                "price_exc, tax, yqyr, q_surcharge, price_inc, currency, preferred_currency_rate, price_outbound, price_inbound, is_tax_inc_outin, outbound_available_seats, " +
                "inbound_available_seats, substitute_site, price_exc_no_gds, price_inc_no_gds, tax_no_gds, price_outbound_no_gds, price_inbound_no_gds, requestid, " +
                "refundable, change_fee, channel, outbound_total_flight_duration, inbound_total_flight_duration, sales_date, customer " +
                "from common_output"+schemaSuffix+".common_output_format where sales_date=" + salesDate;


        if ( customer != null && !"*".equals( customer ) ) {
            query = query + " and customer='" + customer + "'";
        }

        if ( limit > 0 ) {
            query = query + " limit " + limit;
        }

        try (Connection connection = getConnection(); Statement statement = connection.createStatement() ) {
            ResultSet rs = statement.executeQuery( query );
            while ( rs.next() ) {
                PECommonOutput commonOutput = parseCommonOutput( rs );
                if ( commonOutput != null ) {
                    commonOutputList.add( commonOutput );
                }
            }
        }
        catch ( Exception e ) {
            String message = "COMMON OUTPUT: error reading common output for sales date=" + salesDate + ", query=" + query;
            log.error( message, e );
            throw new DatabaseReaderException( message, e );
        }

        return commonOutputList;
    }

    private PECommonOutput parseCommonOutput( ResultSet rs ) {
        PECommonOutput record = new PECommonOutput();

        int i=0;

        try {
            record.setFeed(                                 rs.getString( ++i ) );
            record.setObservation_date(                     rs.getString( ++i ) );
            record.setObservation_time(                     rs.getString( ++i ) );
            record.setReference(                            rs.getString(   ++i ) );
            record.setSource(                               rs.getString( ++i ) );
            record.setPos(                                  rs.getString( ++i ) );
            record.setOrigin(                               rs.getString( ++i ) );
            record.setDestination(                          rs.getString( ++i ) );
            record.setOutbound_gcm(                         rs.getInt( ++i ) );
            record.setLos(                                  rs.getInt( ++i ) );
            record.setOutbound_travel_stop_over(            rs.getString( ++i ) );
            record.setInbound_travel_stop_over(             rs.getString( ++i ) );
            record.setCarrier(                              rs.getString( ++i ) );
            record.setDominant_marketing_carrier(           rs.getString( ++i ) );
            record.setOutbound_dominant_marketing_carrier(  rs.getString( ++i ) );
            record.setOutbound_marketing_carrier_list(      rs.getString( ++i ) );
            record.setInbound_dominant_marketing_carrier(   rs.getString( ++i ) );
            record.setInbound_marketing_carrier_list(       rs.getString( ++i ) );
            record.setOutbound_operating_carrier_list(      rs.getString( ++i ) );
            record.setInbound_operating_carrier_list(       rs.getString( ++i ) );
            record.setOutbound_dominant_segment(            rs.getInt( ++i ) );
            record.setInbound_dominant_segment(             rs.getInt( ++i ) );
            record.setOutbound_codeshare(                   rs.getString( ++i ) );
            record.setInbound_codeshare(                   rs.getString( ++i ) );
            record.setOutbound_codeshare_carrier(           rs.getString( ++i ) );
            record.setInbound_codeshare_carrier(            rs.getString( ++i ) );
            record.setOutbound_flight_no(                   rs.getString( ++i ) );
            record.setOutbound_departure_date(              rs.getString( ++i ) );
            record.setOutbound_departure_time(              rs.getString( ++i ) );
            record.setOutbound_arrival_date(                rs.getString( ++i ) );
            record.setOutbound_arrival_time(                rs.getString( ++i ) );
            record.setOutbound_flight_duration(             rs.getString( ++i ) );
            record.setOutbound_flights_seats(               rs.getString( ++i ) );
            record.setOutbound_effective_seats(             rs.getInt( ++i ) );
            record.setInbound_flight_no(                    rs.getString( ++i ) );
            record.setInbound_departure_date(               rs.getString( ++i ) );
            record.setInbound_departure_time(               rs.getString( ++i ) );
            record.setInbound_arrival_date(                 rs.getString( ++i ) );
            record.setInbound_arrival_time(                 rs.getString( ++i ) );
            record.setInbound_flight_duration(              rs.getString( ++i ) );
            record.setInbound_flights_seats(                rs.getString( ++i ) );
            record.setInbound_effective_seats(              rs.getInt( ++i ) );
            record.setSearch_class(                         rs.getString( ++i ) );
            record.setCabin(                                rs.getString( ++i ) );
            record.setOutbound_cabins(                      rs.getString( ++i ) );
            record.setInbound_cabins(                       rs.getString( ++i ) );
            record.setOutbound_fare_family(                 rs.getString( ++i ) );
            record.setOutbound_booking_class(               rs.getString( ++i ) );
            record.setOutbound_fare_basis(                  rs.getString( ++i ) );
            record.setInbound_fare_family(                  rs.getString( ++i ) );
            record.setInbound_booking_class(                rs.getString( ++i ) );
            record.setInbound_fare_basis(                   rs.getString( ++i ) );
            record.setPrice_exc(                            rs.getDouble( ++i ) );
            record.setTax(                                  rs.getDouble( ++i ) );
            record.setYqyr(                                 rs.getDouble( ++i ) );
            record.setQ_surcharge(                          rs.getDouble( ++i ) );
            record.setPrice_inc(                            rs.getDouble( ++i ) );
            record.setCurrency(                             rs.getString( ++i ) );
            record.setPreferred_currency_rate(              rs.getDouble( ++i ) );
            record.setPrice_outbound(                       rs.getDouble( ++i ) );
            record.setPrice_inbound(                        rs.getDouble( ++i ) );
            record.setIs_tax_inc_outin(                     rs.getInt( ++i ) );
            record.setOutbound_available_seats(             rs.getString( ++i ) );
            record.setInbound_available_seats(              rs.getString( ++i ) );
            record.setSubstitute_site(                      rs.getInt( ++i ) );
            record.setPrice_exc_no_gds(                     rs.getDouble( ++i ) );
            record.setPrice_inc_no_gds(                     rs.getDouble( ++i ) );
            record.setTax_no_gds(                           rs.getDouble( ++i ) );
            record.setPrice_outbound_no_gds(                rs.getDouble( ++i ) );
            record.setPrice_inbound_no_gds(                 rs.getDouble( ++i ) );
            record.setRequestId(                            rs.getLong( ++i ) );
            record.setRefundable(                           rs.getBoolean( ++i ) );
            record.setChange_fee(                           rs.getDouble( ++i ) );
            record.setChannel(                              rs.getString( ++i ) );
            record.setOutbound_total_flight_duration(       rs.getInt( ++i ) );
            record.setInbound_total_flight_duration(        rs.getInt( ++i ) );
            record.setSales_date(                           rs.getString( ++i ) );
            record.setCustomer(                             rs.getString( ++i ) );

        }
        catch ( Exception e ) {
            log.warn( "Error parsing common output row, skipping...", e );
            return null;
        }

        return record;
    }

    /**
     * Main method added to test the RedshiftCommonOutputReader
     * Fetches PECommonOutput data with the specified parameters and prints the results
     */
    public static void main(String[] args) {
        try {
            // Create an instance of RedshiftCommonOutputReader
            RedshiftCommonOutputReader reader = new RedshiftCommonOutputReader();

            // Call getCommonOutput with the specified parameters
            int salesDate = 20250626;
            String customer = "AA";
            String schemaSuffix = "";
            int limit = 10;

            System.out.println("Fetching common output data with parameters:");
            System.out.println("Sales Date: " + salesDate);
            System.out.println("Customer: " + customer);
            System.out.println("Schema Suffix: " + schemaSuffix);
            System.out.println("Limit: " + limit);

            List<PECommonOutput> results = reader.getCommonOutput(salesDate, customer, schemaSuffix, limit);

            // Print the results
            System.out.println("\nResults found: " + results.size());

            for (int i = 0; i < results.size(); i++) {
                PECommonOutput output = results.get(i);
                System.out.println("\nResult #" + (i + 1) + ":");
                System.out.println("  Feed: " + output.getFeed());
                System.out.println("  Reference: " + output.getReference());
                System.out.println("  Origin: " + output.getOrigin());
                System.out.println("  Destination: " + output.getDestination());
                System.out.println("  Carrier: " + output.getCarrier());
                System.out.println("  Price: " + output.getPrice_inc() + " " + output.getCurrency());
                System.out.println("  Sales Date: " + output.getSales_date());
                System.out.println("  Customer: " + output.getCustomer());
            }
        } catch (Exception e) {
            System.err.println("Error executing test: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

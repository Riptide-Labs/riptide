package org.riptide.repository.postgres.jdbc;

public interface PostgresQueries {
    String QUERY_DROP_TABLE_BUCKETS = "drop table if exists buckets";
    String QUERY_CREATE_TABLE_BUCKETS = """
            create table buckets as
                        SELECT
                            CAST (NULL AS TIMESTAMP) AS bucket_time,
                            CAST (NULL AS BIGINT) AS bucket_bytes,
                            CAST (NULL AS BIGINT) AS bucket_packets,
                            *
                        FROM flows
                        WITH NO DATA;
            """;
    String QUERY_DROP_TABLE_FLOWS = "drop table if exists flows";
    String QUERY_CREATE_TABLE_FLOWS = """
            create table flows (
                received_at timestamp without time zone,
                timestamp timestamp without time zone,
                bytes bigint,
                direction character varying(100),
                dst_addr inet,
                dst_addr_hostname character varying(255),
                dst_mask_length integer,
                dst_port integer,
                engine_id integer,
                engine_type integer,
                delta_switched timestamp without time zone,
                first_switched timestamp without time zone,
                flow_records integer,
                flow_sequence_number bigint,
                input_snmp integer,
                ip_protocol_version integer,
                last_switched timestamp without time zone,
                next_hop inet,
                output_snmp integer,
                packets bigint,
                protocol integer,
                sampling_algorithm character varying(100),
                src_addr inet,
                src_addr_hostname character varying(255),
                src_as bigint,
                src_mask_length integer,
                src_port integer,
                tcp_flags integer,
                tos integer,
                flow_protocol character varying(100),
                vlan integer,
                application character varying(255),
                exporter_addr character varying(255),
                location character varying(255),
                src_locality character varying(100),
                dst_locality character varying(100),
                flow_locality character varying(100),
                clock_correction_ms bigint,
                input_snmp_ifname character varying(255),
                output_snmp_ifname character varying(255)
              );
            """;
    String QUERY_INSERT_FLOWS = """
                INSERT INTO flows (
                                   received_at, timestamp, bytes, direction,
                                   dst_addr, dst_addr_hostname, dst_mask_length, dst_port, engine_id, engine_type,
                                   delta_switched, first_switched, flow_records, flow_sequence_number,
                                   input_snmp, ip_protocol_version, last_switched, next_hop,
                                   output_snmp, packets, protocol, sampling_algorithm,
                                   src_addr, src_addr_hostname, src_as, src_mask_length,
                                   src_port, tcp_flags, tos, flow_protocol,
                                   vlan, application, exporter_addr, location,
                                   src_locality, dst_locality, flow_locality, clock_correction_ms,
                                   input_snmp_ifname, output_snmp_ifname
                               ) 
                VALUES (?, ?, ?, ?, ?::INET, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?::INET,
                        ?, ?, ?, ?, ?::INET, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?,?, ?
                        )
            """;
    String QUERY_INSERT_BUCKETS = """
                INSERT INTO buckets (
                                   bucket_time, bucket_bytes, bucket_packets,
                                   received_at, timestamp, bytes, direction,
                                   dst_addr, dst_addr_hostname, dst_mask_length, dst_port, engine_id, engine_type,
                                   delta_switched, first_switched, flow_records, flow_sequence_number,
                                   input_snmp, ip_protocol_version, last_switched, next_hop,
                                   output_snmp, packets, protocol, sampling_algorithm,
                                   src_addr, src_addr_hostname, src_as, src_mask_length,
                                   src_port, tcp_flags, tos, flow_protocol,
                                   vlan, application, exporter_addr, location,
                                   src_locality, dst_locality, flow_locality, clock_correction_ms,
                                   input_snmp_ifname, output_snmp_ifname
                               ) 
                VALUES (?, ?, ?,
                        ?, ?, ?, ?, ?::INET, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?::INET,
                        ?, ?, ?, ?, ?::INET, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?,?, ?)
            """;
}

package com.astrawms.sapadapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param logicalSystem SAP logical system (SID_client), used as sourceSystem on translated messages
 * @param gateway       {@code mock} (simulated SAP backend) or {@code jco} (real RFC connection, future release)
 */
@ConfigurationProperties("astra.sap")
public record SapProperties(@DefaultValue("SAP_S4_DEV_100") String logicalSystem, @DefaultValue("mock") String gateway) {
}

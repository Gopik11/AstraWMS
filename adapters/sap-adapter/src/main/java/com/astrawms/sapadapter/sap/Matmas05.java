package com.astrawms.sapadapter.sap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * JSON form of the MATMAS05 IDoc (material master, ALE distribution from SAP MM), field by field as the middleware
 * converts IDoc-XML. Only the segments and fields AstraWMS uses are modelled (ADR-0022): E1MARAM general data,
 * E1MAKTM descriptions, E1MARMM units of measure with EANs and dimensions, E1MARCM plant data, E1MBEWM valuation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Matmas05(
        @JsonProperty("DOCNUM") String docnum,
        @JsonProperty("MESTYP") String mestyp,
        @JsonProperty("E1MARAM") E1maram e1maram) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1maram(
            @JsonProperty("MATNR") String matnr,
            @JsonProperty("MTART") String mtart,
            @JsonProperty("MEINS") String meins,
            @JsonProperty("LVORM") String lvorm,                // deletion flag at client level
            @JsonProperty("MSTAE") String mstae,                // cross-plant material status
            @JsonProperty("XCHPF") String xchpf,                // batch management
            @JsonProperty("MHDHB") Integer mhdhb,               // total shelf life (days)
            @JsonProperty("MHDRZ") Integer mhdrz,               // minimum remaining shelf life (days)
            @JsonProperty("STOFF") String stoff,                // hazardous material number
            @JsonProperty("TEMPB") String tempb,                // temperature conditions indicator
            @JsonProperty("E1MAKTM") List<E1maktm> e1maktm,
            @JsonProperty("E1MARMM") List<E1marmm> e1marmm,
            @JsonProperty("E1MARCM") List<E1marcm> e1marcm,
            @JsonProperty("E1MBEWM") List<E1mbewm> e1mbewm) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1maktm(@JsonProperty("SPRAS_ISO") String sprasIso, @JsonProperty("SPRAS") String spras,
                          @JsonProperty("MAKTX") String maktx) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1marmm(@JsonProperty("MEINH") String meinh, @JsonProperty("UMREZ") Integer umrez,
                          @JsonProperty("UMREN") Integer umren, @JsonProperty("EAN11") String ean11,
                          @JsonProperty("LAENG") BigDecimal laeng, @JsonProperty("BREIT") BigDecimal breit,
                          @JsonProperty("HOEHE") BigDecimal hoehe, @JsonProperty("MEABM") String meabm,
                          @JsonProperty("BRGEW") BigDecimal brgew, @JsonProperty("GEWEI") String gewei) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1marcm(@JsonProperty("WERKS") String werks, @JsonProperty("XCHPF") String xchpf,
                          @JsonProperty("SERNP") String sernp, @JsonProperty("MMSTA") String mmsta,
                          @JsonProperty("LVORM") String lvorm) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1mbewm(@JsonProperty("BWKEY") String bwkey, @JsonProperty("STPRS") BigDecimal stprs,
                          @JsonProperty("VERPR") BigDecimal verpr, @JsonProperty("VPRSV") String vprsv,
                          @JsonProperty("PEINH") BigDecimal peinh) {
    }
}

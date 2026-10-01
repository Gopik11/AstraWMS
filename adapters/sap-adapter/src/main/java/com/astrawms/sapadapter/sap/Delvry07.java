package com.astrawms.sapadapter.sap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * JSON form of the DELVRY07 IDoc as delivered by the middleware (IDoc-XML converted field by field). Only the
 * segments and fields used by ISD IF-IB-001 are modelled; others are ignored. Field names are SAP's, so the
 * mapping in {@code DelvryMapper} can be read against the ISD table directly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Delvry07(
        @JsonProperty("DOCNUM") String docnum,
        @JsonProperty("MESTYP") String mestyp,
        @JsonProperty("E1EDL20") E1edl20 e1edl20,
        @JsonProperty("E1EDL18") List<E1edl18> e1edl18,
        @JsonProperty("E1ADRM1") List<E1adrm1> e1adrm1,
        @JsonProperty("E1EDT13") List<E1edt13> e1edt13,
        @JsonProperty("E1EDL24") List<E1edl24> e1edl24,
        @JsonProperty("E1EDL37") List<E1edl37> e1edl37) {

    public static final String SAVE_REPLICA = "SHP_IBDLV_SAVE_REPLICA";
    public static final String CHANGE = "SHP_IBDLV_CHANGE";

    /** Delivery header. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1edl20(@JsonProperty("VBELN") String vbeln, @JsonProperty("LFART") String lfart,
                          @JsonProperty("LIFEX") String lifex, @JsonProperty("BOLNR") String bolnr,
                          @JsonProperty("TRAID") String traid, @JsonProperty("WERKS") String werks) {
    }

    /** Control segment; QUALF = DEL marks a deletion. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1edl18(@JsonProperty("QUALF") String qualf) {
    }

    /** Partner: PARTNER_Q LF = vendor, SP = carrier. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1adrm1(@JsonProperty("PARTNER_Q") String partnerQ, @JsonProperty("PARTNER_ID") String partnerId) {
    }

    /** Deadline: QUALF 007 = delivery date; NTANF yyyyMMdd, NTANZ HHmmss in plant local time. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1edt13(@JsonProperty("QUALF") String qualf, @JsonProperty("NTANF") String ntanf,
                          @JsonProperty("NTANZ") String ntanz) {
    }

    /** Delivery item. INSMK: ' ' unrestricted, X quality inspection, S blocked. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1edl24(@JsonProperty("POSNR") String posnr, @JsonProperty("MATNR") String matnr,
                          @JsonProperty("WERKS") String werks, @JsonProperty("LFIMG") String lfimg,
                          @JsonProperty("VRKME") String vrkme, @JsonProperty("CHARG") String charg,
                          @JsonProperty("LICHN") String lichn, @JsonProperty("VGBEL") String vgbel,
                          @JsonProperty("VGPOS") String vgpos, @JsonProperty("INSMK") String insmk,
                          @JsonProperty("UEBTO") String uebto, @JsonProperty("UNTTO") String untto) {
    }

    /** Handling unit (EXIDV = SSCC) with contents E1EDL44. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1edl37(@JsonProperty("EXIDV") String exidv, @JsonProperty("VHILM") String vhilm,
                          @JsonProperty("E1EDL44") List<E1edl44> e1edl44) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record E1edl44(@JsonProperty("POSNR") String posnr, @JsonProperty("VEMNG") String vemng,
                          @JsonProperty("VEMEH") String vemeh, @JsonProperty("CHARG") String charg) {
    }
}

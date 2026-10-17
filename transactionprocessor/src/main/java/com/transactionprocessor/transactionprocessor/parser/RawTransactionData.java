package com.transactionprocessor.transactionprocessor.parser;

import java.util.LinkedHashMap;
import java.util.Map;

public class RawTransactionData {
    private String sourceTransactionId;
    private String rawDate;
    private String rawDescription;
    private String rawAmount;
    private String rawCurrency;
    private Map<String, Object> payload = new LinkedHashMap<>();
    private String rawDataHashInput; // for stable dedup

    public String getSourceTransactionId() { return sourceTransactionId; }
    public void setSourceTransactionId(String v) { this.sourceTransactionId = v; }
    public String getRawDate() { return rawDate; }
    public void setRawDate(String v) { this.rawDate = v; }
    public String getRawDescription() { return rawDescription; }
    public void setRawDescription(String v) { this.rawDescription = v; }
    public String getRawAmount() { return rawAmount; }
    public void setRawAmount(String v) { this.rawAmount = v; }
    public String getRawCurrency() { return rawCurrency; }
    public void setRawCurrency(String v) { this.rawCurrency = v; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> v) { this.payload = v; }
    public String getRawDataHashInput() { return rawDataHashInput; }
    public void setRawDataHashInput(String v) { this.rawDataHashInput = v; }
}

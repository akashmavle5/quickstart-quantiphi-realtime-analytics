package com.amazonaws.streamingeventmodel;

public interface StreamingStatusDetail {
    String getVoiceConnectorId();
    String getTransactionId();
    String getCallId();
    StreamingStatus getStreamingStatus();
    Direction getDirection();
    String getVersion();
    String getStreamArn();
    String getFromNumber();
    String getToNumber();
    //String getSQSurl();
    Boolean getIsCaller();
}
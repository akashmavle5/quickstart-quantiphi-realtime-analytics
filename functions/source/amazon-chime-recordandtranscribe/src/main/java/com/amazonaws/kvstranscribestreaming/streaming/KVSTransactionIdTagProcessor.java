package com.amazonaws.kvstranscribestreaming.streaming;

import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadata;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.MkvTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Responsible to ensure that reading from KVS is done until transactionId changes.
 */
public class KVSTransactionIdTagProcessor implements FragmentMetadataVisitor.MkvTagProcessor {

    private static final Logger log = LoggerFactory.getLogger(KVSTransactionIdTagProcessor.class);

    private final String transactionId;
    private boolean sameTransactionId;

    public KVSTransactionIdTagProcessor(String transactionId) {
        this.transactionId = transactionId;
        sameTransactionId = true;
    }

    public void process(MkvTag mkvTag, Optional<FragmentMetadata> currentFragmentMetadata) {
        if ("TransactionId".equals(mkvTag.getTagName())) {
            if (this.transactionId.equals(mkvTag.getTagValue())) {
                sameTransactionId = true;
            }
            else {
                log.info("TransactionId Id in tag does not match expected, will stop streaming. "
                        + "transactionId actual:" + mkvTag.getTagValue() + " expected: {}" + transactionId);
                sameTransactionId = false;
            }
        }
    }

    public boolean shouldStopProcessing() {
        return sameTransactionId == false;
    }
}
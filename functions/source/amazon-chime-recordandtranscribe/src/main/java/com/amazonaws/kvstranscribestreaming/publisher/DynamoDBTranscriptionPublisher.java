package com.amazonaws.kvstranscribestreaming.publisher;

import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.streamingeventmodel.StreamingStatusDetail;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.transcribestreaming.model.Result;
import software.amazon.awssdk.services.transcribestreaming.model.TranscriptEvent;
import com.google.gson.Gson;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.List;

import static com.amazonaws.kvstranscribestreaming.constants.TranscribeDDBConstants.*;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
//import software.amazon.awssdk.services.sqs.SqsClient;
//import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;


import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.AmazonSQSException;
//import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.SendMessageRequest;

import software.amazon.awssdk.core.sync.RequestBody;
/**
 * TranscribedSegmentWriter writes the transcript segments to DynamoDB
 */
public class DynamoDBTranscriptionPublisher implements TranscriptionPublisher {
    private final String transactionId;
    private final String callId;
    private String speakerLabel;
    private final DynamoDB ddbClient;
    private final Boolean consoleLogTranscriptFlag;
    private final Boolean isCaller;
    private final String SQS_URL;
    private final AmazonSQS SqsClient;
    private static String  TABLE_TRANSCRIPT; //    = "TranscriptSegment";
    private static final Logger logger = LoggerFactory.getLogger(DynamoDBTranscriptionPublisher.class);

    public DynamoDBTranscriptionPublisher(StreamingStatusDetail streamingStatusStartedDetail, DynamoDB ddbClient, Boolean consoleLogTranscriptFlag, String TABLE_TRANSCRIPT, String SQS_URL, AmazonSQS SqsClient) {
        this.transactionId = Validate.notNull(streamingStatusStartedDetail.getTransactionId());
        this.callId = streamingStatusStartedDetail.getCallId();
        this.ddbClient = Validate.notNull(ddbClient);
        this.consoleLogTranscriptFlag = Validate.notNull(consoleLogTranscriptFlag);
        this.isCaller = streamingStatusStartedDetail.getIsCaller();
        //this.SQSURL = streamingStatusStartedDetail.getSQSurl();
        this.TABLE_TRANSCRIPT = TABLE_TRANSCRIPT;
        this.SQS_URL = SQS_URL;
        this.SqsClient = Validate.notNull(SqsClient);
        //this.
        // initialize it to null so it's set on the first write
        // TODO:  this is a race condition nightmare so use the leg attribute once it's available
        this.speakerLabel = null;
    }
    
    @Override
    public void publish(TranscriptEvent transcriptEvent) {
        List<Result> results = transcriptEvent.transcript().results();
        if (results.size() > 0) {

            Result result = results.get(0);

            // we're only saving final transcripts here (note:  this will make the Ux appear slower)
            //logger.info("the result - " + result);
            if (!result.isPartial()) {
                try {

                    Item ddbItem = toDynamoDbItem(result);
                    Gson gson = new Gson();
                    String jsonDB = gson.toJson(ddbItem.asMap());
                    logger.info("Json is - " +jsonDB);
                    if (ddbItem != null) {
                        getDdbClient().getTable(TABLE_TRANSCRIPT).putItem(ddbItem);
                        String messageBody = jsonDB;
                        SendMessageRequest send_msg_request = new SendMessageRequest()
                                .withQueueUrl(this.SQS_URL)
                                .withMessageBody(jsonDB);
                        getSQSClient().sendMessage(send_msg_request); 
                    }

                } catch (Exception e) {
                    logger.error("Exception while writing to DDB: ", e);
                }
            }
        }
    }

    /**
     * Transcribe website looks for Final event in DynamoDB payload to stop polling for messages. This is workaround
     * to display end of streaming.
     */
    @Override
    public void publishDone() {
        Instant now = Instant.now();
        logger.info("writing end of transcription to DDB for " + this.transactionId);
        Item ddbItem = new Item()
            .withKeyComponent(TRANSACTION_ID, this.transactionId)
            .withKeyComponent(START_TIME, now.getEpochSecond())
            .withString(CALL_ID, this.callId)
            .withString(TRANSCRIPT, "END_OF_TRANSCRIPTION")
            // LoggedOn is an ISO-8601 string representation of when the entry was created
            .withString(LOGGED_ON, now.toString())
            .withBoolean(IS_PARTIAL, Boolean.FALSE)
            .withBoolean(IS_FINAL, Boolean.TRUE);
        
        if (ddbItem != null) {
            try {
                getDdbClient().getTable(TABLE_TRANSCRIPT).putItem(ddbItem);
            } catch (Exception e) {
                logger.error("Exception while writing to DDB:", e);
            }
        }
    }

    private DynamoDB getDdbClient() {
        return this.ddbClient;
    }

    private AmazonSQS getSQSClient() {
        return this.SqsClient;
    }

    private String initSpeakerLabel() {
        // assume that the first speaker is spk_0 and all others are spk_1
        // TODO:  if we need to be more precise, use the stream ARN to determine how many speakers for a given CallId
        String speaker = "spk_0";

        QuerySpec spec = new QuerySpec()
            .withMaxResultSize(1)
            .withKeyConditionExpression(TRANSACTION_ID + " = :id")
            .withValueMap(new ValueMap().withString(":id", getTransactionId()));

        if (getDdbClient().getTable(TABLE_TRANSCRIPT).query(spec).iterator().hasNext()) {
            speaker = "spk_1";
        }

        logger.info(String.format("Speaker label was assumed to be %s for %s", speaker, getTransactionId()));

        return speaker;
    }

    private Item toDynamoDbItem(Result result) {
        Item ddbItem = null;
        Instant now = Instant.now();
        if (result.alternatives().size() > 0) {
            if (!result.alternatives().get(0).transcript().isEmpty()) {
                ddbItem = new Item()
                        .withKeyComponent(TRANSACTION_ID, this.transactionId)
                        .withKeyComponent(START_TIME, result.startTime())
                        .withString(CALL_ID, this.callId)
                        .withString(SPEAKER, getSpeakerLabel())
                        .withDouble(END_TIME, result.endTime())
                        .withString(SEGMENT_ID, result.resultId())
                        .withString(TRANSCRIPT, result.alternatives().get(0).transcript())
                        // LoggedOn is an ISO-8601 string representation of when the entry was created
                        .withString(LOGGED_ON, now.toString())
                        .withBoolean(IS_PARTIAL, result.isPartial())
                        .withBoolean(IS_FINAL, Boolean.FALSE);

                if (consoleLogTranscriptFlag) {
                    NumberFormat nf = NumberFormat.getInstance();
                    nf.setMinimumFractionDigits(3);
                    nf.setMaximumFractionDigits(3);

                    logger.info(String.format("Thread %s %d: [%s, %s] %s - %s",
                            Thread.currentThread().getName(),
                            System.currentTimeMillis(),
                            nf.format(result.startTime()),
                            nf.format(result.endTime()),
                            getSpeakerLabel(),
                            result.alternatives().get(0).transcript()));
                }
            }
        }

        return ddbItem;
    }

    private String getTransactionId() {
        return this.transactionId;
    }

    private String getSpeakerLabel() {
        // If isCaller is not avaiable, fall back to initial speaker label by querying DynamoDB.
        if (this.isCaller == null && this.speakerLabel == null) {
            this.speakerLabel = initSpeakerLabel();
        } else if (this.isCaller != null) {
            this.speakerLabel = this.isCaller == Boolean.TRUE ? "spk_0" : "spk_1";
        }

        return this.speakerLabel;
    }
}

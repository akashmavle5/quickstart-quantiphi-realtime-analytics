package com.amazonaws.kvstranscribestreaming.lambda;

import com.amazonaws.kvstranscribestreaming.handler.KVSTranscribeStreamingHandler;
import com.amazonaws.kvstranscribestreaming.constants.Platform;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrate Amazon VoiceConnectors's real-time transcription feature using
 * AWS Kinesis Video Streams and AWS Transcribe. The data flow is :
 * <p>
 * Amazon CloudWatch Events => Amazon SQS => AWS Lambda => AWS Transcribe => AWS
 * DynamoDB & S3
 *
 */
public class KVSTranscribeStreamingLambda implements RequestHandler<SQSEvent, String> {
    private static final Logger logger = LoggerFactory.getLogger(KVSTranscribeStreamingLambda.class);
    private static final String LAMBDA_KEY_PREFIX = "KVSTranscribeStreamingLambda:";
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String handleRequest(SQSEvent event, Context context) {
        try {
            logger.info(LAMBDA_KEY_PREFIX + " received request : " + objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            logger.error(LAMBDA_KEY_PREFIX + " Error happened where serializing the event", e);
        }
        logger.info(LAMBDA_KEY_PREFIX + " received context: " + context.toString());

        try {
            event.getRecords().forEach(msg -> {
                logger.info("Received streaming message  : " + msg.getBody());
            });
            if (event.getRecords().size() != 1) {
                logger.error("Invalid number of records present in the SQS message body");
                throw new RuntimeException("Invalid number of records");
            }

            SQSEvent.SQSMessage sqsMessage = event.getRecords().get(0);
            logger.info("SQS message body: {} ", sqsMessage.getBody());

            KVSTranscribeStreamingHandler handler = new KVSTranscribeStreamingHandler(Platform.LAMBDA);

            logger.info("body from sqs message {}", sqsMessage.getBody());
            handler.handleRequest(sqsMessage.getBody());
        } catch (Exception e) {
            logger.error(LAMBDA_KEY_PREFIX + " KVS to Transcribe Streaming failed with: ", e);
            return "{ \"result\": \"Failed\" }";
        }
        return "{ \"result\": \"Success\" }";
    }
}

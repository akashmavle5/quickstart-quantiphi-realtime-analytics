package com.amazonaws.kvstranscribestreaming.lambda;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClientBuilder;
import com.amazonaws.services.ecs.model.ContainerOverride;
import com.amazonaws.services.ecs.model.Failure;
import com.amazonaws.services.ecs.model.KeyValuePair;
import com.amazonaws.services.ecs.model.LaunchType;
import com.amazonaws.services.ecs.model.RunTaskRequest;
import com.amazonaws.services.ecs.model.RunTaskResult;
import com.amazonaws.services.ecs.model.TaskOverride;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.streamingeventmodel.StreamingStatus;
import com.amazonaws.streamingeventmodel.StreamingStatusStartedDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Function that is used to start transcription container by sending RunTask request with streaming event detail and environment overrides.
 */
public class SendRunTaskRequestLambda implements RequestHandler<SQSEvent, String> {
    private static final Logger logger = LoggerFactory.getLogger(SendRunTaskRequestLambda.class);
    private static final String LAMBDA_KEY_PREFIX = "SendRunTaskRequestLambda:";
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String IS_TRANSCRIBE_ENABLED = System.getenv("IS_TRANSCRIBE_ENABLED");
    private static final String RECORDINGS_BUCKET_NAME = System.getenv("RECORDINGS_BUCKET_NAME");
    private static final String TASK_DEFINITION = System.getenv("TASK_DEFINITION");
    private static final Regions AWS_REGION = Regions.fromName(System.getenv("AWS_REGION"));
    private static final String CLUSTER_NAME = System.getenv("CLUSTER_NAME");
    private static final String CONTAINER_NAME = System.getenv("CONTAINER_NAME");
    // private static final String WEBSOCKET_MAPPING_TABLE_NAME = System.getenv("WEBSOCKET_MAPPING_TABLE_NAME");
    private static final String TRANSCRIBE_API_GATEWAY_APIID = System.getenv("TRANSCRIBE_API_GATEWAY_APIID");
    private static final String TRANSCRIBE_API_GATEWAY_STAGE = System.getenv("TRANSCRIBE_API_GATEWAY_STAGE");
    private static final String SQS_ENDPOINT = System.getenv("SQS_ENDPOINT");
    private static final String DDB_OUT_NAME = System.getenv("DDB_OUT_NAME");
    private static final AmazonECS ecsClient = AmazonECSClientBuilder.standard().withRegion(AWS_REGION.getName()).build();

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

            Map<String, Object> eventBodyMap = objectMapper.readValue(sqsMessage.getBody(), Map.class);
            Map<String, String> eventDetail = (Map) eventBodyMap.get("detail");

            String streamingStatus = eventDetail.get("streamingStatus");
            String transactionId = eventDetail.get("transactionId");
            logger.info("Received STARTED new event ");

            if (StreamingStatus.STARTED.name().equals(streamingStatus)) {
                final StreamingStatusStartedDetail startedDetail = objectMapper.convertValue(eventDetail,
                        StreamingStatusStartedDetail.class);

                logger.info("[{}] Streaming status {} , EventDetail: {}", transactionId, streamingStatus, startedDetail);

                List<String> commandOverride = Arrays.asList("-e", sqsMessage.getBody());
                List<KeyValuePair> environmentOverride = Arrays.asList(
                        new KeyValuePair().withName("IS_TRANSCRIBE_ENABLED").withValue(IS_TRANSCRIBE_ENABLED),
                        new KeyValuePair().withName("RECORDINGS_BUCKET_NAME").withValue(RECORDINGS_BUCKET_NAME),
                        new KeyValuePair().withName("AWS_REGION").withValue(AWS_REGION.getName()),
                        // new KeyValuePair().withName("WEBSOCKET_MAPPING_TABLE_NAME").withValue(WEBSOCKET_MAPPING_TABLE_NAME),
                        new KeyValuePair().withName("TRANSCRIBE_API_GATEWAY_APIID").withValue(TRANSCRIBE_API_GATEWAY_APIID),
                        new KeyValuePair().withName("TRANSCRIBE_API_GATEWAY_STAGE").withValue(TRANSCRIBE_API_GATEWAY_STAGE),
                        new KeyValuePair().withName("SQS_ENDPOINT").withValue(SQS_ENDPOINT),
                        new KeyValuePair().withName("DDB_OUT_NAME").withValue(DDB_OUT_NAME)
                );
                ContainerOverride containerOverride = new ContainerOverride().withName(CONTAINER_NAME).withCommand(commandOverride).withEnvironment(environmentOverride);
                RunTaskResult result = ecsClient.runTask(new RunTaskRequest()
                        .withCluster(CLUSTER_NAME)
                        .withTaskDefinition(TASK_DEFINITION)
                        .withLaunchType(LaunchType.EC2)
                        .withOverrides(new TaskOverride().withContainerOverrides(containerOverride)));

                if(result.getFailures().isEmpty()) {
                    logger.info("{} Sending RunTask request succeeded. ", LAMBDA_KEY_PREFIX);
                    return "{ \"result\": \"Success\" }";
                } else {
                    List<Failure> failures = result.getFailures();
                    logger.error("{} Sending RunTask request failed", LAMBDA_KEY_PREFIX);

                    for(Failure failure : failures) {
                        logger.error("Failure detail: {}", failure);
                    }
                    return "{ \"result\": \"Failed\" }";
                }
            }
        } catch (Exception e) {
            logger.error("{} Sending RunTask request failed with: ", LAMBDA_KEY_PREFIX, e);
            return "{ \"result\": \"Failed\" }";
        }
        return "{ \"result\": \"Success\" }";
    }
}

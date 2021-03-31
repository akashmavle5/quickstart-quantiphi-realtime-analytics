package com.amazonaws.kvstranscribestreaming.lambda;

import com.amazonaws.kvstranscribestreaming.constants.WebSocketMappingDDBConstants;
import com.amazonaws.kvstranscribestreaming.constants.WebsocketConnectionDDBConstants;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.DeleteItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.GetItemSpec;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Integration function that processes and responds web socket connection request through API Gateway.
 *
 * 1. When client connects to API Gateway (route key: $connect), Lambda does nothing.
 * 2. When client is ready to receive transcription (route key: transcribe), Lambda stores both from and to numbers in the mapping table, also
 * stores connection and associated numbers in the connection table.
 * 3. When client disconnects, Lambda removes the numbers and connection from mapping and connection table.
 */
public class TranscriptionWebSocketIntegrationLambda implements RequestHandler<APIGatewayV2WebSocketEvent, APIGatewayV2WebSocketResponse> {

    private static final Logger logger = LoggerFactory.getLogger(TranscriptionWebSocketIntegrationLambda.class);
    private static final String LAMBDA_KEY_PREFIX = "TranscriptionWebSocketIntegrationLambda:";
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String WEB_SOCKET_MAPPING_TABLE = System.getenv("WEB_SOCKET_MAPPING_TABLE");
    private static final String WEB_SOCKET_CONNECTION_TABLE = System.getenv("WEB_SOCKET_CONNECTION_TABLE");

    private static final String TRANSCRIBE_ROUTE_KEY = System.getenv("TRANSCRIBE_ROUTE_KEY");
    private static final String DISCONNECT_ROUTE_KEY = "$disconnect";
    private static final Regions AWS_REGION = Regions.fromName(System.getenv("AWS_REGION"));

    private static final DynamoDB dynamoDB = new DynamoDB(
            AmazonDynamoDBClientBuilder.standard().withRegion(AWS_REGION.getName()).build());

    @Override
    public APIGatewayV2WebSocketResponse handleRequest(APIGatewayV2WebSocketEvent requestEvent, Context context) {
        try {
            logger.info(LAMBDA_KEY_PREFIX + " received request : " + objectMapper.writeValueAsString(requestEvent));
        } catch (JsonProcessingException e) {
            logger.error(LAMBDA_KEY_PREFIX + " Error happened where serializing the event", e);
        }
        logger.info(LAMBDA_KEY_PREFIX + " received context: " + context.toString());

        APIGatewayV2WebSocketResponse responseEvent = new APIGatewayV2WebSocketResponse();
        try {
            String routeKey = requestEvent.getRequestContext().getRouteKey(), connectionId = requestEvent.getRequestContext().getConnectionId();

            if (!routeKey.equals(DISCONNECT_ROUTE_KEY) && !routeKey.equals(TRANSCRIBE_ROUTE_KEY)) {
                generateResponse(responseEvent, 200, "Success");
                return responseEvent;
            }

            Table mappingTable = dynamoDB.getTable(WEB_SOCKET_MAPPING_TABLE);
            Table connectionTable = dynamoDB.getTable(WEB_SOCKET_CONNECTION_TABLE);

            // Release all resources when client calls disconnect.
            if (routeKey.equals(DISCONNECT_ROUTE_KEY)) {
                GetItemSpec spec = new GetItemSpec()
                        .withPrimaryKey(WebsocketConnectionDDBConstants.CONNECTION_ID, connectionId)
                        .withConsistentRead(true);

                Item item = dynamoDB.getTable(WEB_SOCKET_CONNECTION_TABLE).getItem(spec);
                DeleteItemSpec deleteNumberSpec;

                if(item.hasAttribute(WebsocketConnectionDDBConstants.ASSOCIATED_NUMBERS)) {
                    Set<String> numbers = (Set) item.get(WebsocketConnectionDDBConstants.ASSOCIATED_NUMBERS);

                    // Remove number mappings from the mapping table.
                    for(String n : numbers) {
                        deleteNumberSpec = new DeleteItemSpec().withPrimaryKey(WebSocketMappingDDBConstants.NUMBER, n);
                        mappingTable.deleteItem(deleteNumberSpec);
                    }
                }

                // Remove the connection from the connection table.
                deleteNumberSpec = new DeleteItemSpec().withPrimaryKey(WebsocketConnectionDDBConstants.CONNECTION_ID, connectionId);
                connectionTable.deleteItem(deleteNumberSpec);

                generateResponse(responseEvent, 200, "Web socket connection " + connectionId + " with route key " + routeKey + " has been disconnected");
            }

            // Put DDB resources when client is ready to receive transcription
            if (routeKey.equals(TRANSCRIBE_ROUTE_KEY)) {
                if(requestEvent.getBody() == null) {
                    generateResponse(responseEvent, 400, "Must specify body");
                    return responseEvent;
                }

                Map<String, String> eventBodyMap = objectMapper.readValue(requestEvent.getBody(), Map.class);
                if(eventBodyMap.get("from") == null && eventBodyMap.get("to") == null) {
                    generateResponse(responseEvent, 400, "Must specify from or to numbers");
                    return responseEvent;
                }

                String fromNumber = eventBodyMap.get("from"), toNumber = eventBodyMap.get("to");
                Item numberItem, connectionItem;
                Set<String> numbers = new HashSet<>();

                if(fromNumber != null) {
                    numbers.add(fromNumber);

                    numberItem = new Item()
                            .withPrimaryKey(WebSocketMappingDDBConstants.NUMBER, fromNumber)
                            .withString(WebSocketMappingDDBConstants.CONNECTION_ID, connectionId)
                            .withString(WebSocketMappingDDBConstants.UPDATE_TIME, Instant.now().toString());

                    mappingTable.putItem(numberItem);
                }

                if(toNumber != null) {
                    numbers.add(toNumber);

                    numberItem = new Item()
                            .withPrimaryKey(WebSocketMappingDDBConstants.NUMBER, toNumber)
                            .withString(WebSocketMappingDDBConstants.CONNECTION_ID, connectionId)
                            .withString(WebSocketMappingDDBConstants.UPDATE_TIME, Instant.now().toString());

                    mappingTable.putItem(numberItem);
                }

                connectionItem = new Item()
                        .withPrimaryKey(WebsocketConnectionDDBConstants.CONNECTION_ID, connectionId)
                        .withStringSet(WebsocketConnectionDDBConstants.ASSOCIATED_NUMBERS, numbers);
                connectionTable.putItem(connectionItem);

                generateResponse(responseEvent, 200,
                        "Web socket connection " + connectionId + " with route key " + routeKey + " associated with numbers " + numbers.toString() + " has been established");
            }

        } catch (Exception e) {
            logger.error("{} transcription integration failed. Reason: {}", LAMBDA_KEY_PREFIX, e.getMessage(), e);
            generateResponse(responseEvent, 500, "Must specify from and to numbers");
        }

        logger.info("{} response event {}", LAMBDA_KEY_PREFIX, responseEvent);
        return responseEvent;
    }

    private void generateResponse(APIGatewayV2WebSocketResponse responseEvent, Integer statusCode, String message) {
        responseEvent.setHeaders(Collections.singletonMap("timeStamp", String.valueOf(System.currentTimeMillis())));
        responseEvent.setStatusCode(statusCode);
        responseEvent.setBody(message);
    }
}

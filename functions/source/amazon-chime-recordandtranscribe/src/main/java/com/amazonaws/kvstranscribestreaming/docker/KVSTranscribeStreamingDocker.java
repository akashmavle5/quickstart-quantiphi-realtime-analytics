package com.amazonaws.kvstranscribestreaming.docker;

import com.amazonaws.kvstranscribestreaming.constants.Platform;
import com.amazonaws.kvstranscribestreaming.handler.KVSTranscribeStreamingHandler;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrate Amazon VoiceConnectors's real-time transcription feature using
 * AWS Kinesis Video Streams and AWS Transcribe. The data flow is :
 * <p>
 * Amazon CloudWatch Events => Amazon SQS => AWS Lambda => AWS Transcribe => AWS
 * DynamoDB & S3
 */
public class KVSTranscribeStreamingDocker {
    private static final Logger logger = LoggerFactory.getLogger(KVSTranscribeStreamingDocker.class);
    private static final String DOCKER_KEY_PREFIX = "KVSTranscribeStreamingDocker:";
    public static void main(String[] args) {
        final KVSTranscribeStreamingHandler handler = new KVSTranscribeStreamingHandler(Platform.ECS);
        String eventBody = constructEventBody(args);
        handler.handleRequest(eventBody);
    }

    private static String constructEventBody(String[] args) {
        final Options options = new Options();
        options.addRequiredOption("e", "eventBody", true, "Streaming event body in json format");

        final CommandLineParser parser = new DefaultParser();
        try {
            final CommandLine line = parser.parse(options, args);
            final String body = line.getOptionValue('e');
            logger.info("{} event body is {}", DOCKER_KEY_PREFIX, body);

            return body;
        } catch (final Exception e) {
            String errorMsg = String.format("Unable to process streaming event. Message: %s", e.getMessage());
            logger.error(errorMsg, e);
            throw new RuntimeException(errorMsg);
        }
    }
}


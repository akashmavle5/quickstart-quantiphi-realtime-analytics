package com.amazonaws.kvstranscribestreaming.transcribe;

import com.amazonaws.kvstranscribestreaming.publisher.TranscriptionPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionResponse;
import software.amazon.awssdk.services.transcribestreaming.model.TranscriptEvent;
import software.amazon.awssdk.services.transcribestreaming.model.TranscriptResultStream;

import java.util.List;

/**
 * Implementation of StreamTranscriptionBehavior to define how a stream response is handled.
 */
public class StreamTranscriptionBehaviorImpl implements StreamTranscriptionBehavior {

    private static final Logger logger = LoggerFactory.getLogger(StreamTranscriptionBehaviorImpl.class);
    private final List<TranscriptionPublisher> transcriptionPublisher;

    public StreamTranscriptionBehaviorImpl(List<TranscriptionPublisher> transcriptionPublisher) {
        this.transcriptionPublisher = transcriptionPublisher;
    }

    @Override
    public void onError(Throwable e) {
        logger.error("Error in middle of stream: ", e);
    }

    @Override
    public void onStream(TranscriptResultStream e) {
        // EventResultStream has other fields related to the timestamp of the transcripts in it.
        // Please refer to the javadoc of TranscriptResultStream for more details
        publishTranscript((TranscriptEvent) e);
    }

    @Override
    public void onResponse(StartStreamTranscriptionResponse r) {
        logger.info(String.format("%d Received Initial response from Transcribe. Request Id: %s",
                System.currentTimeMillis(), r.requestId()));
    }

    @Override
    public void onComplete() {
        logger.info("Transcribe stream completed");
        completeTranscriptPublish();
    }

    private void publishTranscript(TranscriptEvent e) {
        for(TranscriptionPublisher publisher : this.transcriptionPublisher) {
            publisher.publish(e);
        }
    }

    private void completeTranscriptPublish() {
        for(TranscriptionPublisher publisher : this.transcriptionPublisher) {
            publisher.publishDone();
        }
    }
}


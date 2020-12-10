package com.amazonaws.kvstranscribestreaming.publisher;

import software.amazon.awssdk.services.transcribestreaming.model.TranscriptEvent;

/**
 * A general purpose interface that exposes publish and publishDone methods to publish transcription to a datastore or endpoint.
 *
 * All details are in the actual implementation.
 */
public interface TranscriptionPublisher {
    /**
     * Publish transcription given a {@link TranscriptEvent}
     */
    void publish(TranscriptEvent e);

    /**
     * Publish transcription done signal.
     */
    void publishDone();
}

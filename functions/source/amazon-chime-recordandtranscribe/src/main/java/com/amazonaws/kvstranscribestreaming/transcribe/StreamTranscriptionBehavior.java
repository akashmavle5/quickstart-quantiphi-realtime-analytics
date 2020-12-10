package com.amazonaws.kvstranscribestreaming.transcribe;

import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionResponse;
import software.amazon.awssdk.services.transcribestreaming.model.TranscriptResultStream;

/**
 * Defines how a stream response should be handled.
 * You should build a class implementing this interface to define the behavior.
 */
public interface StreamTranscriptionBehavior {
    /**
     * Defines how to respond when encountering an error on the stream transcription.
     *
     * @param e The exception
     */
    void onError(Throwable e);

    /**
     * Defines how to respond to the Transcript result stream.
     *
     * @param e The TranscriptResultStream event
     */
    void onStream(TranscriptResultStream e);

    /**
     * Defines what to do on initiating a stream connection with the service.
     *
     * @param r StartStreamTranscriptionResponse
     */
    void onResponse(StartStreamTranscriptionResponse r);

    /**
     * Defines what to do on stream completion
     */
    void onComplete();
}
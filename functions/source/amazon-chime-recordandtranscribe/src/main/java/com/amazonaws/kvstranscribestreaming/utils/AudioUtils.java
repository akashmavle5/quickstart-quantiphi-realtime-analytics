package com.amazonaws.kvstranscribestreaming.utils;

import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Utility class to download/upload audio files from/to S3
 */
public final class AudioUtils {

    private static final Logger logger = LoggerFactory.getLogger(AudioUtils.class);

    /**
     * Converts the given raw audio data into a wav file. Returns the wav file back.
     */
    private static File convertToWav(String audioFilePath) throws IOException, UnsupportedAudioFileException {
        File outputFile = new File(audioFilePath.replace(".raw", ".wav"));
        AudioInputStream source = new AudioInputStream(Files.newInputStream(Paths.get(audioFilePath)),
                new AudioFormat(8000, 16, 1, true, false), -1); // 8KHz, 16 bit, 1 channel, signed, little-endian
        AudioSystem.write(source, AudioFileFormat.Type.WAVE, outputFile);
        return outputFile;
    }

    /**
     * Saves the raw audio file as an S3 object
     *  @param region
     * @param bucketName
     * @param keyPrefix
     * @param audioFilePath
     * @param transactionId
     * @param awsCredentials
     */
    public static void uploadRawAudio(Regions region, String bucketName, String keyPrefix, String audioFilePath, String transactionId, String startTime, boolean publicReadAcl, AWSCredentialsProvider awsCredentials) {
        File wavFile = null;
        try {

            AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                    .withRegion(region)
                    .withCredentials(awsCredentials)
                    .build();

            wavFile = convertToWav(audioFilePath);

            // upload the raw audio file to the designated S3 location
            String objectKey = keyPrefix + wavFile.getName();

            logger.info(String.format("Uploading Audio: to %s/%s from %s", bucketName, objectKey, wavFile));
            PutObjectRequest request = new PutObjectRequest(bucketName, objectKey, wavFile);
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("audio/wav");
            metadata.addUserMetadata("transactionId", transactionId);
            metadata.addUserMetadata("startTime", startTime);
            request.setMetadata(metadata);

            if (publicReadAcl) {
                request.setCannedAcl(CannedAccessControlList.PublicRead);
            }

            s3Client.putObject(request);

            logger.info("putObject completed successfully for S3 key " + objectKey);

        } catch (SdkClientException e) {
            logger.error("Audio upload to S3 failed: ", e);
            throw e;
        } catch (UnsupportedAudioFileException|IOException e) {
            logger.error("Failed to convert to wav: ", e);
        }
        finally {
            if (wavFile != null) {
                wavFile.delete();
            }
        }
    }
}

package com.amazonaws.kvstranscribestreaming.utils;

import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.model.MetricDatum;
import com.amazonaws.services.cloudwatch.model.PutMetricDataRequest;
import com.amazonaws.services.cloudwatch.model.StandardUnit;

import java.time.Instant;
import java.util.Date;

public class MetricsUtil {

    private static String NAMESPACE = "KVSTranscribeStreaming";
    private final AmazonCloudWatch amazonCloudWatch;

    public MetricsUtil(AmazonCloudWatch amazonCloudWatch) {
        this.amazonCloudWatch = amazonCloudWatch;
    }

    public void recordMetric(final String metricName, long value) {
        MetricDatum metricData = new MetricDatum().withMetricName(metricName)
                .withTimestamp(Date.from(Instant.now()))
                .withUnit(StandardUnit.Count)
                .withValue(Double.valueOf(value));

        PutMetricDataRequest metricRequest = new PutMetricDataRequest()
                .withNamespace(NAMESPACE)
                .withMetricData(metricData);

        amazonCloudWatch.putMetricData(metricRequest);
    }
}

package com.bachinalabs;

import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.runners.direct.DirectOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TypeDescriptors;

import java.util.Arrays;

/**
 * Hello world!
 *
 */
public class App 
{

    public static void main( String[] args ) {

        // Start by defining the options for the pipeline.
        System.out.println(args);
        DataflowPipelineOptions options = PipelineOptionsFactory.as(DataflowPipelineOptions.class);

        // For Cloud execution, set the Cloud Platform project, staging location,
        // and specify DataflowRunner.
        options.setProject("crypto-truck-334202");
        options.setRegion("us-central1");
        options.setServiceAccount("id-dataflow-pipeline@crypto-truck-334202.iam.gserviceaccount.com");
        // options.setWorkerZone("us-central1-c");
        options.setStagingLocation("gs://gcpdata_flowfiles/binaries/");
        options.setGcpTempLocation("gs://gcpdata_flowfiles/temp/");
        options.setNetwork("default");
        options.setSubnetwork("regions/us-central1/subnetworks/default");
        options.setRunner(DataflowRunner.class);
        options.setNumWorkers(3);

        // Then create the pipeline.
        Pipeline p = Pipeline.create(options);

        p.apply("ReadLines", TextIO.read().from("gs://gcpdata_flowfiles/gcp-file-in/inputData.txt"))
                .apply(new SplitWords())
                .apply(new CountWords())
                .apply("FormatResults", MapElements
                        .into(TypeDescriptors.strings())
                        .via((KV<String, Long> wordCount) -> wordCount.getKey() + ": " + wordCount.getValue()))
                .apply("WriteCounts", TextIO.write().to("gs://gcpdata_flowfiles/gcp-file-out/"));


        p.run().waitUntilFinish();

    }
}

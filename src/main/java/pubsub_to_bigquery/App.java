/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package pubsub_to_bigquery;

import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;

import org.apache.beam.runners.direct.DirectOptions;
import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.*;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.transforms.windowing.*;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition.CREATE_NEVER;
import static org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition.WRITE_APPEND;
import static pubsub_to_bigquery.TransformToBQ.FAILURE_TAG;
import static pubsub_to_bigquery.TransformToBQ.SUCCESS_TAG;

public class App {

    //for argumanets
    /*public interface MyOptions extends DataflowPipelineOptions, DirectOptions {
        @Description("BigQuery project")
        @Default.String("my_bq_project")
        String getBQProject();

        void setBQProject(String value);

        @Description("BigQuery dataset")
        @Default.String("my_bq_dataset")
        String getBQDataset();

        void setBQDataset(String value);

        @Description("Bucket path to collect pipeline errors in json files")
        @Default.String("errors")

        String getErrorsBucket();
        void setErrorsBucket(String value);

        @Description("Bucket path to collect pipeline errors in json files")
        @Default.String("my_bucket")
        String getBucket();

        void setBucket(String value);

        @Description("Pubsub project")
        @Default.String("my_pubsub_project")
        String getPubSubProject();

        void setPubSubProject(String value);

        @Description("Pubsub subscription")
        @Default.String("my_pubsub_subscription")
        String getSubscription();

        void setSubscription(String value);
    }*/

      /** The log to output status messages to. */
    private static Logger LOG = LoggerFactory.getLogger(App.class);

    /**
     * class {@link ErrorFormatFileName} implement file naming format for files in errors bucket (failed rows)
     * used in withNaming
     */
    private static class ErrorFormatFileName implements FileIO.Write.FileNaming {

        private static final DateTimeFormatter DATE_FORMAT = DateTimeFormat.forPattern("yyyy-MM-dd");
        private static final DateTimeFormatter TIME_FORMAT = DateTimeFormat.forPattern("HH:mm:ss");

        private final String filePrefix;

        ErrorFormatFileName(String prefix) {
            filePrefix = prefix;
        }

        /**
         * Create filename in specific format
         *
         * @return A string representing filename.
         */
        @Override
        public String getFilename(BoundedWindow window, PaneInfo pane, int numShards, int shardIndex, Compression compression) {
            //IntervalWindow intervalWindow = (IntervalWindow) window;

            return String.format(
                    "%s/%s/error-%s-%s-of-%s%s",
                    filePrefix,
                    DATE_FORMAT.print(new DateTime()),
                    TIME_FORMAT.print(new DateTime()),
                    shardIndex,
                    numShards,
                    compression.getSuggestedSuffix());
        }
    }

    public static void main(String[] args) {

        DataflowPipelineOptions options = PipelineOptionsFactory.as(DataflowPipelineOptions.class);

        // For Cloud execution, set the Cloud Platform project, staging location,
        // and specify DataflowRunner.
        options.setProject("crypto-truck-334202");
        options.setRegion("us-central1");
        //  options.setServiceAccount("id-dataflow-pipeline@crypto-truck-334202.iam.gserviceaccount.com");
        // options.setWorkerZone("us-central1-c");
        options.setStagingLocation("gs://library_app_bucket/staging/");
        options.setGcpTempLocation("gs://library_app_bucket/tmp/");
        options.setNetwork("default");
        options.setSubnetwork("regions/us-central1/subnetworks/default");
        //Running from gcp cloud
        //options.setRunner(DataflowRunner.class);

        //from local system running
        options.setRunner(DirectRunner.class);
        options.setNumWorkers(1);

        Pipeline p = Pipeline.create(options);

        final String PROJECT = options.getProject();
        final String ERRORS_BUCKET = String.format("gs://%s/%s/", "library_app_bucket", "errors");
        final String SUBSCRIPTION = String.format("projects/%s/subscriptions/%s", "crypto-truck-334202", "library_app_subscription");
        final int STORAGE_LOAD_INTERVAL = 1; // minutes
        final int STORAGE_NUM_SHARDS = 1;

        final String BQ_PROJECT = "crypto-truck-334202";
        final String BQ_DATASET = "library_app_dataset";

        System.out.println(options);

        // 1. Read from PubSub
        PCollection<String> pubsubMessages = p
                .apply("ReadPubSubSubscription", PubsubIO.<String>readStrings().fromSubscription(SUBSCRIPTION));

        // 2. Count PubSub Data
        pubsubMessages.apply("CountPubSubData", ParDo.of(new DoFn<String, String>() {
                    @ProcessElement
                    public void processElement(ProcessContext c)  {
                        Metric.pubsubMessages.inc();
                    }
                }));

        // 3. Transform element to TableRow
        PCollectionTuple results = pubsubMessages.apply("TransformToBQ", TransformToBQ.run());

        // 4. Write the successful records out to BigQuery

        WriteResult writeResult = results.get(SUCCESS_TAG).apply("WriteSuccessfulRecordsToBQ", BigQueryIO.writeTableRows()
                .withMethod(BigQueryIO.Write.Method.STREAMING_INSERTS)
                .withFailedInsertRetryPolicy(InsertRetryPolicy.retryTransientErrors()) //Retry all failures except for known persistent errors.
                .withWriteDisposition(WRITE_APPEND)
                .withCreateDisposition(CREATE_NEVER)
                .withExtendedErrorInfo() //- getFailedInsertsWithErr
                .ignoreUnknownValues()
                .skipInvalidRows()
                .withoutValidation()
                .to((row) -> {
                    String tableName = Objects.requireNonNull(row.getValue()).get("event_type").toString();
                    return new TableDestination(String.format("%s:%s.%s", BQ_PROJECT, BQ_DATASET, tableName), "Some destination");
                })
        );
        
        // 5. Write rows that failed to GCS using windowing of STORAGE_LOAD_INTERVAL interval
        // Flatten failed rows after TransformToBQ with failed inserts

        PCollection<KV<String, String>> failedInserts = writeResult.getFailedInsertsWithErr()
                .apply("MapFailedInserts", MapElements.via(new SimpleFunction<BigQueryInsertError, KV<String, String>>() {
                                                               @Override
                                                               public KV<String, String> apply(BigQueryInsertError input) {
                                                                   return KV.of("FailedInserts", input.getError().toString() + " for table" + input.getRow().get("table") + ", message: "+ input.getRow().toString());
                                                               }
                                                           }
                ));

        // 6. Count failed inserts
        failedInserts.apply("LogFailedInserts", ParDo.of(new DoFn<KV<String, String>, Void>() {
            @ProcessElement
            public void processElement(ProcessContext c)  {
                LOG.error("{}: {}", c.element().getKey(), c.element().getValue());
                Metric.failedInsertMessages.inc();
            }
        }));


        // 7. write all 'bad' data to ERRORS_BUCKET with STORAGE_LOAD_INTERVAL
        PCollectionList<KV<String, String>> allErrors = PCollectionList.of(results.get(FAILURE_TAG)).and(failedInserts);
        allErrors.apply(Flatten.<KV<String, String>>pCollections())
                .apply("Window Errors", Window.<KV<String, String>>into(new GlobalWindows())
                .triggering(Repeatedly
                        .forever(AfterProcessingTime
                                .pastFirstElementInPane()
                                .plusDelayOf(Duration.standardMinutes(STORAGE_LOAD_INTERVAL)))
                )
                .withAllowedLateness(Duration.standardDays(1))
                .discardingFiredPanes()
        )
                .apply("WriteErrorsToGCS", FileIO.<String, KV<String, String>>writeDynamic()
                        .withDestinationCoder(StringUtf8Coder.of())
                        .by(KV::getKey)
                        .via(Contextful.fn(KV::getValue), TextIO.sink())
                        .withNumShards(STORAGE_NUM_SHARDS)
                        .to(ERRORS_BUCKET)
                        .withNaming(ErrorFormatFileName::new));


        p.run().waitUntilFinish();


    }
}
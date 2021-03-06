package pubsub_to_bigquery;

import com.google.api.services.bigquery.model.TableRow;
import org.apache.beam.sdk.coders.Coder.Context;
import org.apache.beam.sdk.io.gcp.bigquery.TableRowJsonCoder;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


public class TransformToBQ {

    private static final Logger log = LoggerFactory.getLogger(TransformToBQ.class);

    public static PTransform<PCollection<String>, PCollectionTuple> run() {
        return new JsonToTableRow();
    }

    static final TupleTag<TableRow> SUCCESS_TAG =
            new TupleTag<TableRow>(){};
    static final TupleTag<KV<String, String>> FAILURE_TAG =
            new TupleTag<KV<String, String>>(){};

    private static class JsonToTableRow
            extends PTransform<PCollection<String>, PCollectionTuple> {


        @Override
        public PCollectionTuple expand(PCollection<String> jsonStrings) {
            return jsonStrings
                    .apply(ParDo.of(new DoFn<String, TableRow>() {
                        @ProcessElement
                        public void processElement(ProcessContext context) {
                            String jsonString = context.element();

                            byte[] message_in_bytes = jsonString.getBytes(StandardCharsets.UTF_8);

                            // The total request payload must be less than 10MB
                            // here we guess that request body is not more than 10MB, but 
                            // but also the size of the headers should be taken into account
                            if (message_in_bytes.length >= 10 * 1024 * 1024) {
                                log.error("Error: too big row of size {} bytes in type {}", message_in_bytes.length);
                                Metric.tooBigMessages.inc();
                                context.output(FAILURE_TAG, KV.of("TooBigRow", jsonString));
                            }

                            TableRow row;
                            // Parse the JSON into a {@link TableRow} object.
                            try (InputStream inputStream = new ByteArrayInputStream(message_in_bytes)) 
                            {
                                row = TableRowJsonCoder.of().decode(inputStream, Context.OUTER);
                                Metric.successfulMessages.inc();
                                context.output(row);

                             } catch (IOException e) {
                                log.error("Error: {}", e.getMessage());
                                Metric.jsonParseErrorMessages.inc();
                                context.output(FAILURE_TAG, KV.of("JsonParseError", jsonString));
                             }
                             

                        }
                    }).withOutputTags(SUCCESS_TAG, TupleTagList.of(FAILURE_TAG)));
        }

    }
}

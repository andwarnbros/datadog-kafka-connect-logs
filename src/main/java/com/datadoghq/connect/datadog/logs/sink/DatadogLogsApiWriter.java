package com.datadoghq.connect.datadog.logs.sink;

import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.ws.rs.core.Response;

public class DatadogLogsApiWriter {
    private final DatadogLogsSinkConnectorConfig config;
    private static final Logger log = LoggerFactory.getLogger(DatadogLogsApiWriter.class);
    private final List<SinkRecord> batch = new ArrayList<>();

    public DatadogLogsApiWriter(DatadogLogsSinkConnectorConfig config) {
        this.config = config;
    }

    /**
     * Writes records to the Datadog Logs API.
     * @param records to be written from the Source Broker to the Datadog Logs API.
     * @throws IOException may be thrown if the connection to the API fails.
     */
    public void write(Collection<SinkRecord> records) throws IOException {
        for (SinkRecord record : records) {
            if (batch.size() >= config.ddMaxBatchLength) {
                sendBatch();
            }

            batch.add(record);
        }

        // Flush remaining records
        sendBatch();
    }

    private void sendBatch() throws IOException {
        String requestContent = formatBatch();
        if (requestContent.isEmpty()) {
            log.debug("Nothing to send; Skipping the HTTP request.");
            return;
        }

        URL url = new URL("https://" + config.ddURL + ":" + config.ddPort.toString() + "/v1/input/" + config.ddAPIKey);
        HttpsURLConnection con = sendRequest(requestContent, url);
        batch.clear();

        // get response
        int status = con.getResponseCode();
        if (Response.Status.Family.familyOf(status) != Response.Status.Family.SUCCESSFUL) {
            String error = getOutput(con.getErrorStream());
            throw new IOException("HTTP Response code: " + status
                    + ", " + con.getResponseMessage() + ", " + error
                    + ", Submitted payload: " + requestContent
                    + ", url:" + url);
        }

        log.debug(", response code: " + status + ", " + con.getResponseMessage());

        // write the response to the log
        String content = getOutput(con.getInputStream());

        log.debug("Response content: " + content);
        con.disconnect();
    }

    private String getOutput(InputStream input) throws IOException {
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = input.read(buffer)) != -1) {
            errorOutput.write(buffer, 0, length);
        }

        return errorOutput.toString(StandardCharsets.UTF_8.name());
    }

    private HttpsURLConnection sendRequest(String requestContent, URL url) throws IOException {
        HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Content-Encoding", "gzip");
        byte[] compressedPayload = compress(requestContent);


        DataOutputStream output = new DataOutputStream(con.getOutputStream());
        output.write(compressedPayload);
        output.close();
        log.debug("Submitted payload: " + requestContent);

        return con;
    }

    private String formatBatch() {
        StringBuilder builder = new StringBuilder();
        for (SinkRecord record : batch) {
            if (record == null) {
                continue;
            }

            if (record.value() == null) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(record.value().toString());
        }

        if (builder.length() == 0) {
            return "";
        }

        return builder.toString();
    }

    private byte[] compress(String str) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(str.length());
        GZIPOutputStream gos = new GZIPOutputStream(os);
        gos.write(str.getBytes());
        os.close();
        gos.close();
        return os.toByteArray();
    }

}

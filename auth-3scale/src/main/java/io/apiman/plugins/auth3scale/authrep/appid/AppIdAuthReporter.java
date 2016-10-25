/*
 * Copyright 2016 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.apiman.plugins.auth3scale.authrep.appid;

import io.apiman.plugins.auth3scale.authrep.AuthRepConstants;
import io.apiman.plugins.auth3scale.authrep.apikey.ApiKeyReportData;
import io.apiman.plugins.auth3scale.util.ParameterMap;
import io.apiman.plugins.auth3scale.util.report.batchedreporter.AbstractReporter;
import io.apiman.plugins.auth3scale.util.report.batchedreporter.ReportToSend;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AppIdAuthReporter extends AbstractReporter<ApiKeyReportData> {
    // TODO consider ringbuffer.
    private final Map<Integer, ConcurrentLinkedQueue<ApiKeyReportData>> reports = new ConcurrentHashMap<>(); // TODO LRU?
    private static final int DEFAULT_LIST_CAPAC = 800;
    private static final int FULL_TRIGGER_CAPAC = 500;
    private static final int MAX_RECORDS = 1000; //

    @Override // TODO need locking?
    public List<ReportToSend> encode() {
        List<ReportToSend> encodedReports = new ArrayList<>(reports.size());
        for (ConcurrentLinkedQueue<ApiKeyReportData> queue : reports.values()) {
            if (queue.isEmpty())
                continue;

            ApiKeyReportData reportData = queue.poll();
            URI endpoint = reportData.getEndpoint();
            // Base report
            ParameterMap data = new ParameterMap();
            data.add(AuthRepConstants.PROVIDER_KEY, reportData.getServiceToken()); 
            data.add(AuthRepConstants.SERVICE_ID, reportData.getServiceId());

            // Transactions
            List<ParameterMap> transactions = new ArrayList<>(); //TODO approximate - size() is O(n) on linkedqueue, so don't use that.
            int i = 0;
            do {
                ParameterMap transaction = new ParameterMap(); // TODO consider moving these back into executor?
                transactions.add(transaction);

                transaction.add(AuthRepConstants.USER_KEY, reportData.getUserKey());
                transaction.add("timestamp", reportData.getTimestamp());

                //setIfNotNull(transaction, AuthRepConstants.REFERRER, reportData.getReferrer());
                setIfNotNull(transaction, AuthRepConstants.USER_ID, reportData.getUserId());
                transaction.add("usage", reportData.getUsage());
                setIfNotNull(transaction, "log", reportData.getLog());

                i++;
                reportData = queue.poll();
            } while (reportData != null && i < MAX_RECORDS);

            data.add("transactions", transactions.toArray(new ParameterMap[transactions.size()]));
            encodedReports.add(new ApiKeyAuthReportToSend(endpoint, data.encode()));
        }
        return encodedReports;
    }

    @Override
    public AppIdAuthReporter addRecord(ApiKeyReportData record) {
        ConcurrentLinkedQueue<ApiKeyReportData> reportGroup = reports.computeIfAbsent(record.groupId(), k -> new ConcurrentLinkedQueue<>());

        reportGroup.add(record);

        if (reportGroup.size() > FULL_TRIGGER_CAPAC) {
            full(); // This is just approximate, we don't care whether it's somewhat out.
        }
        return this;
    }

    private static final class ApiKeyAuthReportToSend implements ReportToSend {
        private final URI endpoint;
        private final String data;

        ApiKeyAuthReportToSend(URI endpoint, String data) {
            this.endpoint = endpoint;
            this.data = data;
        }

        @Override
        public String getData() {
            return data;
        }

        @Override
        public String getEncoding() {
            return "application/x-www-form-urlencoded";
        }

        @Override
        public URI getEndpoint() {
            return endpoint;
        }
    }
}
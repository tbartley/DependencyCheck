/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2018 Steve Springett. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.update.nvd;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.zip.GZIPInputStream;
import org.owasp.dependencycheck.data.nvdcve.CveDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.owasp.dependencycheck.data.nvd.json.CVEItem;
import org.owasp.dependencycheck.data.nvd.json.ConfigurationNodeExtension;
import org.owasp.dependencycheck.data.nvd.json.Description;
import org.owasp.dependencycheck.data.nvd.json.ProblemtypeDatum;
import org.owasp.dependencycheck.utils.Settings;

/**
 * Parser and processor of NVD CVE JSON data feeds.
 *
 * @author Jeremy Long
 */
public final class NvdCveParser {

    /**
     * The logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(NvdCveParser.class);
    /**
     * A reference to the Cve DB.
     */
    private final CveDB cveDB;
    /**
     * The filter for 2.3 CPEs in the CVEs - we don't import unless we get a
     * match.
     */
    private final String cpeStartsWithFilter;

    /**
     * Creates a new NVD CVE JSON Parser.
     *
     * @param settings the dependency-check settings
     * @param db a reference to the database
     */
    public NvdCveParser(Settings settings, CveDB db) {
        this.cpeStartsWithFilter = settings.getString(Settings.KEYS.CVE_CPE_STARTS_WITH_FILTER, "cpe:2.3:a:");
        this.cveDB = db;
    }

    public void parse(File file) {
        LOGGER.info("Parsing " + file.getName());
        try (InputStream fin = new FileInputStream(file);
                InputStream in = new GZIPInputStream(fin);
                InputStreamReader isr = new InputStreamReader(in);
                JsonReader reader = new JsonReader(isr)) {
            Gson gson = new GsonBuilder().create();

            reader.beginObject();

            while (reader.hasNext() && !JsonToken.BEGIN_ARRAY.equals(reader.peek())) {
                reader.skipValue();
            }
            reader.beginArray();
            while (reader.hasNext()) {
                final CVEItem cve = gson.fromJson(reader, CVEItem.class);

                cve.getCve().getCVEDataMeta().getSTATE();

                if (testCveCpeStartWithFilter(cve)) {
                    cveDB.updateVulnerability(cve);
                }

                //there can be zero or more CWE - should this be a list?
                String cwe;
                for (ProblemtypeDatum datum : cve.getCve().getProblemtype().getProblemtypeData()) {
                    for (Description d : datum.getDescription()) {
                        if ("en".equals(d.getLang())) {
                            cwe = d.getValue();
                        }
                    }
                }
                //contains the affected vendor/product/version - possibly useful for vendor/product/version matching
                cve.getCve().getAffects();

            }
        } catch (FileNotFoundException ex) {
            LOGGER.error(ex.getMessage());
            //TODO throw something
        } catch (IOException ex) {
            LOGGER.error("Error reading NVD JSON data: {}", file);
            LOGGER.debug("Error extracting the NVD JSON data from: " + file.toString(), ex);
        }
    }

    /**
     * Tests the CVE's CPE entries against the starts with filter. In general
     * this limits the CVEs imported to just application level vulnerabilities.
     *
     * @param cve the CVE entry to examine
     * @return <code>true</code> if the CVE affects CPEs identified by the
     * configured CPE Starts with filter
     */
    protected boolean testCveCpeStartWithFilter(final CVEItem cve) {
        //cycle through to see if this is a CPE we care about (use the CPE filters
         return cve.getConfigurations().getNodes().stream()
                    .map(node -> new ConfigurationNodeExtension(node))
                    .map(ConfigurationNodeExtension::streamNodes)
                    .flatMap(ConfigurationNodeExtension::streamCpeMatches)
                    .anyMatch(cpe -> cpe.getCpe23Uri().startsWith(cpeStartsWithFilter));
    }
}

/** 
 *  Copyright 2011 Applied Research in Patacriticism and the University of Virginia
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 **/
package org.nines;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;
import org.nines.RDFIndexerConfig.Mode;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class RDFIndexer {

    private int numFiles = 0;
    private int numObjects = 0;
    private long largestTextSize = 0;
    private RDFIndexerConfig config;
    private Queue<File> dataFileQueue;
    private ErrorReport errorReport;
    private LinkCollector linkCollector;
    private Logger log;
    private ExecutorService solrExecutorService;
    private JsonArray jsonPayload = new JsonArray();
    private int docCount = 0;
    private int postCount = 0;
    private String targetArchive;
    private SolrClient solrClient;
    private Date ts = new Date();
    private SimpleDateFormat ts2 = new SimpleDateFormat("yyyy-MM-dd");
    private String timeStamp = new String(ts2.format(ts));

    // special field names
    private final String isPartOf = "isPartOf";
    private final String hasPart = "hasPart";

    /**
     * 
     * @param config
     * @param config
     */
    public RDFIndexer(RDFIndexerConfig config) {

        this.config = config;
        String logFileRoot = this.config.getLogfileBaseName("");

        // setup logger
        String indexLog = this.config.getLogfileBaseName("progress") + "_progress.log";
        System.setProperty("index.log.file", indexLog);
        URL url = ClassLoader.getSystemResource("log4j-index.xml");
        DOMConfigurator.configure(url);
        this.log = Logger.getLogger(RDFIndexer.class.getName());

        // keep report file in the same folder as the log file.
        String logName;
        if (this.config.mode.equals(Mode.INDEX) || this.config.mode.equals(Mode.TEST)) {
            logName = logFileRoot + "_error.log";
        } else {
            logName = logFileRoot + "_" + this.config.mode.toString().toLowerCase() + "_error.log";
        }
        File reportFile = new File(logName);
        try {
            this.errorReport = new ErrorReport(reportFile);
        } catch (IOException e1) {
            this.log.error("Unable to open error report log for writing, aborting indexer.");
            return;
        }

        this.linkCollector = new LinkCollector(this.config.getLogfileBaseName("links"));
        this.solrClient = new SolrClient(this.config.solrBaseURL);
    }

    /**
     * Execute the configured indexing task
     */
    public void execute() {

        // if a purge was requested, it must be done FIRST
        if (config.deleteAll) {
            purgeArchive( config.coreName() );
        }

        // There is only something else to do if a MODE was configured
        if (config.mode.equals(Mode.NONE) == false) {

            // first, ensure that core is valid and exists
            try {
                this.solrClient.validateCore( config.coreName( ) );
            } catch (IOException e) {
                this.errorReport.addError(new IndexerError("Validate core", "", e.getMessage()));
            }

            // execute based on mode setting
            if (config.mode.equals(Mode.SPIDER)) {
                this.log.info("Full Text Spider Mode");
                doSpidering();
            } else if (config.mode.equals(Mode.CLEAN_RAW)) {
                this.log.info("Raw Text Cleanup Mode");
                doRawTextCleanup();
            } else if (config.mode.equals(Mode.CLEAN_FULL)) {
                this.log.info("Full Text Cleanup Mode");
                doFullTextCleanup();
            } else if (config.mode.equals(Mode.INDEX)) {
                this.log.info("Index Mode");
                doIndexing();
            } else {
                this.log.info("*** TEST MODE: Not committing changes to SOLR");
                doIndexing();
            }
        }

        this.errorReport.close();
        this.linkCollector.close();
    }

    private void doFullTextCleanup() {
        Date start = new Date();
        this.log.info("Started raw text cleanup at " + start);

        this.dataFileQueue = new LinkedList<File>();
        String fullPath = config.sourceDir.toString() + "/" + config.safeArchive( config.archiveName );
        recursivelyQueueFiles(new File(fullPath), false);
        int totalFiles = this.dataFileQueue.size();

        FullTextCleaner cleaner = new FullTextCleaner(config.archiveName, this.errorReport,
            config.customCleanClass);
        while (this.dataFileQueue.size() > 0) {
            File txtFile = this.dataFileQueue.remove();
            cleaner.clean(txtFile);
            this.errorReport.flush();
        }

        String stats = "Cleaned " + totalFiles + " files (Original Size: " + cleaner.getOriginalLength()
            + ", Cleaned Size: " + cleaner.getCleanedLength() + ", Total Files Cleaned: "
            + cleaner.getTotalFilesChanged() + ")";

        Date end = new Date();
        double durationSec = (end.getTime() - start.getTime()) / 1000.0;
        if (durationSec >= 60) {
            this.log.info(String.format("%s in %3.2f minutes.", stats, (durationSec / 60.0)));
        } else {
            this.log.info(String.format("%s in %3.2f seconds.", stats, durationSec));
        }
    }

    private void doRawTextCleanup() {
        Date start = new Date();
        log.info("Started raw text cleanup at " + start);

        this.dataFileQueue = new LinkedList<File>();
        String rawPath = config.sourceDir.toString() + "/" + config.safeArchive( config.archiveName );
        recursivelyQueueFiles(new File(rawPath), false);
        int totalFiles = this.dataFileQueue.size();

        RawTextCleaner cleaner = new RawTextCleaner(config, this.errorReport);
        while (this.dataFileQueue.size() > 0) {
            File rawFile = this.dataFileQueue.remove();
            cleaner.clean(rawFile);
            this.errorReport.flush();
        }

        String stats = "Cleaned " + totalFiles + " files (Original Size: " + cleaner.getOriginalLength()
            + ", Cleaned Size: " + cleaner.getCleanedLength() + ", Total Files Cleaned: "
            + cleaner.getTotalFilesChanged() + ")";

        Date end = new Date();
        double durationSec = (end.getTime() - start.getTime()) / 1000.0;
        if (durationSec >= 60) {
            this.log.info(String.format("%s in %3.2f minutes.", stats, (durationSec / 60.0)));
        } else {
            this.log.info(String.format("%s in %3.2f seconds.", stats, durationSec));
        }
    }
    
    /**
     * find the full path to the corrected text root baseed on 
     * the path to the original rdf sources
     * @return
     */
    private String findCorrectedTextRoot() {
        String path = config.sourceDir.toString();
        int pos = path.indexOf("/rdf/");
        path = path.substring(0, pos) + "/correctedtext/";
        path += config.safeArchive( config.archiveName ) + "/";
        return path;
    }

    private void doIndexing() {        
        Date start = new Date();
        log.info("Started indexing at " + start);
        System.out.println("Indexing " + config.sourceDir);
        indexDirectory(config.sourceDir);
        System.out.println("Indexing DONE");

        // report indexing stats
        Date end = new Date();
        double durationSec = (end.getTime() - start.getTime()) / 1000.0;
        if (durationSec >= 60) {
            this.log.info(String.format(
                "Indexed " + numFiles + " files (" + numObjects + " objects) in %3.2f minutes.", (durationSec / 60.0)));
        } else {
            this.log.info(String.format(
                "Indexed " + numFiles + " files (" + numObjects + " objects) in %3.2f seconds.", durationSec));
        }
        this.log.info("Largest text field size: " + this.largestTextSize);
    }

    private void doSpidering() {
        Date start = new Date();
        log.info("Started full-text spider at " + start);
        System.out.println("Full-text spider of " + config.sourceDir);
        spiderDirectory( config.sourceDir);
        System.out.println("DONE");

        // report indexing stats
        Date end = new Date();
        double durationSec = (end.getTime() - start.getTime()) / 1000.0;
        if (durationSec >= 60) {
            this.log.info(String.format("Spidered " + numFiles + " files in %3.2f minutes.", (durationSec / 60.0)));
        } else {
            this.log.info(String.format("Spidered " + numFiles + " files in %3.2f seconds.", durationSec));
        }
    }

    private void purgeArchive(final String coreName) {
        log.info("Deleting all data from: " + coreName);
        try {
            this.solrClient.postJSON("{\"delete\": { \"query\": \"*:*\"}, \"commit\": {}}", coreName);
        } catch (IOException e) {
            errorReport.addError(new IndexerError("", "", "Unable to POST DELETE message to SOLR. "
                + e.getLocalizedMessage()));
        }
    }

    private void recursivelyQueueFiles(final File dir, final boolean rdfMode) {
        if (dir.isDirectory()) {
            log.info("loading directory: " + dir.getPath());

            File fileList[] = dir.listFiles();
            for (File entry : fileList) {
                if ( entry.getName().endsWith(".svn") || entry.getName().endsWith(".git")) {
                    log.info("Skipping source control directory");
                    continue;
                }
                if (entry.isDirectory() ) {
                    recursivelyQueueFiles(entry, rdfMode);
                }

                if (rdfMode) {
                    if (entry.getName().endsWith(".rdf") || entry.getName().endsWith(".xml")) {
                        this.dataFileQueue.add(entry);
                    }
                } else {
                    this.dataFileQueue.add(entry);
                }
            }
        } else { // a file was passed in, not a folder
            this.log.info("loading file: " + dir.getPath());
            this.dataFileQueue.add(dir);
        }
    }

    /**
     * Run through all rdf files in the directory and harvest full text
     * from remote sites.
     * 
     * @param rdfDir
     */
    private void spiderDirectory(final File rdfDir) {
        this.dataFileQueue = new LinkedList<File>();
        recursivelyQueueFiles(rdfDir, true);
        this.numFiles = this.dataFileQueue.size();
        log.info("=> Spider text for " + rdfDir + " total files: " + this.numFiles);
        RdfTextSpider spider = new RdfTextSpider( config, this.errorReport);
        while (this.dataFileQueue.size() > 0) {
            File rdfFile = this.dataFileQueue.remove();
            this.log.info("Spider text from file " + rdfFile.toString());
            spider.spider(rdfFile);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
            this.errorReport.flush();
        }
    }

    /**
     * run through all RDF files in the directory and write them
     * to a solr archive.
     * 
     * @param rdfDir
     */
    private void indexDirectory(File rdfDir) {
        // see if corrected texts exist. 
        config.correctedTextDir = new File(  findCorrectedTextRoot() );
        if ( config.correctedTextDir .exists() ) {
            // it does; grab a list of filenames that have corrected text and cache them.
            // The file names are URIs with ugly characters replaces. Rules... 
            // '/' is replaced by _S_ and ':' by _C_
            // Undo this and save a list of corrected doc URIs
            for (File entry : config.correctedTextDir .listFiles()) {
                if ( entry.getName().endsWith(".txt")) {
                    config.correctedTextMap.put(
                        entry.getName().replaceAll("_C_", ":").replaceAll("_S_", "\\/").replaceAll(".txt",""),                        
                        entry.getName() );
                }
            }
        }
        
        this.dataFileQueue = new LinkedList<File>();
        recursivelyQueueFiles(rdfDir, true);
        this.numFiles = this.dataFileQueue.size();
        log.info("=> Indexing " + rdfDir + " total files: " + this.numFiles);
        if( config.isTestMode() == false ) {
            newWorkerPool( 1 );
        }

        this.targetArchive = "";
        this.docCount = 0;
        while (this.dataFileQueue.size() > 0) {
            File rdfFile = this.dataFileQueue.remove();
            indexFile(rdfFile);
        }

        if( config.isTestMode( ) == false ) {

           // if any remaining data
           if ( this.jsonPayload.size( ) > 0 ) {
              postJson( );
           }

           // wait for all the workers to complete and commit the changes
           shutdownWorkerPool( );
           log.info("  committing to SOLR archive " + config.coreName() );
           this.solrClient.commit( config.coreName() );

           // if we actually processed any documents, process any isPartOf or hasPart references
           if( this.numObjects != 0 ) {
               updateReferenceFields( );
           }
        }
    }

    private void indexFile(File file) {

        HashMap<String, HashMap<String, ArrayList<String>>> objects;

        // Parse a file into a hashmap.
        // Key is object URI, Value is a set of key-value pairs
        // that describe the object
        try {
            objects = RdfDocumentParser.parse(file, this.errorReport, this.linkCollector, config);
        } catch (IOException e) {
            this.errorReport.addError(new IndexerError(file.getName(), "", e.getMessage()));
            return;
        }

        // Log an error for no objects and bail if size is zero
        if (objects == null || objects.size() == 0) {
            errorReport.addError(new IndexerError(file.getName(), "", "No objects in this file."));
            errorReport.flush();
            return;
        }

        // save the largest text field size
        this.largestTextSize = Math.max(this.largestTextSize, RdfDocumentParser.getLargestTextSize());

        for (Map.Entry<String, HashMap<String, ArrayList<String>>> entry : objects.entrySet()) {

            this.targetArchive = "";
            String uri = entry.getKey();
            HashMap<String, ArrayList<String>> object = entry.getValue();

            // Validate archive and push objects into new archive map
            ArrayList<String> objectArray = object.get("archive");
            if (objectArray != null) {
                String objArchive = objectArray.get(0);
                this.targetArchive = config.coreName( objArchive );
                if (!objArchive.equals( config.archiveName)) {
                    this.errorReport.addError(new IndexerError(file.getName(), uri, "The wrong archive was found. "
                        + objArchive + " should be " + config.archiveName));
                }
            } else {
                this.errorReport.addError(new IndexerError(file.getName(), uri,
                    "Unable to determine archive for this object."));
            }

            // validate all other parts of object and generate error report
            try {
                ArrayList<String> messages = ValidationUtility.validateObject(object);
                for (String message : messages) {
                    IndexerError e = new IndexerError(file.getName(), uri, message);
                    errorReport.addError(e);
                }
            } catch (Exception valEx) {
                System.err.println("ERROR Validating file:" + file.getName() + " URI: " + uri);
                valEx.printStackTrace();
                IndexerError e = new IndexerError(file.getName(), uri, valEx.getMessage());
                errorReport.addError(e);
            }

            // turn this object into an XML solr docm then xml string. Add this to the curr payload
            JsonElement jsonDoc = docToJson(uri, object);
            this.jsonPayload.add(jsonDoc);
            this.docCount++;

            // once threshold met, post the data to solr
            if ( this.jsonPayload.toString().length() >= config.maxUploadSize ) {
                if( config.isTestMode( ) == false ) {
                    postJson( );
                }
            }
        }

        this.numObjects += objects.size();
        this.errorReport.flush();
    }

    //
    // update the references for any isPartOf or hasPart fields
    //
    private void updateReferenceFields( ) {

        int page = 0;
        int size = config.pageSize;
        String fl = config.getFieldList( );
        String coreName = config.coreName( );
        List<String> orList = new ArrayList<String>(  );
        orList.add( isPartOf + "=*" );
        orList.add( hasPart + "=*" );
        boolean done = false;

        newWorkerPool( 1 );

        while( done == false ) {
           List<JsonObject> results = this.solrClient.getResultsPage( coreName, config.archiveName, page, size, fl, null, orList );

           for( JsonObject json : results ) {
              log.info( "Resolving references for " + json.get( "uri" ).getAsString( ) );
              updateDocumentReferences( json );
           }

           // are there potentially more results?
           if( results.size( ) == size ) {
               page++;
           } else {
               done = true;
           }
        }

        // if any remaining data
        if ( this.jsonPayload.size( ) > 0 ) {
            postJson( );
        }

        // wait for all the workers to complete and commit the changes
        shutdownWorkerPool( );
        log.info("  committing to SOLR archive " + config.coreName() );
        this.solrClient.commit( config.coreName() );
    }

    //
    // resolve the isPartOf or hasPart references for the specified document
    //
    private void updateDocumentReferences( final JsonObject json ) {

        String fl = config.getFieldList( );
        String coreName = config.coreName( );
        String uri = json.get( "uri" ).getAsString( );

        boolean updated = false;

        try {
            if( json.has( isPartOf ) == true ) {
                JsonArray refs = json.getAsJsonArray( isPartOf );
                JsonArray objs = new JsonArray( );
                for( int ix = 0; ix < refs.size(); ix++ ) {
                    List<String> andList = new ArrayList<String>();
                    andList.add( "uri=" + URLEncoder.encode( "\"" + refs.get( ix ).getAsString( ) + "\"", "UTF-8" ) );
                    List<JsonObject> results = this.solrClient.getResultsPage( coreName, config.archiveName, 0, 1, fl, andList, null );
                    if( results.size() == 1 ) {
                        objs.add( removeExcessFields( results.get( 0 ) ) );
                    } else {
                        // reference to a non-existent object, note in the error log
                        IndexerError e = new IndexerError( "", uri, "Cannot resolve isPartOf reference (" + refs.get( ix ).getAsString( ) +
                                                           ") for document " + uri );
                        errorReport.addError( e );
                    }
                }

                // remove the field; we may replace it with resolved data
                json.remove( isPartOf );
                updated = true;

                // did we resolve any of the references
                if( objs.size( ) != 0 ) {
                    //log.info( "UPDATING isPartOf: " + objs.toString( ) );
                    json.addProperty( isPartOf, objs.toString( ) );
                }
            }

            if( json.has( hasPart ) == true ) {
                JsonArray refs = json.getAsJsonArray( hasPart );
                JsonArray objs = new JsonArray( );
                for( int ix = 0; ix < refs.size(); ix++ ) {
                    List<String> andList = new ArrayList<String>();
                    andList.add( "uri=" + URLEncoder.encode( "\"" + refs.get( ix ).getAsString( ) + "\"", "UTF-8" ) );
                    List<JsonObject> results = this.solrClient.getResultsPage( coreName, config.archiveName, 0, 1, fl, andList, null );
                    if( results.size() == 1 ) {
                        objs.add( removeExcessFields( results.get( 0 ) ) );
                    } else {
                        // reference to a non-existent object, note in the error log
                        IndexerError e = new IndexerError( "", uri, "Cannot resolve hasPart reference (" + refs.get( ix ).getAsString( ) +
                                ") for document " + uri );
                        errorReport.addError( e );
                    }
                }

                // remove the field; we may replace it with resolved data
                json.remove( hasPart );
                updated = true;

                if( objs.size( ) != 0 ) {
                    //log.info( "UPDATING hasPart: " + objs.toString( ) );
                    json.addProperty( hasPart, objs.toString( ) );
                }
            }

            if( updated == true ) {
                this.jsonPayload.add( json );
                this.docCount++;

                // once threshold met, post the data to solr
                if ( this.jsonPayload.toString().length( ) >= config.maxUploadSize ) {
                    postJson( );
                }
            }
        } catch( UnsupportedEncodingException ex ) {
            // should never happen
        }
    }

    //
    // remove the fields we do not want for reference documents
    //
    private JsonObject removeExcessFields( JsonObject json ) {
        json.remove( isPartOf );
        json.remove( hasPart );
        json.remove( "text" );
        json.remove( "_version_" );
        json.remove( "year_sort_desc" );
        json.remove( "federation" );
        json.remove( "year" );
        json.remove( "year_sort" );
        json.remove( "year_sort_asc" );
        json.remove( "title_sort" );
        json.remove( "author_sort" );
        json.remove( "date_created" );
        json.remove( "date_updated" );

        return( json );
    }

    private JsonElement docToJson(String documentName, HashMap<String, ArrayList<String>> fields) {
        Gson gson = new Gson();
        JsonObject obj = gson.toJsonTree(fields).getAsJsonObject();
        obj.addProperty("date_created", this.timeStamp);
        obj.addProperty("date_updated", this.timeStamp);
        return obj;
    }

    private void newWorkerPool( int poolsize ) {
        this.solrExecutorService = Executors.newFixedThreadPool( poolsize );
    }

    private void shutdownWorkerPool( ) {

        // signal shutdown and wait until it is complete
        this.solrExecutorService.shutdown( );
        try {
            this.solrExecutorService.awaitTermination( 15, TimeUnit.MINUTES );
        } catch (InterruptedException e) {
            // do nothing...
        }
    }

    // async post JSON to SOLR using the worker pool
    private void postJson( ) {
        this.solrExecutorService.execute( new SolrPoster( this.jsonPayload.toString( ), this.targetArchive, this.docCount ) );
        this.jsonPayload = new JsonArray();
        this.docCount = 0;
        this.postCount++;
        if( postCount % 5 == 0 ) {
            this.solrExecutorService.execute( new SolrCommitter( this.targetArchive ) );
        }
    }

    // Worker thread to post data to solr
    private class SolrPoster implements Runnable {

        private final String payload;
        private final String tgtArchive;
        
        public SolrPoster(final String json, final String tgtArchive, int docCnt) {
            this.tgtArchive = tgtArchive;
            this.payload = json;

            log.info("  posting: payload size " + this.payload.length( ) + " with " + docCnt + " documents to SOLR archive " + tgtArchive );
        }

        public void run() {
            try {
                solrClient.postJSON(this.payload, this.tgtArchive);
            } catch (IOException e) {
                Logger.getLogger(RDFIndexer.class.getName()).error("Post to SOLR FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // Worker thread to commit data to solr
    private class SolrCommitter implements Runnable {

        private final String tgtArchive;

        public SolrCommitter( final String tgtArchive ) {
            this.tgtArchive = tgtArchive;
            log.info("  committing to SOLR archive " + tgtArchive );
        }

        public void run( ) {
            solrClient.commit( this.tgtArchive );
        }
    }
}

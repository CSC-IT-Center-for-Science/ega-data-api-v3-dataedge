/*
 * Copyright 2016 ELIXIR EBI
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
package eu.elixir.ega.ebi.dataedge.service.internal;

import com.google.common.io.ByteStreams;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import eu.elixir.ega.ebi.dataedge.config.GeneralStreamingException;
import eu.elixir.ega.ebi.dataedge.config.InternalErrorException;
import eu.elixir.ega.ebi.dataedge.config.NotFoundException;
import eu.elixir.ega.ebi.dataedge.config.PermissionDeniedException;
import eu.elixir.ega.ebi.dataedge.config.VerifyMessage;
import eu.elixir.ega.ebi.dataedge.domain.entity.Transfer;
import eu.elixir.ega.ebi.dataedge.domain.repository.TransferRepository;
import eu.elixir.ega.ebi.dataedge.dto.DownloadEntry;
import eu.elixir.ega.ebi.dataedge.dto.EventEntry;
import eu.elixir.ega.ebi.dataedge.dto.File;
import eu.elixir.ega.ebi.dataedge.dto.FileDataset;
import eu.elixir.ega.ebi.dataedge.dto.FileIndexFile;
import eu.elixir.ega.ebi.dataedge.dto.HttpResult;
import eu.elixir.ega.ebi.dataedge.dto.MyExternalConfig;
import eu.elixir.ega.ebi.dataedge.service.DownloaderLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import eu.elixir.ega.ebi.dataedge.service.FileService;
import eu.elixir.ega.ebi.egacipher.EgaSeekableCachedResStream;
import eu.elixir.ega.ebi.egacipher.EgaSeekableResStream;
import htsjdk.samtools.CRAMFileWriter;
import htsjdk.samtools.DefaultSAMRecordFactory;
import htsjdk.samtools.QueryInterval;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMFileWriterFactory;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordIterator;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.SamInputResource;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.SamReaderFactory.Option;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.cram.ref.CRAMReferenceSource;
import htsjdk.samtools.cram.ref.ReferenceSource;
import htsjdk.samtools.seekablestream.SeekableBufferedStream;
import htsjdk.samtools.seekablestream.SeekableStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 *
 * @author asenf
 */
@Service
@Transactional
@EnableDiscoveryClient
public class RemoteFileServiceImpl implements FileService {

    private final String SERVICE_URL = "http://DOWNLOADER";
    private final String RES_URL = "http://RES";
    
    @Autowired
    RestTemplate restTemplate;

    // Database Repositories/Services
    
    @Autowired
    private TransferRepository transferRepository;
    
    @Autowired
    private DownloaderLogService downloaderLogService;
    
    @Autowired
    private EurekaClient discoveryClient;

    @Autowired
    MyExternalConfig externalConfig;
    
    @Override
    @HystrixCommand
    public void getFile(Authentication auth, 
                        String file_id,
                        String destinationFormat,
                        String destinationKey,
                        long startCoordinate,
                        long endCoordinate,
                        HttpServletRequest request,
                        HttpServletResponse response) {

        // Ascertain Access Permissions for specified File ID
        File reqFile = getReqFile(file_id, auth, request); // request added for ELIXIR

        // Build Header - Specify UUID (Allow later stats query regarding this transfer)
        UUID dlIdentifier = UUID.randomUUID();
        String headerValue = dlIdentifier.toString();
        response = setHeaders(response, headerValue);

        // Variables needed for responses at the end of the function
        long timeDelta = 0;
        HttpResult xferResult = null;
        MessageDigest outDigest = null;
        
        if (reqFile != null) {
            String user_email = auth.getName(); // For Logging

            try {
                // Get Send Stream - http Response, wrap in Digest Stream
                outDigest = MessageDigest.getInstance("MD5");
                DigestOutputStream outDigestStream = new DigestOutputStream(response.getOutputStream(), outDigest);

                // Get RES data stream, and copy it to output stream
                RequestCallback requestCallback = request_ -> request_.getHeaders()
                        .setAccept(Arrays.asList(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));

                // ----------------------------------------------------------------- Callback Function for Resttemplate
                // Get Data Stream from RES ReEncryptionService --------------------
                ResponseExtractor<HttpResult> responseExtractor = response_ -> {
                    List<String> get = response_.getHeaders().get("X-Session"); // RES session UUID
                    long b = 0;
                    String inHashtext = "";
                    try {
                        // Input stream from RES, wrap in DigestStream
                        MessageDigest inDigest = MessageDigest.getInstance("MD5");
                        DigestInputStream inDigestStream = new DigestInputStream(response_.getBody(), inDigest);
                        if (inDigestStream == null) {
                            throw new GeneralStreamingException("Unable to obtain Input Stream", 2);
                        }

                        // The actual Data Transfer - copy bytes from RES to Http connection to client
                        b = ByteStreams.copy(inDigestStream, outDigestStream); // in, outputStream

                        // Done - Close Streams and obtain MD5 of received Stream
                        inDigestStream.close();
                        outDigestStream.close();
                        inHashtext = getDigestText(inDigest.digest());
                    } catch (Throwable t) {
                        inHashtext = t.getMessage();
                    }

                    // return number of bytes copied, RES session header, and MD5 of RES input stream
                    return new HttpResult(b, get, inHashtext); // This is the result of the RestTemplate
                };
                // -----------------------------------------------------------------

                /*
                 * CUSTOMISATION: If you access files by absolute path (nearly everyone)
                 * then gall getResUri with the file path instead of the file ID
                 * [...]getResUri(reqFile.getFileName(),destinationFormat[...]
                 */
                
                // Build Request URI with Ticket Parameters and get requested file from RES (timed for statistics)
                timeDelta = System.currentTimeMillis();
                xferResult = restTemplate.execute(getResUri(file_id,destinationFormat,destinationKey,startCoordinate,endCoordinate), HttpMethod.GET, requestCallback, responseExtractor);
                timeDelta = System.currentTimeMillis() - timeDelta;

            } catch (Throwable t) { // Log Error!
                EventEntry eev = getEventEntry(t, "TODO ClientIp", "Direct Download", user_email);
                downloaderLogService.logEvent(eev);

                throw new GeneralStreamingException(t.toString(), 4);
            } finally {
                if (xferResult != null) {
                    Transfer received = getResSession(xferResult.getSession().get(0)); // Shortcut -- Same Database; otherwise perform a REST call to RES
                    System.out.println("Received? " + (received==null?"null":received.toString()));

                    // Compare received MD5 with RES
                    String inHashtext = xferResult.getMd5();
                    String outHashtext = getDigestText(outDigest.digest());

                    // Store with UUID for later retrieval - in case of error or success            
                    Transfer transfer = new Transfer(headerValue,
                                                     new java.sql.Timestamp(Calendar.getInstance().getTime().getTime()),
                                                     inHashtext,
                                                     outHashtext,
                                                     0,
                                                     xferResult.getBytes(),
                                                     "DATAEDGE");
                    Transfer save = transferRepository.save(transfer);

                    // Compare - Sent MD5 equals Received MD5? - Log Download in DB
                    boolean success = outHashtext.equals(inHashtext);
                    double speed = (xferResult.getBytes()/1024.0/1024.0)/(timeDelta*1000.0);
                    System.out.println("Success? " + success + ", Speed: " + speed + " MB/s");
                    DownloadEntry dle = getDownloadEntry(success, speed, file_id, "TODO ClientIp", user_email, destinationFormat);
                    downloaderLogService.logDownload(dle);                
                }
            }
        }
    }

    /*
     * GA4GH / Semantic Functionality: Use SAMTools to access a File in Cleversafe
     */
    
    @Override
    @HystrixCommand
    @Cacheable(cacheNames="headerFile")
    public Object getFileHeader(Authentication auth, 
                                String file_id, 
                                String destinationFormat, 
                                String destinationKey) {
        Object header = null;
        
        // Ascertain Access Permissions for specified File ID
        File reqFile = getReqFile(file_id, auth, null);
        if (reqFile!=null) {
            URL resUrl = null;
            try {
                resUrl = new URL(resUrl() + "/file/archive/" + reqFile.getFileId()); // Just specify file ID
                
                SeekableStream cIn = new EgaSeekableResStream(resUrl); // Deals with coordinates
                //SeekableStream cIn = new EgaSeekableCachedResStream(resUrl); // Deals with coordinates

                // SamReader with input stream based on RES URL
                SamReader reader = 
                    SamReaderFactory.make() 
                      .validationStringency(ValidationStringency.LENIENT) 
                      .samRecordFactory(DefaultSAMRecordFactory.getInstance()) 
                      .open(SamInputResource.of(cIn));  
                header = reader.getFileHeader();
                reader.close();
            } catch (MalformedURLException ex) {
                Logger.getLogger(RemoteFileServiceImpl.class.getName()).log(Level.SEVERE, null, ex);
            } catch (IOException ex) {
                Logger.getLogger(RemoteFileServiceImpl.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        
        return header;
    }

/*    
    protected SAMFileHeader readHeader(final BinaryCodec stream, final ValidationStringency validationStringency, final String source)
        throws IOException {

        final byte[] buffer = new byte[4];
        stream.readBytes(buffer);
        if (!Arrays.equals(buffer, BAMFileConstants.BAM_MAGIC)) {
            throw new IOException("Invalid BAM file header");
        }

        final int headerTextLength = stream.readInt();
        final String textHeader = stream.readString(headerTextLength);
        final SAMTextHeaderCodec headerCodec = new SAMTextHeaderCodec();
        headerCodec.setValidationStringency(validationStringency);
        final SAMFileHeader samFileHeader = headerCodec.decode(new StringLineReader(textHeader),
                source);

        final int sequenceCount = stream.readInt();
        if (!samFileHeader.getSequenceDictionary().isEmpty()) {
            // It is allowed to have binary sequences but no text sequences, so only validate if both are present
            if (sequenceCount != samFileHeader.getSequenceDictionary().size()) {
                throw new SAMFormatException("Number of sequences in text header (" +
                        samFileHeader.getSequenceDictionary().size() +
                        ") != number of sequences in binary header (" + sequenceCount + ") for file " + source);
            }
            for (int i = 0; i < sequenceCount; i++) {
                final SAMSequenceRecord binarySequenceRecord = readSequenceRecord(stream, source);
                final SAMSequenceRecord sequenceRecord = samFileHeader.getSequence(i);
                if (!sequenceRecord.getSequenceName().equals(binarySequenceRecord.getSequenceName())) {
                    throw new SAMFormatException("For sequence " + i + ", text and binary have different names in file " +
                            source);
                }
                if (sequenceRecord.getSequenceLength() != binarySequenceRecord.getSequenceLength()) {
                    throw new SAMFormatException("For sequence " + i + ", text and binary have different lengths in file " +
                            source);
                }
            }
        } else {
            // If only binary sequences are present, copy them into samFileHeader
            final List<SAMSequenceRecord> sequences = new ArrayList<SAMSequenceRecord>(sequenceCount);
            for (int i = 0; i < sequenceCount; i++) {
                sequences.add(readSequenceRecord(stream, source));
            }
            samFileHeader.setSequenceDictionary(new SAMSequenceDictionary(sequences));
        }

        return samFileHeader;
    }
*/    
    
    @Override
    @HystrixCommand
    public void getById(Authentication auth, 
                        String idType,
                        String accession, 
                        String format, 
                        String reference,
                        long start, 
                        long end, 
                        boolean header,
                        String destinationFormat, 
                        String destinationKey, 
                        HttpServletRequest request, 
                        HttpServletResponse response) {
        
        // Adding a content header in the response: binary data
        response.addHeader("Content-Type", MediaType.valueOf("application/octet-stream").toString());
        
        String file_id = "";
        if (idType.equalsIgnoreCase("file")) { // Currently only support File IDs
            file_id = accession;
        }
        
        // Ascertain Access Permissions for specified File ID
        File reqFile = getReqFile(file_id, auth, request);
        if (reqFile!=null) {
            
            // SeekableStream on top of RES (using Eureka to obtain RES Base URL)
            SamInputResource inputResource = null;
            CRAMReferenceSource x = null;
            SeekableBufferedStream bIn = null, 
                                   bIndexIn = null;
            try {
                String extension = "";
                if (reqFile.getFileName().contains(".bam")) {
                        extension = ".bam";
                } else if (reqFile.getFileName().contains(".cram")) {
                        extension = ".cram";
                        x = new ReferenceSource(new java.io.File(externalConfig.getCramFastaReference()));
                }
                
                // BAM/CRAM File
                URL resUrl = new URL(resUrl() + "file/archive/" + reqFile.getFileId()); // Just specify file ID
                //SeekableStream cIn = (new EgaSeekableResStream(resUrl, null, null, reqFile.getFileSize())).setExtension(extension); // Deals with coordinates
                SeekableStream cIn = (new EgaSeekableCachedResStream(resUrl, null, null, reqFile.getFileSize())).setExtension(extension); // Deals with coordinates
                //bIn = new SeekableBufferedStream(cIn);
                // BAI/CRAI File
                FileIndexFile fileIndexFile = getFileIndexFile(reqFile.getFileId());
                File reqIndexFile = getReqFile(fileIndexFile.getIndexFileId(), auth, null);
                URL indexUrl = new URL(resUrl() + "file/archive/" + fileIndexFile.getIndexFileId()); // Just specify index ID
                SeekableStream cIndexIn = (new EgaSeekableCachedResStream(indexUrl, null, null, reqIndexFile.getFileSize()));
                //bIndexIn = new SeekableBufferedStream(cIndexIn);

                inputResource = SamInputResource.of(cIn).index(cIndexIn);
            } catch (Exception ex) {
                throw new InternalErrorException(ex.getMessage(), "9");
            }

            // SamReader with input stream based on RES URL (should work for BAM or CRAM)
            SamReader reader = (x==null) ?
                (SamReaderFactory.make()            // BAM File 
                  .validationStringency(ValidationStringency.LENIENT)
                  .enable(Option.CACHE_FILE_BASED_INDEXES)
                  .samRecordFactory(DefaultSAMRecordFactory.getInstance())
                  .open(inputResource)) :
                (SamReaderFactory.make()            // CRAM File
                  .referenceSource(x)
                  .validationStringency(ValidationStringency.LENIENT)
                  .enable(Option.CACHE_FILE_BASED_INDEXES)
                  .samRecordFactory(DefaultSAMRecordFactory.getInstance())
                  .open(inputResource)) ;                    
            
            SAMFileHeader fileHeader = reader.getFileHeader();
//System.out.println("HEADER :: " + fileHeader.getTextHeader());
            int iIndex = fileHeader.getSequenceIndex(reference);
//System.out.println("REFERENCE :: " + reference + "     (" + iIndex + ")");

            // Handle Request here - query Reader according to parameters
            int iStart = (int)(start);
            int iEnd = (int)(end);
            SAMRecordIterator query = null;
            if (iIndex > -1) { // singe ref was specified
                //QueryInterval[] qis = {new QueryInterval(iIndex, iStart, iEnd)};
                //query = reader.query(qis, true);
                query = reader.query(reference, iStart, iEnd, false);
            } else if ((reference==null || reference.isEmpty()) && iIndex == -1) {
                throw new GeneralStreamingException("Unknown reference: " + reference, 40);                
            } else { // no ref - ignore start/end
                query = reader.iterator();
            }

            // Open return output stream - instatiate a SamFileWriter
            OutputStream out = null;
            SAMFileWriterFactory writerFactory = new SAMFileWriterFactory();
            if (query!=null) try {
                out = response.getOutputStream();
                if (format.equalsIgnoreCase("BAM")) {
                    try (SAMFileWriter writer = writerFactory.makeBAMWriter(fileHeader, true, out)) { // writes out header
                        Stream<SAMRecord> stream = query.stream();
                        Iterator<SAMRecord> iterator = stream.iterator();
                        while (iterator.hasNext()) {
                            SAMRecord next = iterator.next();
//System.out.println(next.toString());
                            writer.addAlignment(next);
                        }
                        writer.close();
                    }
                } else if (format.equalsIgnoreCase("CRAM")) { // Must specify Reference fasta file
                    try (CRAMFileWriter writer = writerFactory
                            .makeCRAMWriter(fileHeader, out, new java.io.File(externalConfig.getCramFastaReference()))) {
                        Stream<SAMRecord> stream = query.stream();
                        Iterator<SAMRecord> iterator = stream.iterator();
                        while (iterator.hasNext()) {
                            SAMRecord next = iterator.next();
                            writer.addAlignment(next);
                        }
                        writer.close();
                    }
                }
                
            } catch (Throwable t) { // Log Error!
                EventEntry eev = getEventEntry(t, "TODO ClientIp", "Direct GA4GH Download", auth.getName());
                downloaderLogService.logEvent(eev);

                throw new GeneralStreamingException(t.toString(), 4);
            } finally {
                if (out != null) try {out.close();} catch (IOException ex) {;}
            }
        } else { // If no 404 was found, this is a permissions denied error
            throw new PermissionDeniedException(accession);
        }
    }
    
    /*
     * Helper Functions
     */
    @HystrixCommand
    private String getDigestText(byte[] inDigest) {
        BigInteger bigIntIn = new BigInteger(1,inDigest);
        String hashtext = bigIntIn.toString(16);
        while(hashtext.length() < 32 ){
            hashtext = "0"+hashtext;
        }                    
        return hashtext;
    }
    
    @HystrixCommand
    private HttpServletResponse setHeaders(HttpServletResponse response, String headerValue) {
        // Set headers for the response
        String headerKey = "X-Session";
        response.setHeader(headerKey, headerValue);

        // get MIME type of the file (actually, it's always this for now)
        String mimeType = "application/octet-stream";
        System.out.println("MIME type: " + mimeType);

        // set content attributes for the response
        response.setContentType(mimeType);        
        
        return response;
    }
    
    @HystrixCommand
    private URI getResUri(String fileStableIdPath,
                          String destFormat,
                          String destKey,
                          Long startCoord,
                          Long endCoord) {
        destFormat = destFormat.equals("AES")?"aes128":destFormat; // default to 128-bit if not specified
        String url = RES_URL + "/file";
        if (fileStableIdPath.startsWith("EGAF")) { // If an ID is specified - resolve this in RES
            url += "/archive/" + fileStableIdPath;
        }
        
        // Build components based on Parameters provided
        UriComponentsBuilder builder = null;
        
        if (startCoord==0 && endCoord==0 && destFormat.equalsIgnoreCase("plain")) {
            builder = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("destinationFormat", destFormat)
                    .queryParam("filePath", fileStableIdPath); // TEST!!
        } else if (startCoord==0 && endCoord==0) {
            builder = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("destinationFormat", destFormat)
                    .queryParam("destinationKey", destKey)
                    .queryParam("filePath", fileStableIdPath); // TEST!!
        } else if (destFormat.equalsIgnoreCase("plain")) {
            builder = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("destinationFormat", destFormat)
                    .queryParam("startCoordinate", startCoord)
                    .queryParam("endCoordinate", endCoord)
                    .queryParam("filePath", fileStableIdPath); // TEST!!
        } else {
            builder = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("destinationFormat", destFormat)
                    .queryParam("destinationKey", destKey)
                    .queryParam("startCoordinate", startCoord)
                    .queryParam("endCoordinate", endCoord)
                    .queryParam("filePath", fileStableIdPath); // TEST!!
        }

        return builder.build().encode().toUri();
    }
    
    @HystrixCommand
    private Transfer getResSession(String resSession) {
        Transfer sessionResponse = null;
        try {
            sessionResponse = restTemplate.getForObject(RES_URL + "/session/{ticket}/", Transfer.class, resSession);
        } catch (HttpClientErrorException ex) {
            sessionResponse = new Transfer();
        }
        return sessionResponse;
    }
    
    @HystrixCommand
    private DownloadEntry getDownloadEntry(boolean success, double speed, String fileId,
                                                                          String clientIp,
                                                                          String email,
                                                                          String encryptionType) {
        DownloadEntry dle = new DownloadEntry();
            dle.setDownloadLogId(0L);
            dle.setDownloadSpeed(speed);
            dle.setDownloadStatus(success?"success":"failed");
            dle.setFileId(fileId);
            dle.setClientIp(clientIp);
            dle.setEmail(email);
            dle.setDownloadProtocol("http");
            dle.setServer("DATAEDGE");
            dle.setEncryptionType(encryptionType);
            dle.setCreated(new java.sql.Timestamp(Calendar.getInstance().getTime().getTime())); 

        return dle;
    }
    
    @HystrixCommand
    private EventEntry getEventEntry(Throwable t, String clientIp,
                                                  String ticket,
                                                  String email) {
        EventEntry eev = new EventEntry();
            eev.setEventId("0");
            eev.setClientIp(clientIp);
            eev.setEvent(t.toString());
            eev.setDownloadTicket(ticket);
            eev.setEventType("Error");
            eev.setEmail(email);
            eev.setCreated(new java.sql.Timestamp(Calendar.getInstance().getTime().getTime())); 
        
        return eev;
    }
    
    @HystrixCommand
    @Cacheable(cacheNames="reqFile")
    private File getReqFile(String file_id, Authentication auth, HttpServletRequest request) {

        // Obtain all Authorised Datasets (Provided by EGA AAI)
        HashSet<String> permissions = new HashSet<>();
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        if (authorities != null && authorities.size() > 0) {
            Iterator<? extends GrantedAuthority> iterator = authorities.iterator();
            while (iterator.hasNext()) {
                GrantedAuthority next = iterator.next();
                permissions.add(next.getAuthority());
            }
        } else if (request!=null) { // ELIXIR User Case: Obtain Permmissions from X-Permissions Header
            //String permissions_ = request.getHeader("X-Permissions");
            try {
                List<String> permissions_ = (new VerifyMessage(request.getHeader("X-Permissions"))).getPermissions();
                if (permissions_ != null && permissions_.size() > 0) {
                    //StringTokenizer t = new StringTokenizer(permissions_, ",");
                    //while (t!=null && t.hasMoreTokens()) {
                    for (String ds:permissions) {
                        //String ds = t.nextToken();
                        if (ds != null) {
                            permissions.add(ds);
                        }
                    }
                }            
            } catch (Exception ex) {}
        }
        
        ResponseEntity<FileDataset[]> forEntityDataset = restTemplate.getForEntity(SERVICE_URL + "/file/{file_id}/datasets", FileDataset[].class, file_id);
        FileDataset[] bodyDataset = forEntityDataset.getBody();        
        
        File reqFile = null;
        ResponseEntity<File[]> forEntity = restTemplate.getForEntity(SERVICE_URL + "/file/{file_id}", File[].class, file_id);
        File[] body = forEntity.getBody();
        if (body!=null && bodyDataset!=null) {
            for (FileDataset f:bodyDataset) {
                String dataset_id = f.getDatasetId();
                if (permissions.contains(dataset_id) && body.length>=1) {
                    reqFile = body[0];
                    reqFile.setDatasetId(dataset_id);
                    break;
                }
            }
            
            // If there's no file size in the database, obtain it from RES
//            if (reqFile.getFileSize() == 0) {
                ResponseEntity<Long> forSize = restTemplate.getForEntity(RES_URL + "/file/archive/{file_id}/size", Long.class, file_id);
                reqFile.setFileSize(forSize.getBody());
//            }
        } else { // 404 File Not Found
            throw new NotFoundException(file_id, "4");
        }
        return reqFile;
    }

    @HystrixCommand
    private String mapRunToFile(String runId) {
        
        // Can't access Runs yet... TODO
        
        return "";
    }

    @HystrixCommand
    public String resUrl() {
        InstanceInfo instance = discoveryClient.getNextServerFromEureka("RES", false);
        return instance.getHomePageUrl();
    }    

    @HystrixCommand
    @Cacheable(cacheNames="indexFile")
    private FileIndexFile getFileIndexFile(String file_id) {
        FileIndexFile indexFile = null;
        ResponseEntity<FileIndexFile[]> forEntity = restTemplate.getForEntity(SERVICE_URL + "/file/{file_id}/index", FileIndexFile[].class, file_id);
        FileIndexFile[] body = forEntity.getBody();
        if (body!=null && body.length>=1) {
            indexFile = body[0];
        }
        return indexFile;
    }

    @Override
    @HystrixCommand
    @Cacheable(cacheNames="fileSize")
    public ResponseEntity getHeadById(Authentication auth, 
                            String idType, 
                            String accession, 
                            HttpServletRequest request, 
                            HttpServletResponse response) {
        String file_id = "";
        if (idType.equalsIgnoreCase("file")) { // Currently only support File IDs
            file_id = accession;
        }
        
        // Ascertain Access Permissions for specified File ID
        File reqFile = getReqFile(file_id, auth, null);
        if (reqFile!=null) {
            response.addHeader("Content-Length", String.valueOf(reqFile.getFileSize()) );
            return new ResponseEntity(HttpStatus.OK);
        }
        
        return new ResponseEntity(HttpStatus.UNAUTHORIZED);
    }

    
}
